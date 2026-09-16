package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Canal 连接配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "canal")
public class CanalProperties {

    private Server server = new Server();
    private Boolean enabled = true;
    private String destination = "hmdp";
    private String username = "";
    private String password = "";
    private String subscribe = "hmdp\\.(tb_shop|tb_shop_type|tb_product)";
    private Integer batchSize = 100;
    private Long emptySleepMillis = 200L;
    private Long reconnectSeconds = 5L;

    @Data
    public static class Server {
        private String host = "127.0.0.1";
        private int port = 11111;
    }
}
