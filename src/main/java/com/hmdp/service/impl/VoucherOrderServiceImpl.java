package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Autowired
    private ISeckillVoucherService seckillVoucherService;


    private final TransactionTemplate transactionTemplate;


    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 异步线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

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
                    userId.toString(),
                    orderId.toString()
            );
        } catch (Exception e) {
            return Result.fail("下订单失败");
        }
        // 是否能下单
        if (result == 1 || result == 2) {
            return Result.fail(result == 1 ? "已被抢完" : "你已下过单");
        }
        // 返回orderId
        return Result.ok(orderId);

    }


    // 需要建立一个异步处理的 消费者

    // 类初始化之后马上开始消费 ？ 有必要吗
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    String queueName = "stream.orders";

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //1. 获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //ReadOffset.lastConsumed()底层就是 '>'
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //2. 判断消息是否获取成功
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    //3. 消息获取成功之后，我们需要将其转为对象
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4. 获取成功，执行下单逻辑，将数据保存到数据库中
                    writeToDB(voucherOrder);
                    //5. 手动ACK，SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    //订单异常的处理方式我们封装成一个函数，避免代码太臃肿
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                //1. 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0")));
                //2. 判断pending-list中是否有未处理消息
                if (records == null || records.isEmpty()) {
                    //如果没有就说明没有异常消息，直接结束循环
                    break;
                }
                //3. 消息获取成功之后，我们需要将其转为对象
                MapRecord<String, Object, Object> record = records.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //4. 获取成功，执行下单逻辑，将数据保存到数据库中
                writeToDB(voucherOrder);
                //5. 手动ACK，SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                log.info("处理pending-list异常");
                //如果怕异常多次出现，可以在这里休眠一会儿
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    private void writeToDB(VoucherOrder voucherOrder) {
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
        }

        try {
            transactionTemplate.execute(status -> {
                // 检查一人一单
                int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
                if (count > 0) {
                    log.error("一个人只能下一单");
                    return null;
                }
                boolean success = seckillVoucherService.update()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", voucherId)
                        .gt("stock", 0)
                        .update();
                if (!success) {
                    log.error("库存不足");
                }
                save(voucherOrder);
                return null;
            });
        } finally {
            redisLock.unlock();
        }


    }

}
