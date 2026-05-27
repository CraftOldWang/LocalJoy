package com.hmdp.controller;


import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitScope;
import com.hmdp.annotation.RateLimits;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @RateLimits({
            @RateLimit(name = "seckill", key = "#voucherId", scopes = {RateLimitScope.GLOBAL}, limit = 1000),
            @RateLimit(name = "seckill", key = "#voucherId", scopes = {RateLimitScope.IP}, limit = 50),
            @RateLimit(name = "seckill", key = "#voucherId", scopes = {RateLimitScope.USER}, limit = 5)
    })
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
