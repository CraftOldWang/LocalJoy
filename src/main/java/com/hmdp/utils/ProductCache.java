package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Product;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Product metadata only: seckill admission always uses the inventory Lua script. */
@Component
public class ProductCache {
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;

    public ProductCache(StringRedisTemplate redis, RedissonClient redisson) {
        this.redis = redis;
        this.redisson = redisson;
    }

    public Product get(Long id, Supplier<Product> loader) {
        String key = RedisConstants.CACHE_PRODUCT_KEY + id;
        String json = redis.opsForValue().get(key);
        if (json != null) return decode(json);
        RLock lock = redisson.getLock(RedisConstants.LOCK_PRODUCT_CACHE_KEY + id);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(500, TimeUnit.MILLISECONDS);
            // Bounded waiting. A fallback read must not populate the cache without this lock.
            if (!acquired) return loader.get();
            json = redis.opsForValue().get(key);
            if (json != null) return decode(json);
            Product product = loader.get();
            redis.opsForValue().set(key, product == null ? "" : JSONUtil.toJsonStr(product),
                    product == null ? 30 : 300, TimeUnit.SECONDS);
            return product;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("商品缓存读取被中断", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    public void invalidate(Long id) {
        RLock lock = redisson.getLock(RedisConstants.LOCK_PRODUCT_CACHE_KEY + id);
        boolean acquired = false;
        try {
            // Serialize invalidation with cache fills to prevent stale refill after the event.
            acquired = lock.tryLock(1, TimeUnit.SECONDS);
            if (!acquired) throw new IllegalStateException("商品缓存正在重建，重试失效消息");
            redis.delete(RedisConstants.CACHE_PRODUCT_KEY + id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("商品缓存失效被中断", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private Product decode(String json) {
        return json.isEmpty() ? null : JSONUtil.toBean(json, Product.class);
    }
}
