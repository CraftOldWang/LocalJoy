package com.hmdp.mq;

public class RocketMqConstants {

    public static final String SECKILL_ORDER_TOPIC = "seckill-order-topic";
    public static final String SECKILL_ORDER_CONSUMER_GROUP = "seckill-order-consumer-group";

    public static final String PRODUCT_SECKILL_ORDER_TOPIC = "product-seckill-order-topic";
    public static final String PRODUCT_SECKILL_ORDER_CONSUMER_GROUP = "product-seckill-order-consumer-group";

    /** 商品订单超时关单延时消息 Topic */
    public static final String PRODUCT_ORDER_TIMEOUT_TOPIC = "product-order-timeout-topic";
    /** 商品订单超时关单消费者组 */
    public static final String PRODUCT_ORDER_TIMEOUT_CONSUMER_GROUP = "product-order-timeout-consumer-group";

    /** 超时关单延时消息 Topic */
    public static final String ORDER_TIMEOUT_TOPIC = "order-timeout-topic";
    /** 超时关单消费者组 */
    public static final String ORDER_TIMEOUT_CONSUMER_GROUP = "order-timeout-consumer-group";

    /** Canal 投递 MySQL binlog 变更的 Topic */
    public static final String CANAL_CACHE_TOPIC = "hmdp-canal";
    /** Canal 缓存同步消费者组 */
    public static final String CANAL_CACHE_CONSUMER_GROUP = "hmdp-canal-cache-consumer-group";
    /**
     * RocketMQ 4.x 延时级别：
     * 1=1s, 2=5s, 3=10s, 4=30s, 5=1m, 6=2m, 7=3m, 8=4m, 9=5m, 10=6m,
     * 11=7m, 12=8m, 13=9m, 14=10m, 15=20m, 16=30m, 17=1h, 18=2h
     *
     * 使用 level 16 = 30分钟 作为订单超时时间
     */
    public static final int ORDER_TIMEOUT_DELAY_LEVEL = 16;

    private RocketMqConstants() {
    }
}
