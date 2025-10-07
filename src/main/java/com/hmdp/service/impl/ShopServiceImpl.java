package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.cglib.core.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ShopMapper shopMapper;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate, ShopMapper shopMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.shopMapper = shopMapper;
    }

//    private boolean tryLock(String key) {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unlock(String key) {
//        stringRedisTemplate.delete(key);
//    }

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透， 用工具类
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑国企缓存击穿， 工具类
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);

        // 互斥锁缓存击穿，工具类
        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES, CACHE_NULL_TTL, TimeUnit.MINUTES);

        // 缓存穿透
//        Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("商家不存在");
        }
        return Result.ok(shop);
    }

//    public Shop queryWithMutex(Long id) {
//
//        // 1尝试从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//
//
//        // 2判断是否命中
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 命中， 返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//
//        // 2.5... 前面过滤掉了null，我们现在只需要看是否是 "" ，是的话说明数据库没有，直接返回。
//        if ("".equals(shopJson)) {
//            return null;
//        }
//
//
//        // 进行缓存重建
//        String lockKey = LOCK_SHOP_KEY + id;
//        try {
//            while (true) {
//
//                // 获取互斥锁
//                boolean isLock = tryLock(lockKey);
//                if (!isLock) { // 没抢到锁 ， 休眠， 并重试
//                    Thread.sleep(50);
//                    shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//                    // 2判断是否命中
//                    if (StrUtil.isNotBlank(shopJson)) {
//                        // 命中， 返回
//                        return JSONUtil.toBean(shopJson, Shop.class);
//                    }
//
//                    // 2.5... 前面过滤掉了null，我们现在只需要看是否是 "" ，是的话说明数据库没有，直接返回。
//                    if ("".equals(shopJson)) {
//                        return null;
//                    }
//
//                } else { // 抢到锁了
//
//                    // Double-check , 因为 有可能已经重建完毕了
//                    shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//                    if (StrUtil.isNotBlank(shopJson)) {
//                        return JSONUtil.toBean(shopJson, Shop.class);
//                    }
//                    if ("".equals(shopJson)) {
//                        return null;
//                    }
//                    // end - Double-check
//
//
//                    Shop shop = getById(id);
//                    Thread.sleep(1400);
//                    long randomOffset = ThreadLocalRandom.current().nextLong(1, 11); // 1~10 分钟
//                    // 4不存在， 缓存存储空对象， 返回错误
//                    if (shop == null) {
//                        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL + randomOffset, TimeUnit.MINUTES);
//                        return null;
//                    }
//                    // 5存在， 写入redis
//                    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + randomOffset, TimeUnit.MINUTES);
//                    // 6返回商户
//                    return shop;
//
//
//                }
//
//            }
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            unlock(lockKey);
//        }
//
//
//    }


//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

//    // 逻辑过期解决缓存击穿
//    public Shop queryWithLogicalExpire(Long id) {
//
//        // 1尝试从Redis查询商铺缓存
//        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//
//
//        // 2判断是否命中
//        // 不存在为什么直接返回？ 默认热点key都放到redis里了...
//        if (StrUtil.isBlank(json)) {
//            // 未命中，说明没有，直接返回空
//            return null;
//        }
//
//        // 3命中了，需要判断是否逻辑过期
//        // 3.1首先把json反序列化成对象， 然后拿出过期时间
//        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class); //包了两层，都是json...
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        // 3.2 与当下比较， 看是否过期。
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            //未过期，不用做什么，返回
//            return shop;
//        }
//        // 4. 如果已经过期,需要进行重建
//
//        // 4.1 首先拿到锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        if (isLock) {
//            //  4.1.1进行double确认，看缓存里是否更新了
//            String json1 = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//            RedisData redisData1 = JSONUtil.toBean(json1, RedisData.class);
//            LocalDateTime expireTime1 = redisData1.getExpireTime();
//            if (expireTime1.isAfter(LocalDateTime.now())) {
//                unlock(lockKey);
//                return JSONUtil.toBean((JSONObject) redisData1.getData(), Shop.class);
//            }
//
//
//            // 4.2 把锁给新线程， 然后新线程里面写任务（这里是异步了...）
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    // 4.3 查询数据库，拿到数据，计算过期时间
//                    // 4.4  包装成redisData ,  set到原来的键
//                    this.saveShop2Redis(id, 20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    // 4.5 不管怎样， unlock
//                    unlock(lockKey);
//                }
//            });
//
//
//        }
//
//
//        // 5. 不过已过期未过期， 返回之前拿到的
//        return shop;
//
//    }
//
//
//    public Shop queryWithPassThrough(Long id) {
//        // 1尝试从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//
//
//        // 2判断是否命中
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 命中， 返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//
//        // 2.5... 前面过滤掉了null，我们现在只需要看是否是 "" ，是的话说明数据库没有，直接返回。
//        if ("".equals(shopJson)) {
//            return null;
//        }
//
//        // 3未命中，根据id查询数据库
//        Shop shop = getById(id);
//        long randomOffset = ThreadLocalRandom.current().nextLong(1, 11); // 1~10 分钟
//        // 4不存在， 返回错误
//        if (shop == null) {
//            // 返回之前先存储空对象
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL + randomOffset, TimeUnit.MINUTES);
//            return null;
//        }
//        // 5存在， 写入redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + randomOffset, TimeUnit.MINUTES);
//        // 6返回商户
//        return shop;
//
//    }


    // 如果失败了，只有数据库会回滚....
    @Transactional
    @Override
    public Result update(Shop shop) {
        // 校验id
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("查询商铺id不能为空");
        }
        // 修改数据库
        shopMapper.updateById(shop);

        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

//    @Override
//    public void saveShop2Redis(Long id, Long expireSeconds) {
//        // 1. 查询店铺数据
//        Shop shop = getById(id);
//
//        // 2.封装到对象
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        // 3. 存储到redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
//    }

}
