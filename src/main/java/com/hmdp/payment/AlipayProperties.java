package com.hmdp.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// No generated toString: configuration can contain private key material.
@Getter @Setter
@Component
@ConfigurationProperties(prefix = "localjoy.payment")
public class AlipayProperties {
    private boolean mockEnabled = true;
    private boolean alipayEnabled = false;
    private boolean reconcileEnabled = true;
    private String appId = "";
    private String sellerId = "";
    private String privateKeyPath = "";
    private String alipayPublicKeyPath = "";
    private String notifyUrl = "";
}
