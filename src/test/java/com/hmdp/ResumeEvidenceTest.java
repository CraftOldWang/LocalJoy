package com.hmdp;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitScope;
import com.hmdp.canal.CanalCacheHandler;
import com.hmdp.canal.CanalFlatMessageConsumer;
import com.hmdp.canal.ProductCanalCacheHandler;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Product;
import com.hmdp.entity.ProductOrder;
import com.hmdp.exception.RateLimitException;
import com.hmdp.mq.RocketMqConstants;
import com.hmdp.service.IProductOrderService;
import com.hmdp.service.IProductService;
import com.hmdp.utils.ProductCache;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL + Redis; MQ sends are explicitly mocked for deterministic fault injection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("resume-evidence")
public class ResumeEvidenceTest {
    @Autowired IProductService products;
    @Autowired IProductOrderService orders;
    @Autowired ProductCache cache;
    @Autowired ProductCanalCacheHandler productHandler;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.boot.autoconfigure.data.redis.RedisProperties redisProperties;
    @Autowired LimitedFacade limited;
    @MockBean RocketMQTemplate mq;
    final List<Long> fixtures = new ArrayList<>();
    final List<ProductOrder> sent = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach void prepare() {
        assertEquals("hmdp_resume_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(14, redisProperties.getDatabase(), "evidence tests must not use the live Redis database");
        reset(mq);
        when(mq.syncSend(eq(RocketMqConstants.PRODUCT_SECKILL_ORDER_TOPIC), any(Object.class)))
                .thenAnswer(call -> { sent.add(call.getArgument(1)); return sendOk(); });
        when(mq.syncSend(eq(RocketMqConstants.PRODUCT_ORDER_TIMEOUT_TOPIC), any(Message.class), anyLong(), anyInt()))
                .thenReturn(sendOk());
    }
    @AfterEach void cleanup() {
        UserHolder.removeUser();
        RequestContextHolder.resetRequestAttributes();
        for (Long id : fixtures) {
            jdbc.update("DELETE FROM tb_product_order WHERE product_id=?", id);
            jdbc.update("DELETE FROM tb_seckill_product WHERE product_id=?", id);
            jdbc.update("DELETE FROM tb_product WHERE id=?", id);
            redis.delete(Arrays.asList(stockKey(id), buyerKey(id), RedisConstants.PRODUCT_SECKILL_META_KEY + id,
                    RedisConstants.CACHE_PRODUCT_KEY + id));
        }
    }
    static SendResult sendOk() { SendResult r = new SendResult(); r.setSendStatus(SendStatus.SEND_OK); return r; }
    Product product(int stock) {
        Product p = new Product().setShopId(1L).setTitle("resume-evidence").setPrice(100L).setStatus(1)
                .setStock(stock).setBeginTime(LocalDateTime.now().minusMinutes(1))
                .setEndTime(LocalDateTime.now().plusHours(1));
        products.addSeckillProduct(p);
        fixtures.add(p.getId());
        return p;
    }
    static String stockKey(Long id) { return RedisConstants.PRODUCT_SECKILL_STOCK_KEY + id; }
    static String buyerKey(Long id) { return RedisConstants.PRODUCT_SECKILL_BUYER_KEY + id; }
    int stock(Long id) { return Integer.parseInt(redis.opsForValue().get(stockKey(id))); }
    int dbStock(Long id) { return jdbc.queryForObject("SELECT stock FROM tb_seckill_product WHERE product_id=?", Integer.class, id); }
    static void user(long id) { UserDTO u = new UserDTO(); u.setId(id); UserHolder.saveUser(u); }
    Result buy(Long product, long uid) { user(uid); try { return orders.seckillProduct(product); } finally { UserHolder.removeUser(); } }
    ProductOrder accepted(Product product, long user) {
        Result result = buy(product.getId(), user);
        assertTrue(result.getSuccess(), result.getErrorMsg());
        return sent.stream().filter(o -> o.getId().toString().equals(result.getData())).findFirst().get();
    }
    void consume(ProductOrder order) { orders.createProductOrder(order); }
    void expire(ProductOrder order) {
        jdbc.update("UPDATE tb_product_order SET expire_time=? WHERE id=?", LocalDateTime.now().minusSeconds(5), order.getId());
    }
    void parallel(int tasks, java.util.function.IntConsumer action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) { final int n = i; futures.add(pool.submit(() -> action.accept(n))); }
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }

