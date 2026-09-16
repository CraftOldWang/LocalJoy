package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_product_order")
public class ProductOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long userId;

    private Long productId;

    private Integer payType;

    private Long totalAmount;

    private String subject;

    private Integer status;

    private Integer version;

    private String closeReason;

    private LocalDateTime createTime;

    private LocalDateTime expireTime;

    private LocalDateTime payTime;

    private LocalDateTime refundTime;

    private LocalDateTime updateTime;
}
