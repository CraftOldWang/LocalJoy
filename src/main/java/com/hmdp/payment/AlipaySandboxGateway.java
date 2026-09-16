package com.hmdp.payment;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.internal.util.AlipayLogger;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.hmdp.entity.ProductOrder;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Intentionally sandbox-only. There is no production gateway switch. */
@Component
@Slf4j
public class AlipaySandboxGateway implements PaymentGateway {
    public static final String GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private final AlipayProperties properties;
    private volatile AlipayClient client;
    private volatile String publicKey;
    public AlipaySandboxGateway(AlipayProperties properties) { this.properties = properties; }

    @Override public boolean ready() {
        if (!properties.isAlipayEnabled() || !properties.getAppId().matches("[0-9]{10,32}")
                || !properties.getSellerId().matches("[0-9]{10,32}")) return false;
        try { client(); return true; } catch (Exception ignored) { return false; }
    }
    private synchronized AlipayClient client() {
        if (client != null) return client;
        if (!properties.isAlipayEnabled()) throw new IllegalStateException("支付宝沙箱尚未配置");
        try {
            String privateKey = readKey(properties.getPrivateKeyPath());
            publicKey = readKey(properties.getAlipayPublicKeyPath());
            // Validate key formats locally before reporting the channel as configured.
            java.security.KeyFactory factory = java.security.KeyFactory.getInstance("RSA");
            factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(privateKey)));
            factory.generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(publicKey)));
            AlipayConfig config = new AlipayConfig();
            config.setServerUrl(GATEWAY); config.setAppId(properties.getAppId());
            config.setPrivateKey(privateKey); config.setAlipayPublicKey(publicKey);
            config.setFormat("json"); config.setCharset("UTF-8"); config.setSignType("RSA2");
            config.setConnectTimeout(3000); config.setReadTimeout(5000);
            // SDK defaults log full signed payloads, including buyer fields. Keep only our sanitized diagnostics.
            AlipayLogger.setNeedEnableLogger(false);
            client = new DefaultAlipayClient(config);
            return client;
        } catch (Exception e) { throw new IllegalStateException("沙箱密钥配置不完整或格式错误"); }
    }
    static String readKey(String path) throws Exception {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException();
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        return text.replaceAll("-----[^-]+-----", "").replaceAll("\\s|\\uFEFF", "");
    }
    public static long cents(String value) {
        if (value == null || !value.matches("[0-9]{1,9}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("金额格式错误");
        return new BigDecimal(value).movePointRight(2).longValueExact();
    }
    @Override public String create(PaymentAttempt attempt, ProductOrder order) {
        try {
            AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
            model.setOutTradeNo(attempt.getOutTradeNo()); model.setSellerId(attempt.getSellerId());
            model.setSubject(order.getSubject());
            model.setTotalAmount(BigDecimal.valueOf(attempt.getAmount(), 2).toPlainString());
            model.setTimeExpire(order.getExpireTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
            request.setBizModel(model);
            // Outbound query is a fallback when no public callback URL has been configured.
            if (!properties.getNotifyUrl().trim().isEmpty()) request.setNotifyUrl(properties.getNotifyUrl());
            AlipayTradePrecreateResponse response = client().execute(request);
            if (!response.isSuccess() || !attempt.getOutTradeNo().equals(response.getOutTradeNo())
                    || response.getQrCode() == null || response.getQrCode().isEmpty()) {
                log.warn("Alipay sandbox precreate unconfirmed: code={}, subCode={}", safeCode(response.getCode()), safeCode(response.getSubCode()));
                throw new IllegalStateException("沙箱未确认预下单");
            }
            return response.getQrCode();
        } catch (Exception e) { throw new IllegalStateException("沙箱预下单结果待确认，请查询支付结果"); }
    }
    @Override public Trade query(String outTradeNo) {
        try {
            AlipayTradeQueryModel model = new AlipayTradeQueryModel(); model.setOutTradeNo(outTradeNo);
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest(); request.setBizModel(model);
            AlipayTradeQueryResponse response = client().execute(request);
            if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) return new Trade("NOT_FOUND", outTradeNo, null, null);
            if (!response.isSuccess()) throw new IllegalStateException();
            return new Trade(response.getTradeStatus(), response.getOutTradeNo(), response.getTradeNo(), cents(response.getTotalAmount()));
        } catch (Exception e) { throw new IllegalStateException("沙箱查单暂不可用"); }
    }
    @Override public boolean close(String outTradeNo) {
        try {
            AlipayTradeCloseModel model = new AlipayTradeCloseModel(); model.setOutTradeNo(outTradeNo);
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest(); request.setBizModel(model);
            AlipayTradeCloseResponse response = client().execute(request);
            // NOT_EXIST is not proof that an in-flight create request cannot arrive later.
            return response.isSuccess() && outTradeNo.equals(response.getOutTradeNo());
        } catch (Exception e) { throw new IllegalStateException("沙箱关单结果待确认"); }
    }
    @Override public boolean verify(Map<String, String> parameters) {
        if (!ready() || !"RSA2".equals(parameters.get("sign_type"))) return false;
        try { return AlipaySignature.rsaCheckV1(parameters, publicKey, "UTF-8", "RSA2"); }
        catch (Exception ignored) { return false; }
    }
    private String safeCode(String value) {
        return value != null && value.matches("[A-Za-z0-9_.]{1,80}") ? value : "UNKNOWN";
    }
}
