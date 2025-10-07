package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;


@Slf4j
@Component
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 包装一层
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    public <ID, R> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1尝试从Redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);


        // 2判断是否命中
        if (StrUtil.isNotBlank(json)) {
            // 命中， 返回
            return JSONUtil.toBean(json, type);
        }

        // 2.5... 前面过滤掉了null，我们现在只需要看是否是 "" ，是的话说明数据库没有，直接返回。
        if ("".equals(json)) {
            return null;
        }

        // 3未命中，根据id查询数据库
        R r = dbFallback.apply(id);
        long randomOffset = ThreadLocalRandom.current().nextLong(1, 11); // 1~10 分钟
        // 4不存在， 返回错误
        if (r == null) {
            // 返回之前先存储空对象
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL + randomOffset, TimeUnit.MINUTES);
            return null;
        }
        // 5存在， 写入redis
        this.set(key, r, time, unit);
        // 6返回商户
        return r;

    }


    // 逻辑过期解决缓存击穿
    public <ID, R> R queryWithLogicalExpire(String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        // 1尝试从Redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);


        // 2判断是否命中
        // 不存在为什么直接返回？ 默认热点key都放到redis里了...
        if (StrUtil.isBlank(json)) {
            // 未命中，说明没有，直接返回空
            return null;
        }

        // 3命中了，需要判断是否逻辑过期
        // 3.1首先把json反序列化成对象， 然后拿出过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type); //包了两层，都是json...
        LocalDateTime expireTime = redisData.getExpireTime();

        // 3.2 与当下比较， 看是否过期。
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，不用做什么，返回
            return r;
        }
        // 4. 如果已经过期,需要进行重建

        // 4.1 首先拿到锁
        String lockKey = lockPrefix + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //  4.1.1进行double确认，看缓存里是否更新了
            String json1 = stringRedisTemplate.opsForValue().get(key);
            RedisData redisData1 = JSONUtil.toBean(json1, RedisData.class);
            LocalDateTime expireTime1 = redisData1.getExpireTime();
            if (expireTime1.isAfter(LocalDateTime.now())) {
                unlock(lockKey);
                return JSONUtil.toBean((JSONObject) redisData1.getData(), type);
            }


            // 4.2 把锁给新线程， 然后新线程里面写任务（这里是异步了...）
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 4.3 查询数据库，拿到数据，计算过期时间
                    R r1 = dbFallback.apply(id);
                    // 4.4  包装成redisData ,  set到原来的键
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 4.5 不管怎样， unlock
                    unlock(lockKey);
                }
            });


        }


        // 5. 不过已过期未过期， 返回之前拿到的
        return r;

    }

    public <ID, R> R queryWithMutex
            (String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit, Long nullTime, TimeUnit nullUnit) {

        // 1尝试从Redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);


        // 2判断是否命中
        if (StrUtil.isNotBlank(json)) {
            // 命中， 返回
            return JSONUtil.toBean(json, type);
        }

        // 2.5... 前面过滤掉了null，我们现在只需要看是否是 "" ，是的话说明数据库没有，直接返回。
        if ("".equals(json)) {
            return null;
        }


        // 进行缓存重建
        String lockKey = lockPrefix + id;
        try {
            while (true) {

                // 获取互斥锁
                boolean isLock = tryLock(lockKey);
                if (!isLock) { // 没抢到锁 ， 休眠， 并重试
                    Thread.sleep(50);
                    json = stringRedisTemplate.opsForValue().get(key);
                    // 2判断是否命中
                    if (StrUtil.isNotBlank(json)) {
                        // 命中， 返回
                        return JSONUtil.toBean(json, type);
                    }

                    // 2.5... 前面过滤掉了null，我们现在只需要看是否是 "" ，是的话说明数据库没有，直接返回。
                    if ("".equals(json)) {
                        return null;
                    }

                } else { // 抢到锁了

                    // Double-check , 因为 有可能已经重建完毕了
                    json = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(json)) {
                        return JSONUtil.toBean(json, type);
                    }
                    if ("".equals(json)) {
                        return null;
                    }
                    // end - Double-check


                    R r = dbFallback.apply(id);
                    Thread.sleep(1400);
                    long randomOffset = ThreadLocalRandom.current().nextLong(1, 11); // 1~10 分钟
                    // 4不存在， 缓存存储空对象， 返回错误
                    if (r == null) {
                        this.set(key, "", nullTime + randomOffset, nullUnit);
                        return null;
                    }
                    // 5存在， 写入redis
                    this.set(key, r, time + randomOffset, unit);
                    // 6返回商户
                    return r;


                }

            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }


    }


}
