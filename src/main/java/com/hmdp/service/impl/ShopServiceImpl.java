package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
//        Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

        if (shop == null) {
            return Result.fail("商家不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {

        // 1尝试从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);


        // 2判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中， 返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 2.5... 前面过滤掉了null，我们现在只需要看是否是 "" ，是的话说明数据库没有，直接返回。
        if ("".equals(shopJson)) {
            return null;
        }


        // 进行缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            while (true) {

                // 获取互斥锁
                boolean isLock = tryLock(lockKey);
                if (!isLock) { // 没抢到锁 ， 休眠， 并重试
                    Thread.sleep(50);
                    shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
                    // 2判断是否命中
                    if (StrUtil.isNotBlank(shopJson)) {
                        // 命中， 返回
                        return JSONUtil.toBean(shopJson, Shop.class);
                    }

                    // 2.5... 前面过滤掉了null，我们现在只需要看是否是 "" ，是的话说明数据库没有，直接返回。
                    if ("".equals(shopJson)) {
                        return null;
                    }

                } else { // 抢到锁了

                    // Double-check , 因为 有可能已经重建完毕了
                    shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
                    if (StrUtil.isNotBlank(shopJson)) {
                        return JSONUtil.toBean(shopJson, Shop.class);
                    }
                    if ("".equals(shopJson)) {
                        return null;
                    }
                    // end - Double-check


                    Shop shop = getById(id);
                    Thread.sleep(1400);
                    long randomOffset = ThreadLocalRandom.current().nextLong(1, 11); // 1~10 分钟
                    // 4不存在， 缓存存储空对象， 返回错误
                    if (shop == null) {
                        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL + randomOffset, TimeUnit.MINUTES);
                        return null;
                    }
                    // 5存在， 写入redis
                    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + randomOffset, TimeUnit.MINUTES);
                    // 6返回商户
                    return shop;


                }

            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }


    }

    public Shop queryWithPassThrough(Long id) {
        // 1尝试从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);


        // 2判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中， 返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 2.5... 前面过滤掉了null，我们现在只需要看是否是 "" ，是的话说明数据库没有，直接返回。
        if ("".equals(shopJson)) {
            return null;
        }

        // 3未命中，根据id查询数据库
        Shop shop = getById(id);
        long randomOffset = ThreadLocalRandom.current().nextLong(1, 11); // 1~10 分钟
        // 4不存在， 返回错误
        if (shop == null) {
            // 返回之前先存储空对象
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL + randomOffset, TimeUnit.MINUTES);
            return null;
        }
        // 5存在， 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + randomOffset, TimeUnit.MINUTES);
        // 6返回商户
        return shop;

    }


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

}
