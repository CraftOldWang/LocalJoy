package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMqConstants.SECKILL_ORDER_TOPIC,
        consumerGroup = RocketMqConstants.SECKILL_ORDER_CONSUMER_GROUP
)
public class VoucherOrderConsumer implements RocketMQListener<VoucherOrder> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(VoucherOrder voucherOrder) {
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("Failed to consume seckill order message: {}", voucherOrder, e);
            throw e;
        }
    }
}
