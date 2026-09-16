package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Product;
import com.hmdp.service.IProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/product")
public class ProductController {

    @Resource
    private IProductService productService;

    @PostMapping
    public Result addProduct(@RequestBody Product product) {
        productService.save(product);
        return Result.ok(product.getId());
    }

    @PostMapping("seckill")
    public Result addSeckillProduct(@RequestBody Product product) {
        productService.addSeckillProduct(product);
        return Result.ok(product.getId());
    }

    @GetMapping("/list/{shopId}")
    public Result queryProductOfShop(@PathVariable("shopId") Long shopId) {
        return productService.queryProductOfShop(shopId);
    }

    @GetMapping("/{id}")
    public Result queryProductById(@PathVariable("id") Long id) {
        return productService.queryProductById(id);
    }
}
