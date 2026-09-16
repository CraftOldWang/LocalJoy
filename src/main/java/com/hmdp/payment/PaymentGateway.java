package com.hmdp.payment;

import com.hmdp.entity.ProductOrder;
import lombok.Value;
import java.util.Map;

public interface PaymentGateway {
    boolean ready();
    String create(PaymentAttempt attempt, ProductOrder order);
    Trade query(String outTradeNo);
    boolean close(String outTradeNo);
    boolean verify(Map<String, String> parameters);

    @Value
    class Trade {
        String status;
        String outTradeNo;
        String tradeNo;
        Long amount;
        public boolean paid() { return "TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status); }
    }
}
