package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.SeckillProduct;
import com.hmdp.mapper.SeckillProductMapper;
import com.hmdp.service.ISeckillProductService;
import org.springframework.stereotype.Service;

@Service
public class SeckillProductServiceImpl extends ServiceImpl<SeckillProductMapper, SeckillProduct>
        implements ISeckillProductService {
}
