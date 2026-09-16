package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.RocketMqConstants;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final int ORDER_STATUS_UNPAID = 1;
    private static final int ORDER_STATUS_CANCELED = 4;
    private static final String CLOSE_REASON_TIMEOUT = "TIMEOUT_AUTO_CLOSE";
    private static final DefaultRedisScript<Long> ROLLBACK_SECKILL_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('setnx', KEYS[1], '1') == 0 then return 0 end\n" +
                    "redis.call('expire', KEYS[1], ARGV[3])\n" +
                    "redis.call('incrby', KEYS[2], 1)\n" +
                    "redis.call('srem', KEYS[3], ARGV[2])\n" +
                    "return 1",
            Long.class
    );

    @Autowired
    private ISeckillVoucherService seckillVoucherService;


    private final TransactionTemplate transactionTemplate;


    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    public VoucherOrderServiceImpl(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    // 总体流程：前端传来voucherId , 由用户auth头会获取到userId , 我们要通过这两个信息
    // 首先拿去确认一人一单 (lua脚本还同时负责检查库存)
    // 若通过检验， 就异步下单(一个函数来实现)
    // 这个东西因为要调数据库， 因此要走事务， 但是由于代理对象....因此需要提前获取
    // 然后操作数据库， 给相应 orderId 对应的对象 存入东西。(其实也就是userId, voucherId),然后放进数据库。
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 执行Lua脚本判断一人一单和库存
        Long userId = UserHolder.getUser().getId();
        // 这个不是感觉有点不妙吗？玩意失败了，也会由订单id？？不过只是redis里有，数据库里无。
        Long orderId = redisIdWorker.nextId("order");

        long result = 0;
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(), // 或者我随便构造一个List传进去也行吧hh
                    voucherId.toString(),
                    userId.toString()
            );
        } catch (Exception e) {
            log.error("执行秒杀脚本失败", e);
            return Result.fail("下订单失败");
        }
        // 是否能下单
        if (result == 1 || result == 2) {
            return Result.fail(result == 1 ? "已被抢完" : "你已下过单");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        try {
            rocketMQTemplate.syncSend(RocketMqConstants.SECKILL_ORDER_TOPIC, voucherOrder);
        } catch (Exception e) {
            log.error("发送秒杀订单消息失败", e);
            rollbackRedisSeckillState(voucherId, userId);
            return Result.fail("下订单失败");
        }
        // 返回orderId
        return Result.ok(orderId);

    }

    /**
     * 发送延时关单消息
     * 使用 RocketMQ 4.x 延时级别 16 = 30分钟
     */
    @Override
    public void sendOrderTimeoutDelayMessage(Long orderId) {
        Message<Long> message = MessageBuilder.withPayload(orderId).build();
        // syncSend(destination, message, timeout, delayLevel)
        // delayLevel 16 对应 30 分钟
        rocketMQTemplate.syncSend(
                RocketMqConstants.ORDER_TIMEOUT_TOPIC,
                message,
                3000,
                RocketMqConstants.ORDER_TIMEOUT_DELAY_LEVEL
        );
        log.info("已发送延时关单消息，orderId={}，延时级别={}", orderId, RocketMqConstants.ORDER_TIMEOUT_DELAY_LEVEL);
    }

    private void rollbackRedisSeckillState(Long voucherId, Long userId) {
        stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        stringRedisTemplate.opsForSet().remove(RedisConstants.SECKILL_ORDER_KEY + voucherId, userId.toString());
    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 重复地检查 一人一单、库存(顺带扣减)， 然后写入数据库表 voucher_order？
        // 或者就别检验了...
        // 直接开写, 就俩表

        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 锁一下，作为最终兜底？？？我不是很理解
        RLock redisLock = redissonClient.getLock("order:" + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            log.error("不满足一人一单");
            throw new IllegalStateException("重复下单");
        }

        try {
            Long timeoutOrderId = transactionTemplate.execute(status -> {
                VoucherOrder consumedOrder = getById(voucherOrder.getId());
                if (consumedOrder != null) {
                    if (Integer.valueOf(ORDER_STATUS_UNPAID).equals(consumedOrder.getStatus())) {
                        return consumedOrder.getId();
                    }
                    log.info("秒杀订单消息重复消费，订单已存在且无需处理。orderId={}，status={}",
                            consumedOrder.getId(), consumedOrder.getStatus());
                    return null;
                }

                // 检查一人一单
                VoucherOrder existingOrder = query()
                        .eq("voucher_id", voucherId)
                        .eq("user_id", userId)
                        .ne("status", ORDER_STATUS_CANCELED)
                        .last("LIMIT 1")
                        .one();
                if (existingOrder != null) {
                    log.error("一个人只能下一单");
                    return Integer.valueOf(ORDER_STATUS_UNPAID).equals(existingOrder.getStatus()) ? existingOrder.getId() : null;
                }
                boolean success = seckillVoucherService.update()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", voucherId)
                        .gt("stock", 0)
                        .update();
                if (!success) {
                    log.error("库存不足");
                    return null;
                }
                if (!save(voucherOrder)) {
                    throw new IllegalStateException("保存订单失败");
                }
                return voucherOrder.getId();
            });
            if (timeoutOrderId != null) {
                sendOrderTimeoutDelayMessage(timeoutOrderId);
            }
        } finally {
            redisLock.unlock();
        }


    }

    @Override
    public void cancelOrder(Long orderId) {
        VoucherOrder cancelledOrder = transactionTemplate.execute(status -> {
            VoucherOrder order = getById(orderId);
            if (order == null) {
                log.warn("超时关单：订单不存在，orderId={}", orderId);
                return null;
            }

            if (!Integer.valueOf(ORDER_STATUS_UNPAID).equals(order.getStatus())) {
                if (Integer.valueOf(ORDER_STATUS_CANCELED).equals(order.getStatus())
                        && CLOSE_REASON_TIMEOUT.equals(order.getCloseReason())) {
                    return order;
                }
                log.info("超时关单：订单已非未支付状态，无需关闭。orderId={}，当前状态={}", orderId, order.getStatus());
                return null;
            }

            boolean updated = update()
                    .set("status", ORDER_STATUS_CANCELED)
                    .set("close_reason", CLOSE_REASON_TIMEOUT)
                    .eq("id", orderId)
                    .eq("status", ORDER_STATUS_UNPAID)
                    .update();

            if (!updated) {
                log.info("超时关单：CAS 更新失败（订单可能已被支付），orderId={}", orderId);
                VoucherOrder latestOrder = getById(orderId);
                if (latestOrder != null
                        && Integer.valueOf(ORDER_STATUS_CANCELED).equals(latestOrder.getStatus())
                        && CLOSE_REASON_TIMEOUT.equals(latestOrder.getCloseReason())) {
                    return latestOrder;
                }
                return null;
            }

            boolean stockRollback = seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", order.getVoucherId())
                    .update();
            if (!stockRollback) {
                log.error("超时关单：库存回滚失败，orderId={}，voucherId={}", orderId, order.getVoucherId());
                throw new IllegalStateException("库存回滚失败");
            }

            return order;
        });

        if (cancelledOrder == null) {
            return;
        }
        rollbackRedisAfterCancel(cancelledOrder);
    }

    private void rollbackRedisAfterCancel(VoucherOrder order) {
        Long result = stringRedisTemplate.execute(
                ROLLBACK_SECKILL_SCRIPT,
                Arrays.asList(
                        RedisConstants.SECKILL_ORDER_CLOSE_KEY + order.getId(),
                        RedisConstants.SECKILL_STOCK_KEY + order.getVoucherId(),
                        RedisConstants.SECKILL_ORDER_KEY + order.getVoucherId()
                ),
                order.getId().toString(),
                order.getUserId().toString(),
                String.valueOf(TimeUnit.DAYS.toSeconds(30))
        );
        if (Long.valueOf(1L).equals(result)) {
            log.info("超时关单成功，orderId={}，voucherId={}，userId={}", order.getId(), order.getVoucherId(), order.getUserId());
        } else {
            log.info("超时关单 Redis 回滚已处理过，orderId={}", order.getId());
        }
    }

}
