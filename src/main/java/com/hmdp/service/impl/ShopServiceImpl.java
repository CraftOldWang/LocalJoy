package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.cglib.core.Local;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1. 判断是否需要根据距离查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2. 计算分页查询参数
        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int end = current * SystemConstants.MAX_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        //3. 查询redis、按照距离排序、分页; 结果：shopId、distance
        //GEOSEARCH key FROMLONLAT x y BYRADIUS 5000 m WITHDIST
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null || results.getContent().isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //4. 解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() < from) {
            //起始查询位置大于数据总量，则说明没数据了，返回空集合
            return Result.ok(Collections.emptyList());
        }
        ArrayList<Long> ids = new ArrayList<>(list.size());
        HashMap<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //5. 根据id查询shop
        String idsStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD( id," + idsStr + ")").list();
        for (Shop shop : shops) {
            //设置shop的举例属性，从distanceMap中根据shopId查询
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6. 返回
        return Result.ok(shops);

    }


}
