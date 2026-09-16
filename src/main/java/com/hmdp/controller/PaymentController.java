package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.payment.PaymentService;
import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitScope;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/payment")
public class PaymentController {
    private final PaymentService payments;
    public PaymentController(PaymentService payments) { this.payments = payments; }
    @GetMapping("/options") public Result options() { return Result.ok(payments.options()); }
    @GetMapping("/alipay/{id}") public Result view(@PathVariable Long id) { return payments.view(id); }
    @PostMapping("/alipay/{id}")
    @RateLimit(name="payment-create", scopes={RateLimitScope.USER}, limit=3)
    public Result start(@PathVariable Long id) { return handle(() -> payments.start(id)); }
    @PostMapping("/alipay/{id}/sync")
    @RateLimit(name="payment-query", scopes={RateLimitScope.USER}, limit=3)
    public Result sync(@PathVariable Long id) { return handle(() -> payments.syncOwned(id)); }
    @PostMapping(value="/alipay/notify", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces=MediaType.TEXT_PLAIN_VALUE)
    public String notify(HttpServletRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        // Reject duplicated parameters instead of signing one representation and processing another.
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (entry.getValue().length != 1) return "failure";
            values.put(entry.getKey(), entry.getValue()[0]);
        }
        try { return payments.notify(values) ? "success" : "failure"; }
        catch (Exception ignored) { return "failure"; }
    }
    private Result handle(Supplier<Result> action) {
        try { return action.get(); }
        catch (IllegalStateException e) { return Result.fail(e.getMessage()); }
        catch (Exception e) { return Result.fail("支付状态暂未确认，请稍后查询订单"); }
    }
}
