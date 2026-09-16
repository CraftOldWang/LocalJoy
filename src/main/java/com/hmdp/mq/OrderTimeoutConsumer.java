package com.hmdp.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import com.hmdp.service.IVoucherOrderService;

import javax.annotation.Resource;

/**
 * 订单超时关单消费者
 * 消费延时消息，检查订单是否仍为未支付状态，若是则取消订单并回滚库存
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMqConstants.ORDER_TIMEOUT_TOPIC,
        consumerGroup = RocketMqConstants.ORDER_TIMEOUT_CONSUMER_GROUP
)
public class OrderTimeoutConsumer implements RocketMQListener<Long> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(Long orderId) {
        log.info("收到订单超时关单消息，orderId={}", orderId);
        try {
            voucherOrderService.cancelOrder(orderId);
        } catch (Exception e) {
            log.error("超时关单处理失败，orderId={}", orderId, e);
            throw e;
        }
    }
}