    @Test void myOrderListIsOwnedPagedAndIdsStayExactInJson() throws Exception {
        Product p = product(1);
        long owner = 990016L;
        long firstId = 9007199254740993L;
        for (int i = 0; i < 13; i++) {
            orders.save(new ProductOrder().setId(firstId + i).setProductId(p.getId())
                    .setUserId(i == 12 ? owner + 1 : owner).setStatus(4).setVersion(0)
                    .setCreateTime(LocalDateTime.now()).setExpireTime(LocalDateTime.now().plusMinutes(30)));
        }
        user(owner);
        Result first = orders.queryMyOrders(1);
        assertEquals(12L, first.getTotal());
        List<ProductOrder> records = (List<ProductOrder>) first.getData();
        assertEquals(10, records.size());
        assertTrue(records.stream().allMatch(o -> o.getUserId() == owner));
        assertEquals(2, ((List<?>) orders.queryMyOrders(2).getData()).size());
        assertFalse(orders.queryMyOrders(0).getSuccess());
        assertFalse(orders.queryMyOrders(10001).getSuccess());
        assertFalse(orders.queryMyOrder(firstId + 12).getSuccess());
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(mapper.writeValueAsString(new ProductOrder().setId(firstId)));
        assertTrue(json.get("id").isTextual());
        assertEquals("9007199254740993", json.get("id").asText());
    }

    @Test void concurrentAdmissionsAndDuplicateConsumptionNeverOversell() throws Exception {
        Product p = product(30);
        AtomicInteger accepted = new AtomicInteger();
        parallel(200, i -> { if (buy(p.getId(), 1000 + i % 100).getSuccess()) accepted.incrementAndGet(); });
        assertEquals(30, accepted.get());
        assertEquals(0, stock(p.getId()));
        assertEquals(30L, redis.opsForHash().size(buyerKey(p.getId())).longValue());
        List<ProductOrder> snapshot = new ArrayList<>(sent);
        parallel(90, i -> consume(snapshot.get(i % 30)));
        assertEquals(0, dbStock(p.getId()));
        assertEquals(30, jdbc.queryForObject("SELECT COUNT(*) FROM tb_product_order WHERE product_id=?", Integer.class, p.getId()).intValue());
    }

    @Test void activityTimeAndMissingMetadataFailClosed() {
        Product p = product(2);
        String key = RedisConstants.PRODUCT_SECKILL_META_KEY + p.getId();
        redis.opsForHash().put(key, "begin", Long.toString(System.currentTimeMillis() + 60000));
        assertFalse(buy(p.getId(), 1).getSuccess());
        redis.opsForHash().put(key, "begin", "0");
        redis.opsForHash().put(key, "end", "1");
        assertFalse(buy(p.getId(), 1).getSuccess());
        redis.delete(key);
        assertFalse(buy(p.getId(), 1).getSuccess());
        assertEquals(2, stock(p.getId()));
        assertTrue(sent.isEmpty());
    }

