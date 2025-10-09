package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
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
import java.util.Collections;
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

    // 阻塞队列
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
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
        long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), // 或者我随便构造一个List传进去也行吧hh
                voucherId,
                userId.toString()
        );

        // 检测错误信号
        if (result == 1 || result == 2) {
            return Result.fail(result == 1 ? "已被抢完" : "你已下过单");
        }

        // 可以下单,加入阻塞队列
        // 这样生成？？
        Long orderId = redisIdWorker.nextId("odrer");
        // 为啥阻塞队列不只放一个orderId? 因为userHolder与线程有关....省得再想办法获取了
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);

        // 返回orderId
        return Result.ok(orderId);

    }


    // 需要建立一个异步处理的 消费者

    // 类初始化之后马上开始消费 ？ 有必要吗
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new HandleOrderTasks());
    }

    // 有一个函数， 自启动以来就一直在读orderTasks并处理
    private class HandleOrderTasks implements Runnable {


        @Override
        public void run() {
            while (true) {
                try {

                    VoucherOrder voucherOrder = orderTasks.take();
                    writeToDB(voucherOrder);

                } catch (InterruptedException e) {
                    log.error("订单处理异常", e);
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
