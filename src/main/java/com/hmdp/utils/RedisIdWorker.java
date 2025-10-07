package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {


    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;


    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 1.2 获取当前日期(因为redis里是按照每天来分开存自增的键的)
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));


        // 2.从redis 获取自增信息
        // 如果不存在， 会新建一个key ， 然后返回1..所以不会返回null
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);


        // 3. 拼接 并返回
        return timestamp << COUNT_BITS | count;
    }

}