    @Test void ambiguousSendAndLateMessageCannotConsumeReplacedReservation() {
        Product p = product(1);
        when(mq.syncSend(eq(RocketMqConstants.PRODUCT_SECKILL_ORDER_TOPIC), any(Object.class)))
                .thenAnswer(call -> { sent.add(call.getArgument(1)); throw new IllegalStateException("simulated acknowledgement loss"); })
                .thenAnswer(call -> { sent.add(call.getArgument(1)); return sendOk(); });
        assertFalse(buy(p.getId(), 1).getSuccess());
        ProductOrder lostAck = sent.get(0);
        assertEquals(1, stock(p.getId()));
        ProductOrder retry = accepted(p, 1);
        consume(lostAck);
        consume(retry);
        consume(lostAck);
        assertNull(orders.getById(lostAck.getId()));
        assertNotNull(orders.getById(retry.getId()));
        assertEquals(0, dbStock(p.getId()));
        assertEquals(0, stock(p.getId()));
    }

    @Test void databaseRejectionCompensatesOnlyOnce() {
        Product p = product(1);
        jdbc.update("UPDATE tb_seckill_product SET stock=0 WHERE product_id=?", p.getId());
        ProductOrder order = accepted(p, 1);
        consume(order); consume(order); consume(order);
        assertEquals(4, orders.getById(order.getId()).getStatus().intValue());
        assertEquals(1, stock(p.getId()));
        assertEquals(0, dbStock(p.getId()));
        assertEquals(0L, redis.opsForHash().size(buyerKey(p.getId())).longValue());
    }

