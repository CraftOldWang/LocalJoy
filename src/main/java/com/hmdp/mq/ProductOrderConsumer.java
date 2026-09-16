package com.hmdp.mq;

import com.hmdp.entity.ProductOrder;
import com.hmdp.service.IProductOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMqConstants.PRODUCT_SECKILL_ORDER_TOPIC,
        consumerGroup = RocketMqConstants.PRODUCT_SECKILL_ORDER_CONSUMER_GROUP
)
public class ProductOrderConsumer implements RocketMQListener<ProductOrder>, RocketMQPushConsumerLifecycleListener {

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        // A fresh group must not skip orders sent before its first queue assignment.
        // Existing persisted offsets remain authoritative on ordinary restarts.
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @Resource
    private IProductOrderService productOrderService;

    @Override
    public void onMessage(ProductOrder productOrder) {
        try {
            productOrderService.createProductOrder(productOrder);
        } catch (Exception e) {
            log.error("Failed to consume product seckill order message: {}", productOrder, e);
            throw e;
        }
    }
}
