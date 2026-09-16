package com.hmdp;

import com.alipay.api.internal.util.AlipaySignature;
import com.hmdp.payment.AlipayProperties;
import com.hmdp.payment.AlipaySandboxGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AlipaySignatureTest {
    @TempDir Path directory;
    @Test void realRsa2VerificationRejectsTamperingWithoutCallingAlipay() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair app = generator.generateKeyPair(), provider = generator.generateKeyPair();
        String appPrivate = Base64.getEncoder().encodeToString(app.getPrivate().getEncoded());
        String providerPrivate = Base64.getEncoder().encodeToString(provider.getPrivate().getEncoded());
        Path privateFile = directory.resolve("app.txt"), publicFile = directory.resolve("alipay.txt");
        Files.write(privateFile, appPrivate.getBytes(StandardCharsets.UTF_8));
        Files.write(publicFile, Base64.getEncoder().encode(provider.getPublic().getEncoded()));
        AlipayProperties config = new AlipayProperties(); config.setAlipayEnabled(true);
        config.setAppId("2026000000000001"); config.setSellerId("2088000000000001");
        config.setPrivateKeyPath(privateFile.toString()); config.setAlipayPublicKeyPath(publicFile.toString());
        AlipaySandboxGateway gateway = new AlipaySandboxGateway(config); assertTrue(gateway.ready());
        Map<String,String> p = new HashMap<>(); p.put("out_trade_no","LJ123"); p.put("total_amount","19.90");
        p.put("trade_status","TRADE_SUCCESS");
        String content = AlipaySignature.getSignContent(p);
        p.put("sign",AlipaySignature.rsaSign(content,providerPrivate,"UTF-8","RSA2")); p.put("sign_type","RSA2");
        assertTrue(gateway.verify(new HashMap<>(p)));
        p.put("total_amount","0.01"); assertFalse(gateway.verify(new HashMap<>(p)));
        p.put("total_amount","19.90"); p.put("sign_type","RSA"); assertFalse(gateway.verify(new HashMap<>(p)));
        assertEquals(1990L,AlipaySandboxGateway.cents("19.90"));
        assertThrows(IllegalArgumentException.class, () -> AlipaySandboxGateway.cents("19.901"));
    }
}
