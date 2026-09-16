package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Product;

public interface IProductService extends IService<Product> {

    Result queryProductOfShop(Long shopId);

    void addSeckillProduct(Product product);
    Result queryProductById(Long id);
}
