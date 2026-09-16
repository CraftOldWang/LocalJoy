package com.hmdp.payment;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ProductOrder;
import com.hmdp.mapper.PaymentAttemptMapper;
import com.hmdp.mapper.ProductOrderMapper;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class PaymentService {
    private final ProductOrderMapper orders;
    private final PaymentAttemptMapper attempts;
    private final PaymentGateway gateway;
    private final AlipayProperties config;
    private final RedissonClient redis;
    private final TransactionTemplate tx;
    public PaymentService(ProductOrderMapper orders, PaymentAttemptMapper attempts, PaymentGateway gateway,
                          AlipayProperties config, RedissonClient redis, TransactionTemplate tx) {
        this.orders = orders; this.attempts = attempts; this.gateway = gateway;
        this.config = config; this.redis = redis; this.tx = tx;
    }
    public Map<String, Object> options() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alipayReady", gateway.ready()); result.put("mockEnabled", config.isMockEnabled());
        result.put("sandbox", true); result.put("callbackConfigured", !config.getNotifyUrl().trim().isEmpty());
        return result;
    }
    public boolean mockAllowed(Long orderId) { return config.isMockEnabled() && attempts.selectById(orderId) == null; }
    public Result view(Long orderId) {
        ProductOrder order = owned(orderId);
        if (order == null) return Result.fail("订单不存在");
        return Result.ok(viewOf(attempts.selectById(orderId)));
    }
    private Map<String, Object> viewOf(PaymentAttempt attempt) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (attempt == null) { view.put("state", "NONE"); return view; }
        view.put("state", attempt.getState()); view.put("amount", attempt.getAmount());
        view.put("tradeNo", attempt.getTradeNo()); view.put("lastCheckedAt", attempt.getLastCheckedAt());
        if ("WAITING".equals(attempt.getState())) view.put("qrCode", attempt.getQrCode());
        return view;
    }
    public Result start(Long orderId) {
        ProductOrder identity = owned(orderId);
        if (identity == null) return Result.fail("订单不存在");
        if (!gateway.ready()) return Result.fail("支付宝沙箱尚未配置，请按仓库支付指南填写本机配置");
        return locked(identity, () -> {
            ProductOrder order = orders.selectById(orderId);
            if (!Integer.valueOf(1).equals(order.getStatus()) || order.getExpireTime() == null
                    || !order.getExpireTime().isAfter(LocalDateTime.now().plusSeconds(5))) return Result.fail("订单状态已变化或已到支付截止时间");
            if (order.getTotalAmount() == null || order.getTotalAmount() < 1 || order.getTotalAmount() > 10000000000L
                    || order.getSubject() == null) return Result.fail("这笔历史订单没有有效金额快照，请重新下单");
            PaymentAttempt attempt = attempts.selectById(orderId);
            if (attempt != null) {
                requireIdentity(attempt);
                // Never issue another create after a timeout/restart, even when query says NOT_FOUND.
                return Result.ok(viewOf(attempt));
            }
            PaymentAttempt created = new PaymentAttempt().setOrderId(orderId).setOutTradeNo("LJ" + orderId)
                    .setAppId(config.getAppId()).setSellerId(config.getSellerId()).setAmount(order.getTotalAmount())
                    .setState("CREATING").setCreateTime(LocalDateTime.now()).setUpdateTime(LocalDateTime.now());
            tx.execute(status -> {
                attempts.insert(created);
                int changed = orders.update(null, new UpdateWrapper<ProductOrder>().eq("id", orderId).eq("status", 1)
                        .eq("version", order.getVersion()).set("pay_type", 2).setSql("version=version+1"));
                if (changed != 1) throw new IllegalStateException("订单状态已变化");
                return null;
            });
            try {
                String qr = gateway.create(created, order);
                saveState(created.setQrCode(qr), "WAITING", null);
            } catch (RuntimeException e) {
                saveState(created, "UNKNOWN", "CREATE_UNCERTAIN");
            }
            return Result.ok(viewOf(created));
        });
    }
    public Result syncOwned(Long orderId) {
        ProductOrder identity = owned(orderId);
        if (identity == null) return Result.fail("订单不存在");
        return locked(identity, () -> {
            PaymentAttempt attempt = attempts.selectById(orderId);
            if (attempt != null && !terminal(attempt)) syncLocked(attempt);
            return Result.ok(viewOf(attempts.selectById(orderId)));
        });
    }
    public void syncSystem(Long orderId) {
        ProductOrder identity = orders.selectById(orderId);
        if (identity != null) locked(identity, () -> {
            PaymentAttempt attempt = attempts.selectById(orderId);
            if (attempt != null && !terminal(attempt)) syncLocked(attempt);
            return null;
        });
    }
    private void syncLocked(PaymentAttempt attempt) {
        requireIdentity(attempt);
        if (attempt.getLastCheckedAt() != null && attempt.getLastCheckedAt().isAfter(LocalDateTime.now().minusSeconds(5))) return;
        saveState(attempt, attempt.getState(), null);
        PaymentGateway.Trade trade = gateway.query(attempt.getOutTradeNo());
        validateQuery(attempt, trade);
        if (trade.paid()) { settle(attempt, trade); return; }
        if ("TRADE_CLOSED".equals(trade.getStatus())) saveState(attempt, "CLOSED", null);
        // NOT_FOUND or WAIT_BUYER_PAY cannot resolve whether a lost create request is still in flight.
    }
    /** Called by the order service while holding the SAME buyer lock, before its local cancel transaction. */
    public boolean beforeClose(ProductOrder order) {
        PaymentAttempt attempt = attempts.selectById(order.getId());
        if (attempt == null) return true;
        requireIdentity(attempt);
        if ("CLOSED".equals(attempt.getState())) return true;
        if ("SUCCEEDED".equals(attempt.getState()) || "REVIEW_REQUIRED".equals(attempt.getState())) return false;
        saveState(attempt, "CLOSING", null);
        PaymentGateway.Trade trade = gateway.query(attempt.getOutTradeNo());
        validateQuery(attempt, trade);
        if (trade.paid()) { settle(attempt, trade); return false; }
        if ("TRADE_CLOSED".equals(trade.getStatus()) || gateway.close(attempt.getOutTradeNo())) {
            saveState(attempt, "CLOSED", null); return true;
        }
        // Close may have lost a race to payment. Query again; do not release stock on uncertainty.
        trade = gateway.query(attempt.getOutTradeNo());
        validateQuery(attempt, trade);
        if (trade.paid()) { settle(attempt, trade); return false; }
        throw new IllegalStateException("渠道交易尚未确认关闭，保留库存并等待重试");
    }
    public boolean notify(Map<String, String> parameters) {
        if (!gateway.verify(parameters)) return false;
        PaymentAttempt attempt = attempts.selectOne(new QueryWrapper<PaymentAttempt>().eq("out_trade_no", parameters.get("out_trade_no")));
        if (attempt == null || !attempt.getAppId().equals(parameters.get("app_id"))
                || !attempt.getSellerId().equals(parameters.get("seller_id"))) return false;
        try {
            if (AlipaySandboxGateway.cents(parameters.get("total_amount")) != attempt.getAmount()) return false;
            String state = parameters.get("trade_status");
            if (!"TRADE_SUCCESS".equals(state) && !"TRADE_FINISHED".equals(state)) return true;
            ProductOrder identity = orders.selectById(attempt.getOrderId());
            if (identity == null) return false;
            return locked(identity, () -> {
                settle(attempts.selectById(attempt.getOrderId()), new PaymentGateway.Trade(state,
                        parameters.get("out_trade_no"), parameters.get("trade_no"), attempt.getAmount()));
                return true;
            });
        } catch (Exception e) {
            log.warn("支付通知未处理，等待渠道重试。orderId={}", attempt.getOrderId());
            return false;
        }
    }
    private void settle(PaymentAttempt attempt, PaymentGateway.Trade trade) {
        if (!attempt.getOutTradeNo().equals(trade.getOutTradeNo()) || !attempt.getAmount().equals(trade.getAmount())
                || trade.getTradeNo() == null || !trade.getTradeNo().matches("[0-9A-Za-z_-]{1,64}")
                || (attempt.getTradeNo() != null && !attempt.getTradeNo().equals(trade.getTradeNo())))
            throw new IllegalArgumentException("渠道交易身份或金额不一致");
        tx.execute(status -> {
            ProductOrder order = orders.selectById(attempt.getOrderId());
            if (Integer.valueOf(2).equals(order.getStatus()) && Integer.valueOf(2).equals(order.getPayType())) {
                saveState(attempt.setTradeNo(trade.getTradeNo()), "SUCCEEDED", null); return null;
            }
            if (!Integer.valueOf(1).equals(order.getStatus())) {
                // A conflicting terminal state is retained for manual refund/reconciliation, never silently lost.
                saveState(attempt.setTradeNo(trade.getTradeNo()), "REVIEW_REQUIRED", "PAID_AFTER_LOCAL_TERMINAL");
                log.error("渠道已支付但本地终态冲突，需要核账。orderId={}", order.getId());
                return null;
            }
            // Channel success is authoritative even when its notification arrives after our deadline.
            int changed = orders.update(null, new UpdateWrapper<ProductOrder>().eq("id", order.getId()).eq("status", 1)
                    .eq("version", order.getVersion()).set("status", 2).set("pay_type", 2)
                    .set("pay_time", LocalDateTime.now()).setSql("version=version+1"));
            if (changed != 1) throw new IllegalStateException("订单状态竞争，稍后重试");
            saveState(attempt.setTradeNo(trade.getTradeNo()), "SUCCEEDED", null);
            return null;
        });
    }
    private void validateQuery(PaymentAttempt attempt, PaymentGateway.Trade trade) {
        if (!attempt.getOutTradeNo().equals(trade.getOutTradeNo())
                || (!"NOT_FOUND".equals(trade.getStatus()) && !attempt.getAmount().equals(trade.getAmount())))
            throw new IllegalArgumentException("渠道查单身份或金额不一致");
    }
    private boolean terminal(PaymentAttempt a) {
        return Arrays.asList("CLOSED", "SUCCEEDED", "REVIEW_REQUIRED").contains(a.getState());
    }
    private void requireIdentity(PaymentAttempt attempt) {
        if (!gateway.ready() || !attempt.getAppId().equals(config.getAppId()) || !attempt.getSellerId().equals(config.getSellerId()))
            throw new IllegalStateException("该订单的沙箱配置不可用，请恢复原应用配置后重试");
    }
    private void saveState(PaymentAttempt attempt, String state, String error) {
        attempt.setState(state).setLastError(error).setLastCheckedAt(LocalDateTime.now()).setUpdateTime(LocalDateTime.now());
        if (attempts.updateById(attempt) != 1) throw new IllegalStateException("支付流水更新失败");
    }
    private ProductOrder owned(Long id) {
        ProductOrder order = id == null ? null : orders.selectById(id);
        return order != null && UserHolder.getUser() != null && order.getUserId().equals(UserHolder.getUser().getId()) ? order : null;
    }
    private <T> T locked(ProductOrder order, Supplier<T> work) {
        RLock lock = redis.getLock("product:order:" + order.getProductId() + ":" + order.getUserId());
        try {
            if (!lock.tryLock(3, TimeUnit.SECONDS)) throw new IllegalStateException("支付状态正在确认，请稍后查询");
            return work.get();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("操作已中断"); }
        finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }
}
