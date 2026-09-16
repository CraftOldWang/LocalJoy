package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Product;
import com.hmdp.entity.ProductOrder;
import com.hmdp.mapper.PaymentAttemptMapper;
import com.hmdp.mq.RocketMqConstants;
import com.hmdp.payment.*;
import com.hmdp.service.IProductOrderService;
import com.hmdp.service.IProductService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real isolated MySQL + Redis. Only Alipay and RocketMQ transport are mocked. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("resume-evidence")
class AlipayPaymentTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path keyDirectory;
    @Autowired PaymentService payments;
    @Autowired PaymentAttemptMapper attempts;
    @Autowired AlipayProperties config;
    @Autowired IProductService products;
    @Autowired IProductOrderService orders;
    @Autowired JdbcTemplate jdbc;
    @SpyBean StringRedisTemplate redis;
    @Autowired CasConflictProbe casProbe;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.boot.autoconfigure.data.redis.RedisProperties redisConfig;
    @MockBean PaymentGateway gateway;
    @MockBean RocketMQTemplate mq;
    final List<Long> fixtures = new ArrayList<>();
    final List<ProductOrder> sent = new ArrayList<>();
    final long userId = 990099L;

    @BeforeEach void prepare() {
        assertEquals("hmdp_resume_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(14, redisConfig.getDatabase());
        config.setAppId("2026000000000001"); config.setSellerId("2088000000000001");
        config.setMockEnabled(true);
        when(gateway.ready()).thenReturn(true);
        when(gateway.verify(anyMap())).thenReturn(true);
        when(gateway.create(any(), any())).thenReturn("https://qr.alipay.com/test-only");
        when(gateway.query(anyString())).thenAnswer(call -> new PaymentGateway.Trade("NOT_FOUND", call.getArgument(0), null, null));
        SendResult ok = new SendResult(); ok.setSendStatus(SendStatus.SEND_OK);
        when(mq.syncSend(eq(RocketMqConstants.PRODUCT_SECKILL_ORDER_TOPIC), any(Object.class)))
                .thenAnswer(call -> { sent.add(call.getArgument(1)); return ok; });
        when(mq.syncSend(eq(RocketMqConstants.PRODUCT_ORDER_TIMEOUT_TOPIC), any(Message.class), anyLong(), anyInt())).thenReturn(ok);
        user(userId);
    }
    @AfterEach void cleanup() {
        UserHolder.removeUser();
        for (Long id : fixtures) {
            jdbc.update("DELETE p FROM tb_payment_attempt p JOIN tb_product_order o ON o.id=p.order_id WHERE o.product_id=?", id);
            jdbc.update("DELETE FROM tb_product_order WHERE product_id=?", id);
            jdbc.update("DELETE FROM tb_seckill_product WHERE product_id=?", id);
            jdbc.update("DELETE FROM tb_product WHERE id=?", id);
            redis.delete(Arrays.asList(RedisConstants.PRODUCT_SECKILL_STOCK_KEY+id, RedisConstants.PRODUCT_SECKILL_BUYER_KEY+id,
                    RedisConstants.PRODUCT_SECKILL_META_KEY+id, RedisConstants.CACHE_PRODUCT_KEY+id));
        }
    }
    void user(long id) { UserDTO user = new UserDTO(); user.setId(id); UserHolder.saveUser(user); }
    ProductOrder order() {
        Product p = new Product().setShopId(1L).setTitle("支付测试商品").setPrice(1990L).setStatus(1).setStock(2)
                .setBeginTime(LocalDateTime.now().minusMinutes(1)).setEndTime(LocalDateTime.now().plusHours(1));
        products.addSeckillProduct(p); fixtures.add(p.getId());
        Result accepted = orders.seckillProduct(p.getId()); assertTrue(accepted.getSuccess(), accepted.getErrorMsg());
        ProductOrder incoming = sent.get(sent.size()-1); orders.createProductOrder(incoming);
        return orders.getById(incoming.getId());
    }
    void start(ProductOrder order) { assertTrue(payments.start(order.getId()).getSuccess()); }
    void expire(ProductOrder order) { jdbc.update("UPDATE tb_product_order SET expire_time=? WHERE id=?", LocalDateTime.now().minusSeconds(5), order.getId()); }
    void allowQuery(ProductOrder order) { jdbc.update("UPDATE tb_payment_attempt SET last_checked_at=? WHERE order_id=?", LocalDateTime.now().minusMinutes(1), order.getId()); }
    String out(ProductOrder o) { return "LJ"+o.getId(); }
    String tradeNo(ProductOrder o) { return "T"+o.getId(); }
    Map<String,String> notification(ProductOrder o) {
        Map<String,String> p = new HashMap<>();
        p.put("app_id", config.getAppId()); p.put("seller_id", config.getSellerId());
        p.put("out_trade_no", out(o)); p.put("trade_no", tradeNo(o));
        p.put("total_amount", "19.90"); p.put("trade_status", "TRADE_SUCCESS"); return p;
    }
    void assertStock(ProductOrder o, int expected) {
        assertEquals(expected, jdbc.queryForObject("SELECT stock FROM tb_seckill_product WHERE product_id=?", Integer.class, o.getProductId()).intValue());
        assertEquals(Integer.toString(expected), redis.opsForValue().get(RedisConstants.PRODUCT_SECKILL_STOCK_KEY+o.getProductId()));
    }

    @Test void callbackAndTimeoutRunConcurrentlyWithoutCancelingPaidOrder() throws Exception {
        ProductOrder o = order(); start(o); expire(o);
        when(gateway.query(out(o))).thenReturn(new PaymentGateway.Trade("TRADE_SUCCESS", out(o), tradeNo(o), 1990L));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(16), go = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                final boolean callback = i % 2 == 0;
                workers.add(pool.submit(() -> {
                    ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                    if (callback) assertTrue(payments.notify(notification(o)));
                    else orders.cancelOrder(o.getId());
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            for (Future<?> worker : workers) worker.get(15, TimeUnit.SECONDS);
        } finally { go.countDown(); pool.shutdownNow(); }
        assertEquals(Integer.valueOf(2), orders.getById(o.getId()).getStatus());
        assertEquals(Integer.valueOf(2), orders.getById(o.getId()).getVersion());
        assertEquals("SUCCEEDED", attempts.selectById(o.getId()).getState());
        assertStock(o, 1);
        assertEquals(o.getId().toString(), redis.opsForHash().get(RedisConstants.PRODUCT_SECKILL_BUYER_KEY+o.getProductId(), Long.toString(userId)));
        verify(gateway, never()).close(anyString());
    }

    @Test void staleDatabaseVersionRejectsCallbackUntilRetryEvenWithoutRedisContender() throws Exception {
        ProductOrder o = order(); start(o);
        ExecutorService otherConnection = Executors.newSingleThreadExecutor();
        // Intercept the actual mapper write after its read. An independent committed DB write
        // bypasses the Redis lock, proving the version predicate itself rejects stale updates.
        casProbe.beforeNextOrderUpdate.set(() -> {
            try {
                assertEquals(1, otherConnection.submit(() -> jdbc.update(
                        "UPDATE tb_product_order SET version=version+1 WHERE id=?", o.getId())).get(5, TimeUnit.SECONDS).intValue());
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        try {
            assertFalse(payments.notify(notification(o)));
            assertEquals(Integer.valueOf(0), casProbe.affectedRows.get());
            assertEquals(Integer.valueOf(1), orders.getById(o.getId()).getStatus());
            assertEquals(Integer.valueOf(2), orders.getById(o.getId()).getVersion());
            assertEquals("WAITING", attempts.selectById(o.getId()).getState());
            assertTrue(payments.notify(notification(o)));
            assertEquals(Integer.valueOf(2), orders.getById(o.getId()).getStatus());
            assertEquals(Integer.valueOf(3), orders.getById(o.getId()).getVersion());
            assertStock(o, 1);
        } finally { casProbe.beforeNextOrderUpdate.set(null); otherConnection.shutdownNow(); }
    }

    @Test void channelClosedButDatabaseRollbackRecoversFromPersistedLedger() {
        ProductOrder o = order(); start(o); expire(o);
        when(gateway.close(out(o))).thenReturn(true);
        jdbc.update("DELETE FROM tb_seckill_product WHERE product_id=?", o.getProductId());
        assertThrows(IllegalStateException.class, () -> orders.cancelOrder(o.getId()));
        assertEquals(Integer.valueOf(1), orders.getById(o.getId()).getStatus());
        assertEquals("CLOSED", attempts.selectById(o.getId()).getState());
        assertEquals("1", redis.opsForValue().get(RedisConstants.PRODUCT_SECKILL_STOCK_KEY+o.getProductId()));
        jdbc.update("INSERT INTO tb_seckill_product(product_id,stock,begin_time,end_time) VALUES(?,1,?,?)",
                o.getProductId(), LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        reconcileOnce();
        assertEquals(Integer.valueOf(4), orders.getById(o.getId()).getStatus()); assertStock(o, 2);
        verify(gateway, times(1)).close(out(o));
    }

    @Test void redisFailureAfterCancelCommitRetriesOnlyReservationCompensation() {
        ProductOrder o = order(); start(o); expire(o);
        when(gateway.close(out(o))).thenReturn(true);
        doThrow(new RedisConnectionFailureException("injected rollback outage")).doCallRealMethod()
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any(), any());
        assertThrows(RedisConnectionFailureException.class, () -> orders.cancelOrder(o.getId()));
        assertEquals(Integer.valueOf(4), orders.getById(o.getId()).getStatus());
        assertEquals(Integer.valueOf(2), jdbc.queryForObject("SELECT stock FROM tb_seckill_product WHERE product_id=?", Integer.class, o.getProductId()));
        assertEquals("1", redis.opsForValue().get(RedisConstants.PRODUCT_SECKILL_STOCK_KEY+o.getProductId()));
        orders.cancelOrder(o.getId()); orders.cancelOrder(o.getId());
        assertStock(o, 2);
        assertNull(redis.opsForHash().get(RedisConstants.PRODUCT_SECKILL_BUYER_KEY+o.getProductId(), Long.toString(userId)));
        assertEquals(Integer.valueOf(2), orders.getById(o.getId()).getVersion());
        verify(gateway, times(1)).close(out(o));
    }

    @Test void scheduledRecoveryPaysWithoutCallbackAndClosesConfirmedExpiredOrders() {
        ProductOrder paid = order(); start(paid); allowQuery(paid);
        ProductOrder closed = order(); start(closed); expire(closed);
        when(gateway.query(out(paid))).thenReturn(new PaymentGateway.Trade("TRADE_SUCCESS", out(paid), tradeNo(paid), 1990L));
        when(gateway.query(out(closed))).thenReturn(new PaymentGateway.Trade("TRADE_CLOSED", out(closed), tradeNo(closed), 1990L));
        reconcileOnce(); reconcileOnce();
        assertEquals(Integer.valueOf(2), orders.getById(paid.getId()).getStatus()); assertStock(paid, 1);
        assertEquals(Integer.valueOf(4), orders.getById(closed.getId()).getStatus()); assertStock(closed, 2);
    }

    private void reconcileOnce() {
        // Keep the Spring scheduled bean disabled; run the same recovery method deterministically.
        AlipayProperties recoveryConfig = new AlipayProperties(); recoveryConfig.setReconcileEnabled(true);
        new PaymentReconciler(jdbc, payments, orders, recoveryConfig, gateway).reconcile();
    }

    @Test void amountIsSnapshotAndOnlyOwnerCanCreateOneAttempt() {
        ProductOrder o = order();
        jdbc.update("UPDATE tb_product SET price=2990,title='改价后的商品' WHERE id=?", o.getProductId());
        user(userId+1); assertFalse(payments.start(o.getId()).getSuccess()); assertFalse(payments.view(o.getId()).getSuccess());
        user(userId); start(o); start(o);
        assertEquals(Long.valueOf(1990), attempts.selectById(o.getId()).getAmount());
        assertEquals("支付测试商品", orders.getById(o.getId()).getSubject());
        verify(gateway, times(1)).create(any(), any());
        assertFalse(orders.payOrder(o.getId()).getSuccess()); assertStock(o,1);
    }
    @Test void unknownCreateIsNeverRepeatedOrRestockedOnNotFound() {
        ProductOrder o = order();
        when(gateway.create(any(),any())).thenThrow(new IllegalStateException("lost acknowledgement"));
        start(o); start(o);
        assertEquals("UNKNOWN", attempts.selectById(o.getId()).getState());
        verify(gateway,times(1)).create(any(),any());
        expire(o); assertThrows(IllegalStateException.class, () -> orders.cancelOrder(o.getId()));
        assertEquals(Integer.valueOf(1), orders.getById(o.getId()).getStatus()); assertStock(o,1);
    }
    @Test void signedIdentityAndAmountMustAllMatch() {
        ProductOrder o = order(); start(o);
        for (String field : Arrays.asList("app_id","seller_id","total_amount","out_trade_no")) {
            Map<String,String> n = notification(o); n.put(field,"123"); assertFalse(payments.notify(n), field);
        }
        Map<String,String> n = notification(o); n.put("trade_no", ""); assertFalse(payments.notify(n));
        when(gateway.verify(anyMap())).thenReturn(false); assertFalse(payments.notify(notification(o)));
        assertEquals(Integer.valueOf(1),orders.getById(o.getId()).getStatus()); assertStock(o,1);
    }
    @Test void concurrentDuplicateCallbacksAndLateNotificationPayOnce() throws Exception {
        ProductOrder o = order(); start(o); expire(o);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for(int i=0;i<16;i++) results.add(executor.submit(() -> payments.notify(notification(o))));
            for(Future<Boolean> result : results) assertTrue(result.get(15,TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        ProductOrder paid = orders.getById(o.getId());
        assertEquals(Integer.valueOf(2), paid.getStatus()); assertEquals(Integer.valueOf(2), paid.getVersion());
        orders.cancelOrder(o.getId()); assertStock(o,1);
        Map<String,String> conflicting = notification(o); conflicting.put("trade_no", "different");
        assertFalse(payments.notify(conflicting));
    }
    @Test void closeConfirmsChannelBeforeRestockingAndRepeatsAreHarmless() {
        ProductOrder o = order(); start(o); expire(o);
        when(gateway.query(out(o))).thenReturn(new PaymentGateway.Trade("WAIT_BUYER_PAY",out(o),tradeNo(o),1990L));
        when(gateway.close(out(o))).thenReturn(true);
        orders.cancelOrder(o.getId()); orders.cancelOrder(o.getId());
        assertEquals(Integer.valueOf(4), orders.getById(o.getId()).getStatus()); assertStock(o,2);
        assertEquals("CLOSED",attempts.selectById(o.getId()).getState()); verify(gateway,times(1)).close(out(o));
    }
    @Test void paymentWinningCloseRaceKeepsInventoryDeducted() {
        ProductOrder o = order(); start(o); expire(o);
        when(gateway.query(out(o))).thenReturn(new PaymentGateway.Trade("WAIT_BUYER_PAY",out(o),tradeNo(o),1990L))
                .thenReturn(new PaymentGateway.Trade("TRADE_SUCCESS",out(o),tradeNo(o),1990L));
        when(gateway.close(out(o))).thenReturn(false);
        orders.cancelOrder(o.getId()); assertEquals(Integer.valueOf(2),orders.getById(o.getId()).getStatus()); assertStock(o,1);
    }
    @Test void queryFailureOrWrongIdentityCannotReleaseStock() {
        ProductOrder o = order(); start(o); expire(o);
        when(gateway.query(out(o))).thenThrow(new IllegalStateException("timeout"));
        assertThrows(IllegalStateException.class, () -> orders.cancelOrder(o.getId())); assertStock(o,1);
        doReturn(new PaymentGateway.Trade("TRADE_CLOSED","another",tradeNo(o),1990L)).when(gateway).query(out(o));
        assertThrows(IllegalArgumentException.class, () -> orders.cancelOrder(o.getId())); assertStock(o,1);
    }
    @Test void authoritativeQueryRecoversMissingNotification() {
        ProductOrder o = order(); start(o); allowQuery(o);
        when(gateway.query(out(o))).thenReturn(new PaymentGateway.Trade("TRADE_SUCCESS",out(o),tradeNo(o),1990L));
        payments.syncSystem(o.getId()); assertEquals(Integer.valueOf(2),orders.getById(o.getId()).getStatus());
        assertEquals("SUCCEEDED",attempts.selectById(o.getId()).getState()); assertStock(o,1);
    }
    @Test void terminalConflictIsRetainedForManualReview() {
        ProductOrder o = order(); start(o); expire(o);
        when(gateway.close(out(o))).thenReturn(true); orders.cancelOrder(o.getId());
        assertTrue(payments.notify(notification(o)));
        assertEquals("REVIEW_REQUIRED",attempts.selectById(o.getId()).getState());
        assertEquals(Integer.valueOf(4),orders.getById(o.getId()).getStatus()); assertStock(o,2);
    }
    @Test void callbackNeedsNoLoginButRejectsDuplicatesAndBadSignatures() throws Exception {
        UserHolder.removeUser(); when(gateway.verify(anyMap())).thenReturn(false);
        mvc.perform(post("/payment/alipay/notify").contentType("application/x-www-form-urlencoded").param("sign","bad"))
                .andExpect(status().isOk()).andExpect(content().string("failure"));
        mvc.perform(post("/payment/alipay/notify").contentType("application/x-www-form-urlencoded").param("sign","a","b"))
                .andExpect(status().isOk()).andExpect(content().string("failure"));
        mvc.perform(get("/payment/alipay/123")).andExpect(status().isUnauthorized());
        mvc.perform(get("/payment/options")).andExpect(status().isOk());
    }

    @Test void realSignedHttpCallbackRejectsTamperingAndAcceptsDuplicateSuccess() throws Exception {
        ProductOrder o = order(); start(o); UserHolder.removeUser();
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair appKey = generator.generateKeyPair(), channelKey = generator.generateKeyPair();
        java.nio.file.Path privatePath = keyDirectory.resolve("app.txt"), publicPath = keyDirectory.resolve("channel.txt");
        java.nio.file.Files.write(privatePath, Base64.getEncoder().encode(appKey.getPrivate().getEncoded()));
        java.nio.file.Files.write(publicPath, Base64.getEncoder().encode(channelKey.getPublic().getEncoded()));
        AlipayProperties verificationConfig = new AlipayProperties();
        verificationConfig.setAlipayEnabled(true); verificationConfig.setAppId(config.getAppId()); verificationConfig.setSellerId(config.getSellerId());
        verificationConfig.setPrivateKeyPath(privatePath.toString()); verificationConfig.setAlipayPublicKeyPath(publicPath.toString());
        AlipaySandboxGateway verifier = new AlipaySandboxGateway(verificationConfig);
        when(gateway.verify(anyMap())).thenAnswer(call -> verifier.verify(new HashMap<>(call.getArgument(0))));
        Map<String,String> signed = notification(o);
        signed.put("sign", com.alipay.api.internal.util.AlipaySignature.rsaSign(
                com.alipay.api.internal.util.AlipaySignature.getSignContent(signed),
                Base64.getEncoder().encodeToString(channelKey.getPrivate().getEncoded()), "UTF-8", "RSA2"));
        signed.put("sign_type", "RSA2");
        Map<String,String> tampered = new HashMap<>(signed); tampered.put("total_amount", "0.01");
        postNotification(tampered, "failure");
        assertEquals(Integer.valueOf(1), orders.getById(o.getId()).getStatus());
        postNotification(signed, "success"); postNotification(signed, "success");
        assertEquals(Integer.valueOf(2), orders.getById(o.getId()).getStatus());
        assertEquals(Integer.valueOf(2), orders.getById(o.getId()).getVersion());
        assertStock(o, 1);
    }

    private void postNotification(Map<String,String> values, String expected) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                post("/payment/alipay/notify").contentType("application/x-www-form-urlencoded");
        values.forEach(request::param);
        mvc.perform(request).andExpect(status().isOk()).andExpect(content().string(expected));
    }

    @TestConfiguration static class ProbeConfig {
        @Bean CasConflictProbe casConflictProbe() { return new CasConflictProbe(); }
    }
    @Intercepts(@Signature(type=Executor.class, method="update", args={MappedStatement.class, Object.class}))
    static class CasConflictProbe implements Interceptor {
        final java.util.concurrent.atomic.AtomicReference<Runnable> beforeNextOrderUpdate = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Integer> affectedRows = new java.util.concurrent.atomic.AtomicReference<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            Runnable injection = statement.getId().endsWith("ProductOrderMapper.update") ? beforeNextOrderUpdate.getAndSet(null) : null;
            if (injection != null) injection.run();
            Object result = invocation.proceed();
            if (injection != null) affectedRows.set((Integer) result);
            return result;
        }
        @Override public Object plugin(Object target) { return Plugin.wrap(target, this); }
        @Override public void setProperties(Properties properties) { }
    }
}
