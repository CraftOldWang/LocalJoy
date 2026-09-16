package com.hmdp.payment;

import com.hmdp.entity.ProductOrder;
import com.hmdp.service.IProductOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j @Configuration @EnableScheduling
public class PaymentReconciler {
    private final JdbcTemplate jdbc;
    private final PaymentService payments;
    private final IProductOrderService orders;
    private final AlipayProperties config;
    private final PaymentGateway gateway;
    public PaymentReconciler(JdbcTemplate jdbc, PaymentService payments, IProductOrderService orders,
                             AlipayProperties config, PaymentGateway gateway) {
        this.jdbc=jdbc; this.payments=payments; this.orders=orders; this.config=config; this.gateway=gateway;
    }
    @Scheduled(initialDelay=30000, fixedDelay=15000)
    public void reconcile() {
        if (!config.isReconcileEnabled() || !gateway.ready()) return;
        // Database-backed recovery survives app restarts and absence/failure of a public callback URL.
        List<Long> ids = jdbc.queryForList("SELECT p.order_id FROM tb_payment_attempt p JOIN tb_product_order o ON o.id=p.order_id "
                + "WHERE o.status=1 AND (p.state IN ('CREATING','WAITING','UNKNOWN','CLOSING') "
                + "OR (p.state='CLOSED' AND o.expire_time<=?)) "
                + "ORDER BY COALESCE(p.last_checked_at,p.create_time) LIMIT 20", Long.class, LocalDateTime.now());
        for (Long id : ids) {
            try {
                ProductOrder order = orders.getById(id);
                if (order.getExpireTime() != null && !order.getExpireTime().isAfter(LocalDateTime.now())) orders.cancelOrder(id);
                else payments.syncSystem(id);
            } catch (Exception e) { log.warn("沙箱支付对账待重试。orderId={}", id); }
        }
    }
}