    @Test void delaySendFailureRetriesAfterCommitWithoutSecondStockDeduction() {
        Product p = product(2);
        ProductOrder order = accepted(p, 1);
        when(mq.syncSend(eq(RocketMqConstants.PRODUCT_ORDER_TIMEOUT_TOPIC), any(Message.class), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("delay send unavailable")).thenReturn(sendOk());
        assertThrows(IllegalStateException.class, () -> consume(order));
        assertNotNull(orders.getById(order.getId()));
        consume(order);
        assertEquals(1, dbStock(p.getId()));
        assertEquals(1, stock(p.getId()));
    }

    @Test void repeatedCloseAndLateReplayDoNotUndoRepurchase() throws Exception {
        Product p = product(1);
        ProductOrder first = accepted(p, 1); consume(first); expire(first);
        parallel(12, i -> orders.cancelOrder(first.getId()));
        assertEquals(1, stock(p.getId()));
        assertEquals(1, dbStock(p.getId()));
        assertEquals(1, orders.getById(first.getId()).getVersion().intValue());
        ProductOrder second = accepted(p, 1); consume(second);
        orders.cancelOrder(first.getId()); consume(first);
        assertEquals(0, stock(p.getId()));
        assertEquals(0, dbStock(p.getId()));
        assertEquals(second.getId().toString(), redis.opsForHash().get(buyerKey(p.getId()), "1"));
    }

    @Test void paymentIsOwnedIdempotentAndCannotRacePastDeadline() throws Exception {
        Product p = product(2);
        ProductOrder first = accepted(p, 1); consume(first);
        user(2); assertFalse(orders.payOrder(first.getId()).getSuccess());
        parallel(20, i -> {
            user(1);
            try { if (i % 2 == 0) assertTrue(orders.payOrder(first.getId()).getSuccess()); else orders.cancelOrder(first.getId()); }
            finally { UserHolder.removeUser(); }
        });
        assertEquals(2, orders.getById(first.getId()).getStatus().intValue());
        assertEquals(1, orders.getById(first.getId()).getVersion().intValue());
        ProductOrder second = accepted(p, 2); consume(second); expire(second);
        parallel(20, i -> {
            user(2);
            try { if (i % 2 == 0) assertFalse(orders.payOrder(second.getId()).getSuccess()); else orders.cancelOrder(second.getId()); }
            finally { UserHolder.removeUser(); }
        });
        assertEquals(4, orders.getById(second.getId()).getStatus().intValue());
        assertEquals(1, dbStock(p.getId()));
        assertEquals(1, stock(p.getId()));
    }

    @Test void earlyDelayCannotCloseOrder() {
        Product p = product(1);
        ProductOrder order = accepted(p, 1); consume(order);
        orders.cancelOrder(order.getId());
        assertEquals(1, orders.getById(order.getId()).getStatus().intValue());
        assertEquals(0, dbStock(p.getId()));
        verify(mq, times(2)).syncSend(eq(RocketMqConstants.PRODUCT_ORDER_TIMEOUT_TOPIC), any(Message.class), anyLong(), anyInt());
    }

    @Test void databaseUniqueConstraintRejectsAnotherActiveOrder() {
        Product p = product(2);
        ProductOrder order = accepted(p, 1); consume(order);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO tb_product_order(id,user_id,product_id,status,version) VALUES(?,?,?,1,0)", order.getId()+99, 1, p.getId()));
    }

    @Test void hotProductCacheAndNullCacheAvoidRepeatedDatabaseLoads() throws Exception {
        Product p = product(1);
        AtomicInteger loads = new AtomicInteger();
        Supplier<Product> loader = () -> { loads.incrementAndGet(); return products.getById(p.getId()); };
        parallel(20, i -> assertEquals(p.getId(), cache.get(p.getId(), loader).getId()));
        assertEquals(1, loads.get());
        Long missing = -p.getId();
        try {
            assertNull(cache.get(missing, () -> { loads.incrementAndGet(); return null; }));
            assertNull(cache.get(missing, () -> { fail("null cache should avoid DB"); return null; }));
            assertEquals(2, loads.get());
        } finally { redis.delete(RedisConstants.CACHE_PRODUCT_KEY + missing); }
    }

    @Test void canalInvalidationCoversUpdatesDeletesAndDuplicateEvents() {
        Product p = product(1);
        assertEquals("resume-evidence", ((Product) products.queryProductById(p.getId()).getData()).getTitle());
        jdbc.update("UPDATE tb_product SET title='changed' WHERE id=?", p.getId());
        Map<String,String> row = Collections.singletonMap("id", p.getId().toString());
        productHandler.handleChange(CanalEntry.EventType.UPDATE, row);
        productHandler.handleChange(CanalEntry.EventType.UPDATE, row);
        assertEquals("changed", ((Product) products.queryProductById(p.getId()).getData()).getTitle());
        jdbc.update("DELETE FROM tb_product WHERE id=?", p.getId());
        productHandler.handleChange(CanalEntry.EventType.DELETE, row);
        assertFalse(products.queryProductById(p.getId()).getSuccess());
    }

    @Test void invalidationWaitsForInFlightFillThenRemovesStaleValue() throws Exception {
        Product p = product(1);
        CountDownLatch read = new CountDownLatch(1), resume = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fill = pool.submit(() -> cache.get(p.getId(), () -> {
                Product old = products.getById(p.getId()); read.countDown();
                try { assertTrue(resume.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return old;
            }));
            assertTrue(read.await(3, TimeUnit.SECONDS));
            jdbc.update("UPDATE tb_product SET title='new-version' WHERE id=?", p.getId());
            Future<?> invalidate = pool.submit(() -> cache.invalidate(p.getId()));
            resume.countDown(); fill.get(5, TimeUnit.SECONDS); invalidate.get(5, TimeUnit.SECONDS);
            assertEquals("new-version", ((Product) products.queryProductById(p.getId()).getData()).getTitle());
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test void canalFailuresPropagateForMqRetryAndForeignSchemasAreIgnored() throws Exception {
        CanalCacheHandler handler = mock(CanalCacheHandler.class);
        when(handler.getTableName()).thenReturn("tb_product");
        doThrow(new IllegalStateException("Redis unavailable")).doNothing()
                .when(handler).handleChange(any(), anyMap(), anyMap());
        CanalFlatMessageConsumer consumer = new CanalFlatMessageConsumer();
        ReflectionTestUtils.setField(consumer, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(consumer, "database", "hmdp_resume_test");
        ReflectionTestUtils.setField(consumer, "cacheHandlers", Collections.singletonList(handler)); consumer.init();
        String payload = "{\"database\":\"hmdp_resume_test\",\"table\":\"tb_product\",\"type\":\"UPDATE\",\"data\":[{\"id\":\"1\"}]}";
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));
        consumer.onMessage(payload);
        consumer.onMessage(payload.replace("hmdp_resume_test", "unrelated_database"));
        verify(handler, times(2)).handleChange(any(), anyMap(), anyMap());
    }

    @Test void slidingWindowScopesAreIndependentAndCannotSpoofIp() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        request("10.0.0.1", "1.1.1.1"); user(1);
        limited.byUser(suffix); assertThrows(RateLimitException.class, () -> limited.byUser(suffix));
        user(2); limited.byUser(suffix);
        limited.byIp(suffix);
        request("10.0.0.1", "2.2.2.2");
        assertThrows(RateLimitException.class, () -> limited.byIp(suffix));
        request("10.0.0.2", "2.2.2.2"); limited.byIp(suffix);
        limited.global(suffix);
        request("10.0.0.3", "3.3.3.3"); user(3);
        assertThrows(RateLimitException.class, () -> limited.global(suffix));
        Thread.sleep(1050);
        limited.global(suffix);
    }
    void request(String ip, String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip); request.addHeader("X-Forwarded-For", forwarded);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="resume.realMq", matches="true")
    void freshConsumerGroupsReceiveMessagesPublishedBeforeStartup() throws Exception {
        String run = Long.toString(System.currentTimeMillis());
        String topic = "resume_first_message_" + run;
        org.apache.rocketmq.client.producer.DefaultMQProducer producer =
                new org.apache.rocketmq.client.producer.DefaultMQProducer("resume_first_producer_" + run);
        org.apache.rocketmq.client.consumer.DefaultMQPushConsumer orderConsumer =
                new org.apache.rocketmq.client.consumer.DefaultMQPushConsumer("resume_first_order_" + run);
        org.apache.rocketmq.client.consumer.DefaultMQPushConsumer timeoutConsumer =
                new org.apache.rocketmq.client.consumer.DefaultMQPushConsumer("resume_first_timeout_" + run);
        CountDownLatch deliveries = new CountDownLatch(2);
        try {
            producer.setNamesrvAddr("127.0.0.1:9876"); producer.start();
            producer.createTopic("TBW102", topic, 1, 0, Collections.emptyMap());
            producer.send(new org.apache.rocketmq.common.message.Message(topic,
                    "published-before-start".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            new com.hmdp.mq.ProductOrderConsumer().prepareStart(orderConsumer);
            new com.hmdp.mq.ProductOrderTimeoutConsumer().prepareStart(timeoutConsumer);
            for (org.apache.rocketmq.client.consumer.DefaultMQPushConsumer consumer : Arrays.asList(orderConsumer, timeoutConsumer)) {
                consumer.setNamesrvAddr("127.0.0.1:9876"); consumer.subscribe(topic, "*");
                java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
                consumer.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently) (messages, context) -> {
                    if (messages.stream().anyMatch(message -> "published-before-start".equals(
                            new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8))) && first.compareAndSet(true, false)) {
                        deliveries.countDown();
                    }
                    return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                });
                consumer.start();
            }
            assertTrue(deliveries.await(25, TimeUnit.SECONDS), "both fresh groups must receive the pre-existing message");
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("topic", topic); evidence.put("messagePublishedBeforeConsumerStart", true);
            evidence.put("freshGroupsVerified", 2); evidence.put("passed", true);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                    new java.io.File("target/resume-first-message-evidence.json"), evidence);
        } finally { orderConsumer.shutdown(); timeoutConsumer.shutdown(); producer.shutdown(); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="resume.realMq", matches="true")
    void realBrokerRetriesDeliveryAndDelaysCloseThenMeasuresAcceptedOrders() throws Exception {
        String run = Long.toString(System.currentTimeMillis());
        String orderTopic = "resume_evidence_" + run;
        String delayTopic = orderTopic + "_timeout";
        org.apache.rocketmq.client.producer.DefaultMQProducer producer =
                new org.apache.rocketmq.client.producer.DefaultMQProducer("resume_producer_" + run);
        org.apache.rocketmq.client.consumer.DefaultMQPushConsumer consumer =
                new org.apache.rocketmq.client.consumer.DefaultMQPushConsumer("resume_consumer_" + run);
        producer.setNamesrvAddr("127.0.0.1:9876");
        consumer.setNamesrvAddr("127.0.0.1:9876");
        consumer.setConsumeFromWhere(org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(4); consumer.setConsumeThreadMax(8); consumer.setConsumeMessageBatchMaxSize(1);
        consumer.subscribe(orderTopic, "*"); consumer.subscribe(delayTopic, "*");
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger retryDeliveries = new AtomicInteger();
        AtomicInteger delayDeliveries = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Exception> lastFailure = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        ObjectMapper mapper = new ObjectMapper();
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(orders);
        ReflectionTestUtils.setField(target, "timeoutSeconds", 2L);
        ReflectionTestUtils.setField(target, "delayLevel", 1);
        try {
            producer.start();
            // Prepare routes BEFORE consumer startup; auto-created topics can otherwise
            // spend a full route-refresh interval invisible to a just-started consumer.
            producer.createTopic("TBW102", orderTopic, 1, 0, Collections.emptyMap());
            producer.createTopic("TBW102", delayTopic, 1, 0, Collections.emptyMap());
            when(mq.syncSend(eq(RocketMqConstants.PRODUCT_SECKILL_ORDER_TOPIC), any(Object.class)))
                    .thenAnswer(call -> {
                        ProductOrder order = call.getArgument(1); sent.add(order);
                        return producer.send(new org.apache.rocketmq.common.message.Message(orderTopic, mapper.writeValueAsBytes(order)));
                    });
            when(mq.syncSend(eq(RocketMqConstants.PRODUCT_ORDER_TIMEOUT_TOPIC), any(Message.class), anyLong(), anyInt()))
                    .thenAnswer(call -> {
                        Message<?> payload = call.getArgument(1);
                        org.apache.rocketmq.common.message.Message message = new org.apache.rocketmq.common.message.Message(
                                delayTopic, payload.getPayload().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        message.setDelayTimeLevel(call.getArgument(3));
                        return producer.send(message);
                    });
            consumer.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently) (messages, context) -> {
                try {
                    for (org.apache.rocketmq.common.message.MessageExt message : messages) {
                        if (message.getTopic().equals(delayTopic)) {
                            delayDeliveries.incrementAndGet();
                            Long id = Long.valueOf(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
                            orders.cancelOrder(id);
                            if (orders.getById(id).getStatus() == 4) closed.countDown();
                        } else {
                            if (attempts.incrementAndGet() == 1)
                                return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.RECONSUME_LATER;
                            if (message.getReconsumeTimes() > 0) retryDeliveries.incrementAndGet();
                            consume(mapper.readValue(message.getBody(), ProductOrder.class));
                        }
                    }
                    return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                } catch (Exception e) {
                    lastFailure.set(e);
                    return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            });
            consumer.start();
            Product p = product(1);
            ProductOrder order = accepted(p, 7001);
            boolean completed = closed.await(50, TimeUnit.SECONDS);
            assertTrue(completed, "real retry/delay delivery did not finish: attempts=" + attempts
                    + ", retries=" + retryDeliveries + ", delays=" + delayDeliveries + ", lastFailure=" + lastFailure.get());
            assertTrue(retryDeliveries.get() >= 1, "must observe broker redelivery, not a manual second invocation");
            assertEquals(4, orders.getById(order.getId()).getStatus().intValue());
            assertEquals(1, dbStock(p.getId())); assertEquals(1, stock(p.getId()));

            ReflectionTestUtils.setField(target, "timeoutSeconds", 1800L);
            ReflectionTestUtils.setField(target, "delayLevel", 16);
            Product batch = product(200);
            long start = System.nanoTime();
            parallel(200, i -> assertTrue(buy(batch.getId(), 8000 + i).getSuccess()));
            long acceptedAt = System.nanoTime();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int persisted;
            do {
                persisted = jdbc.queryForObject("SELECT COUNT(*) FROM tb_product_order WHERE product_id=? AND status=1", Integer.class, batch.getId());
                if (persisted == 200) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            long drainedAt = System.nanoTime();
            assertEquals(200, persisted); assertEquals(0, dbStock(batch.getId())); assertEquals(0, stock(batch.getId()));
            Map<String,Object> report = new LinkedHashMap<>();
            report.put("run", run); report.put("realBrokerRetryObserved", true); report.put("realDelayCloseObserved", true);
            report.put("operation", "service call -> real RocketMQ -> MySQL; excludes HTTP/AOP rate limiting");
            report.put("requests", 200); report.put("accepted", 200); report.put("persisted", persisted); report.put("concurrency", 16);
            report.put("acceptanceSeconds", (acceptedAt-start)/1e9); report.put("endToEndSeconds", (drainedAt-start)/1e9);
            report.put("acceptanceTps", 200e9/(acceptedAt-start)); report.put("persistedTps", 200e9/(drainedAt-start));
            mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File("target/resume-mq-evidence.json"), report);
        } finally {
            consumer.shutdown(); producer.shutdown();
            ReflectionTestUtils.setField(target, "timeoutSeconds", 1800L);
            ReflectionTestUtils.setField(target, "delayLevel", 16);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="resume.benchmark", matches="true")
    void measureWarmProductReadsAgainstDirectDatabase() throws Exception {
        Product p = product(1);
        for (int i=0; i<100; i++) { products.queryProductById(p.getId()); products.getById(p.getId()); }
        List<Map<String,Object>> rounds = new ArrayList<>();
        for (int round=0; round<3; round++) {
            long[] cached = new long[500], database = new long[500];
            for (int i=0; i<500; i++) {
                if ((round+i)%2==0) {
                    cached[i]=timed(() -> assertTrue(products.queryProductById(p.getId()).getSuccess()));
                    database[i]=timed(() -> assertNotNull(products.getById(p.getId())));
                } else {
                    database[i]=timed(() -> assertNotNull(products.getById(p.getId())));
                    cached[i]=timed(() -> assertTrue(products.queryProductById(p.getId()).getSuccess()));
                }
            }
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("round", round+1); result.put("readsPerPath",500);
            result.put("cacheMeanMs", Arrays.stream(cached).average().getAsDouble()/1e6);
            result.put("databaseMeanMs", Arrays.stream(database).average().getAsDouble()/1e6);
            Arrays.sort(cached); Arrays.sort(database);
            result.put("cacheP95Ms",cached[474]/1e6); result.put("databaseP95Ms",database[474]/1e6);
            rounds.add(result);
        }
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("operation","single-thread warm product metadata service query; same product; alternating cache/DB; excludes HTTP");
        report.put("java",System.getProperty("java.version")); report.put("processors",Runtime.getRuntime().availableProcessors());
        report.put("rounds",rounds);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File("target/resume-cache-benchmark.json"), report);
    }
    long timed(Runnable operation) { long start=System.nanoTime(); operation.run(); return System.nanoTime()-start; }

    @TestConfiguration static class TestConfig { @Bean LimitedFacade limitedFacade() { return new LimitedFacade(); } }
    public static class LimitedFacade {
        @RateLimit(name="evidence-user", key="#p0", scopes=RateLimitScope.USER, limit=1)
        public void byUser(String key) { }
        @RateLimit(name="evidence-ip", key="#p0", scopes=RateLimitScope.IP, limit=1)
        public void byIp(String key) { }
        @RateLimit(name="evidence-global", key="#p0", scopes=RateLimitScope.GLOBAL, limit=1)
        public void global(String key) { }
    }
}
