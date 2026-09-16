package com.hmdp.payment;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

@Data @Accessors(chain = true)
@TableName("tb_payment_attempt")
public class PaymentAttempt {
    @TableId(type = IdType.INPUT) private Long orderId;
    private String outTradeNo;
    private String appId;
    private String sellerId;
    private Long amount;
    private String state;
    private String tradeNo;
    private String qrCode;
    private String lastError;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
