package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ILock;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2. 判断是否存在 （它并没有写）


        // 3. 判断是否开始秒杀
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始!");
        }

        // 4. 判断是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }

        // 5. 判断是否库存充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }


        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 获取锁
        boolean isLock = lock.tryLock(100);

        if (!isLock) {
            return Result.fail("一个人只能下一单！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

//        // 1. 不想要锁整个方法， 而只想根据 userId 来锁。
//        // 2. 要了解spring事务实现方法，直接用其实会失效。(需要添加依赖、在启动类开启暴露代理对象、最后在这里获取并调用方法)
//        synchronized (userId.toString().intern()) { // 用intern是未来确保每次拿到的是同一个字符串对象
//            // 由于动态代理，调用 实现类方法，事务不会生效(事务是通过代理类实现的....因此要用代理类来调用)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
    }

    @Transactional
    @Override
    public Result createVoucherOrder(Long voucherId) {
        // 6.一人一单(检验，每个用户只能下一个单)
        Long userId = UserHolder.getUser().getId();


        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("已经下过单了");
        }

        // 7. 扣库存

//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock - 1")
//                .eq("voucher_id", voucherId)
//                .eq("stock", voucher.getStock()) // stock 与之前一致才能修改
//                .update();
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)  // 这里有保证原子性
                .update();

        if (!success) {
            return Result.fail("库存不足！");
        }


        // 8. 创建订单 并返回订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        //
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
