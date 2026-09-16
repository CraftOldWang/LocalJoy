package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Product;
import com.hmdp.entity.SeckillProduct;
import com.hmdp.mapper.ProductMapper;
import com.hmdp.service.IProductService;
import com.hmdp.service.ISeckillProductService;
import com.hmdp.utils.ProductCache;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.Arrays;

@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements IProductService {
    @Resource private ISeckillProductService seckillProductService;
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private ProductCache productCache;

    @Override public Result queryProductOfShop(Long shopId) {
        return Result.ok(getBaseMapper().queryProductOfShop(shopId));
    }

    @Override public Result queryProductById(Long id) {
        Product product = productCache.get(id, () -> getById(id));
        return product == null || !Integer.valueOf(1).equals(product.getStatus())
                ? Result.fail("商品不存在或已下架") : Result.ok(product);
    }

    @Override
    @Transactional
    public void addSeckillProduct(Product product) {
        if (product.getStock() == null || product.getStock() < 0 || product.getPrice() == null
                || product.getPrice() < 0 || product.getBeginTime() == null || product.getEndTime() == null
                || !product.getEndTime().isAfter(product.getBeginTime())) {
            throw new IllegalArgumentException("秒杀商品库存、价格或活动时间不合法");
        }
        if (product.getStatus() == null) product.setStatus(1);
        if (!save(product)) throw new IllegalStateException("保存商品失败");
        SeckillProduct activity = new SeckillProduct();
        activity.setProductId(product.getId());
        activity.setStock(product.getStock());
        activity.setBeginTime(product.getBeginTime());
        activity.setEndTime(product.getEndTime());
        if (!seckillProductService.save(activity)) throw new IllegalStateException("保存秒杀活动失败");
        // A rolled-back database transaction must never expose purchasable Redis stock.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                DefaultRedisScript<Long> init = new DefaultRedisScript<>(
                        "if redis.call('exists', KEYS[1]) == 1 then return 0 end " +
                        "redis.call('set', KEYS[1], ARGV[1]); " +
                        "redis.call('hset', KEYS[2], 'begin', ARGV[2], 'end', ARGV[3]); return 1", Long.class);
                stringRedisTemplate.execute(init, Arrays.asList(
                        RedisConstants.PRODUCT_SECKILL_STOCK_KEY + product.getId(),
                        RedisConstants.PRODUCT_SECKILL_META_KEY + product.getId()),
                        product.getStock().toString(),
                        String.valueOf(product.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
                        String.valueOf(product.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
            }
        });
    }
}
