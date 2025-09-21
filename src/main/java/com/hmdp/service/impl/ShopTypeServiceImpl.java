package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryAll() {

        // 1. 查询缓存
        String typeListJson = stringRedisTemplate.opsForValue().get("cache:typelist");


        // 2.若查到，返回
        if (StrUtil.isNotBlank(typeListJson)) {
            // 直接返回，因为缓存里的是已经排好序的了
            return Result.ok(JSONUtil.toList(typeListJson, ShopType.class));
        }

        // 3. 若没查到， 查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 4. 如果不存在， 返回错误
        if (typeList.isEmpty()) {
            return Result.fail("有问题，商户类型是固定的，不应返回空");
        }

        // 5. 如果存在，记录到redis
        stringRedisTemplate.opsForValue().set("cache:typelist", JSONUtil.toJsonStr(typeList));

        // 6. 返回

        return Result.ok(typeList);

    }
}
