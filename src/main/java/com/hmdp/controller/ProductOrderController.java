package com.hmdp.controller;

import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitScope;
import com.hmdp.annotation.RateLimits;
import com.hmdp.dto.Result;
import com.hmdp.service.IProductOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import javax.annotation.Resource;

@RestController
@RequestMapping("/product-order")
public class ProductOrderController {

    @Resource
    private IProductOrderService productOrderService;

    @RateLimits({
            @RateLimit(name = "product-seckill", key = "#productId", scopes = {RateLimitScope.GLOBAL}, limit = 1000),
            @RateLimit(name = "product-seckill", key = "#productId", scopes = {RateLimitScope.IP}, limit = 50),
            @RateLimit(name = "product-seckill", key = "#productId", scopes = {RateLimitScope.USER}, limit = 5)
    })
    @PostMapping("seckill/{id}")
    public Result seckillProduct(@PathVariable("id") Long productId) {
        return productOrderService.seckillProduct(productId);
    }

    @PostMapping("pay/{id}")
    public Result payOrder(@PathVariable("id") Long orderId) {
        return productOrderService.payOrder(orderId);
    }

    @GetMapping("/{id}")
    public Result queryMyOrder(@PathVariable("id") Long id) {
        return productOrderService.queryMyOrder(id);
    }

    @GetMapping("/list")
    public Result queryMyOrders(@RequestParam(defaultValue = "1") Integer current) {
        return productOrderService.queryMyOrders(current);
    }
}
