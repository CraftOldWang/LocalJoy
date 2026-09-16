package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.ProductOrder;
import com.hmdp.mapper.ProductOrderMapper;
import com.hmdp.mq.RocketMqConstants;
import com.hmdp.service.IProductOrderService;
import com.hmdp.service.ISeckillProductService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ProductOrderServiceImpl extends ServiceImpl<ProductOrderMapper, ProductOrder>
        implements IProductOrderService {
    private static final int UNPAID = 1, PAID = 2, CANCELED = 4;
    private static final String TIMEOUT = "TIMEOUT_AUTO_CLOSE", DUPLICATE = "DUPLICATE_BUYER";
    private static final DefaultRedisScript<Long> RESERVE = script("product_seckill.lua");
    private static final DefaultRedisScript<Long> ROLLBACK = script("product_reservation_rollback.lua");
    private final TransactionTemplate transactions;
    @Resource private ISeckillProductService seckillProductService;
    @Resource private RedisIdWorker redisIdWorker;
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private RedissonClient redissonClient;
    @Resource private RocketMQTemplate rocketMQTemplate;
    @Value("${localjoy.order.timeout-seconds:1800}") private long timeoutSeconds;
    @Value("${localjoy.order.delay-level:16}") private int delayLevel;

    public ProductOrderServiceImpl(TransactionTemplate transactions) { this.transactions = transactions; }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public Result seckillProduct(Long productId) {
        if (productId == null || productId <= 0) return Result.fail("商品不存在");
        Long userId = UserHolder.getUser().getId();
        RLock lock = buyerLock(productId, userId);
        if (!acquire(lock)) return Result.fail("请求处理中，请稍后查询订单");
        try {
            Long orderId = redisIdWorker.nextId("product_order");
            Long result = stringRedisTemplate.execute(RESERVE, Arrays.asList(
                    RedisConstants.PRODUCT_SECKILL_STOCK_KEY + productId,
                    RedisConstants.PRODUCT_SECKILL_BUYER_KEY + productId,
                    RedisConstants.PRODUCT_SECKILL_META_KEY + productId), userId.toString(), orderId.toString());
            if (!Long.valueOf(0).equals(result)) {
                if (Long.valueOf(1).equals(result)) return Result.fail("已被抢完");
                if (Long.valueOf(2).equals(result)) return Result.fail("你已下过单");
                if (Long.valueOf(4).equals(result)) return Result.fail("活动尚未开始");
                if (Long.valueOf(5).equals(result)) return Result.fail("活动已结束");
                return Result.fail("活动未就绪");
            }
            ProductOrder order = new ProductOrder().setId(orderId).setUserId(userId).setProductId(productId);
            try {
                requireSendOk(rocketMQTemplate.syncSend(RocketMqConstants.PRODUCT_SECKILL_ORDER_TOPIC, order));
            } catch (Exception e) {
                // The consumer takes the SAME buyer lock and verifies reservation ownership.
                // If send timed out but arrived at the broker, it cannot consume a rolled-back reservation.
                rollbackReservation(order, "");
                log.warn("商品订单发送失败，已撤销预占。orderId={}", orderId, e);
                return Result.fail("订单发送失败，请重试");
            }
            // Accepted by MQ, not proof of database commit; string preserves 64-bit ID in JS.
            return Result.ok(orderId.toString());
        } finally { release(lock); }
    }

    @Override
    public void createProductOrder(ProductOrder incoming) {
        RLock lock = buyerLock(incoming.getProductId(), incoming.getUserId());
        if (!acquire(lock)) throw new IllegalStateException("订单正在处理，稍后重试");
        try {
            ProductOrder order = transactions.execute(tx -> {
                ProductOrder existing = getById(incoming.getId());
                if (existing != null) {
                    if (!existing.getUserId().equals(incoming.getUserId())
                            || !existing.getProductId().equals(incoming.getProductId())) {
                        throw new IllegalArgumentException("订单消息身份不一致");
                    }
                    return existing;
                }
                Object owner = stringRedisTemplate.opsForHash().get(
                        RedisConstants.PRODUCT_SECKILL_BUYER_KEY + incoming.getProductId(), incoming.getUserId().toString());
                if (!incoming.getId().toString().equals(owner)) return null;
                ProductOrder active = activeOrder(incoming.getProductId(), incoming.getUserId());
                String rejection = active == null ? null : DUPLICATE;
                if (rejection == null && !seckillProductService.update().setSql("stock = stock - 1")
                        .eq("product_id", incoming.getProductId()).gt("stock", 0).update()) {
                    rejection = "DB_STOCK_EXHAUSTED";
                }
                // Persist rejected attempts too: duplicate messages cannot repeatedly compensate.
                ProductOrder created = new ProductOrder().setId(incoming.getId()).setUserId(incoming.getUserId())
                        .setProductId(incoming.getProductId()).setStatus(rejection == null ? UNPAID : CANCELED)
                        .setVersion(0).setCloseReason(rejection).setCreateTime(LocalDateTime.now())
                        .setExpireTime(LocalDateTime.now().plusSeconds(timeoutSeconds));
                if (!save(created)) throw new IllegalStateException("订单写入失败");
                return created;
            });
            if (order == null) return;
            if (Integer.valueOf(UNPAID).equals(order.getStatus())) {
                // If sending this message fails, propagate to MQ. Redelivery retries this step
                // after observing the existing order; stock is never deducted a second time.
                sendOrderTimeoutDelayMessage(order.getId());
            } else if (Integer.valueOf(CANCELED).equals(order.getStatus())) {
                ProductOrder active = DUPLICATE.equals(order.getCloseReason())
                        ? activeOrder(order.getProductId(), order.getUserId()) : null;
                rollbackReservation(order, active == null ? "" : active.getId().toString());
            }
        } finally { release(lock); }
    }

    private ProductOrder activeOrder(Long productId, Long userId) {
        return query().eq("product_id", productId).eq("user_id", userId)
                .ne("status", CANCELED).last("LIMIT 1").one();
    }

    @Override
    public void sendOrderTimeoutDelayMessage(Long orderId) {
        requireSendOk(rocketMQTemplate.syncSend(RocketMqConstants.PRODUCT_ORDER_TIMEOUT_TOPIC,
                MessageBuilder.withPayload(orderId).build(), 3000, delayLevel));
    }

    @Override
    public void cancelOrder(Long orderId) {
        ProductOrder identity = getById(orderId);
        if (identity == null) return;
        RLock lock = buyerLock(identity.getProductId(), identity.getUserId());
        if (!acquire(lock)) throw new IllegalStateException("关单等待订单处理，稍后重试");
        try {
        ProductOrder order = transactions.execute(tx -> {
            ProductOrder current = getById(orderId);
            if (current == null) return null;
            if (Integer.valueOf(CANCELED).equals(current.getStatus())) {
                return TIMEOUT.equals(current.getCloseReason()) ? current : null;
            }
            if (!Integer.valueOf(UNPAID).equals(current.getStatus())) return null;
            if (current.getExpireTime() == null || current.getExpireTime().isAfter(LocalDateTime.now())) return current;
            boolean updated = update().set("status", CANCELED).set("close_reason", TIMEOUT)
                    .setSql("version = version + 1").eq("id", orderId).eq("status", UNPAID)
                    .eq("version", current.getVersion()).le("expire_time", LocalDateTime.now()).update();
            if (!updated) return null;
            if (!seckillProductService.update().setSql("stock = stock + 1")
                    .eq("product_id", current.getProductId()).update()) {
                throw new IllegalStateException("关单库存回补失败");
            }
            return current.setStatus(CANCELED).setCloseReason(TIMEOUT);
        });
        if (order == null) return;
        if (Integer.valueOf(UNPAID).equals(order.getStatus())) {
            // An early, duplicated or misconfigured delay message cannot close before deadline.
            sendOrderTimeoutDelayMessage(orderId);
        } else {
            // If Redis fails after DB commit, MQ redelivery observes CANCELED and retries only this step.
            rollbackReservation(order, "");
        }
        } finally { release(lock); }
    }

    @Override
    public Result payOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        Boolean paid = transactions.execute(tx -> {
            ProductOrder order = getById(orderId);
            if (order == null || !userId.equals(order.getUserId())) return false;
            if (Integer.valueOf(PAID).equals(order.getStatus())) return true;
            if (!Integer.valueOf(UNPAID).equals(order.getStatus())) return false;
            boolean updated = update().set("status", PAID).set("pay_time", LocalDateTime.now())
                    .setSql("version = version + 1").eq("id", orderId).eq("status", UNPAID)
                    .eq("version", order.getVersion()).gt("expire_time", LocalDateTime.now()).update();
            if (updated) return true;
            // An overlapping confirmation may have won the CAS. Use a locking/current read
            // rather than this transaction's repeatable-read snapshot.
            ProductOrder latest = query().eq("id", orderId).last("FOR UPDATE").one();
            return latest != null && Integer.valueOf(PAID).equals(latest.getStatus());
        });
        return Boolean.TRUE.equals(paid) ? Result.ok() : Result.fail("订单不存在、已超时或状态已变更");
    }

    @Override
    public Result queryMyOrder(Long id) {
        ProductOrder order = getById(id);
        if (order == null) return Result.fail("订单尚未生成或不存在，请稍后重试");
        if (!UserHolder.getUser().getId().equals(order.getUserId())) return Result.fail("订单不存在");
        return Result.ok(order);
    }

    @Override
    public Result queryMyOrders(Integer current) {
        if (current == null || current < 1 || current > 10000) return Result.fail("页码不合法");
        Page<ProductOrder> page = query().eq("user_id", UserHolder.getUser().getId())
                .orderByDesc("create_time").orderByDesc("id").page(new Page<>(current, 10));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    private void rollbackReservation(ProductOrder order, String replacement) {
        stringRedisTemplate.execute(ROLLBACK, Arrays.asList(
                RedisConstants.PRODUCT_SECKILL_STOCK_KEY + order.getProductId(),
                RedisConstants.PRODUCT_SECKILL_BUYER_KEY + order.getProductId()),
                order.getUserId().toString(), order.getId().toString(), replacement);
    }
    private RLock buyerLock(Long productId, Long userId) {
        return redissonClient.getLock("product:order:" + productId + ":" + userId);
    }
    private boolean acquire(RLock lock) {
        try { return lock.tryLock(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private void release(RLock lock) { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    private void requireSendOk(SendResult result) {
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK)
            throw new IllegalStateException("MQ 未确认持久化成功");
    }
}
