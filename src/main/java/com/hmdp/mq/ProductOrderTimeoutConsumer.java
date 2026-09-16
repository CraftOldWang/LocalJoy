package com.hmdp.mq;

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
        topic = RocketMqConstants.PRODUCT_ORDER_TIMEOUT_TOPIC,
        consumerGroup = RocketMqConstants.PRODUCT_ORDER_TIMEOUT_CONSUMER_GROUP
)
public class ProductOrderTimeoutConsumer implements RocketMQListener<Long>, RocketMQPushConsumerLifecycleListener {

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @Resource
    private IProductOrderService productOrderService;

    @Override
    public void onMessage(Long orderId) {
        log.info("收到商品订单超时关单消息，orderId={}", orderId);
        try {
            productOrderService.cancelOrder(orderId);
        } catch (Exception e) {
            log.error("商品订单超时关单处理失败，orderId={}", orderId, e);
            throw e;
        }
    }
}
