package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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

    @Override
    public Result queryById(Long id) {

        // 1尝试从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);


        // 2判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中， 返回
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }

        // 3未命中，根据id查询数据库
        Shop shop = getById(id);

        // 4不存在， 返回错误
        if (shop == null) {
            return Result.fail("商家不存在");
        }

        // 5存在， 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6返回商户
        return Result.ok(shop);

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
