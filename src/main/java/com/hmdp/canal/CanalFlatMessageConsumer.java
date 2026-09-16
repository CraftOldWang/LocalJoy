package com.hmdp.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.FlatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.mq.RocketMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes Canal flat messages from RocketMQ and dispatches them to table handlers.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "canal.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = RocketMqConstants.CANAL_CACHE_TOPIC,
        consumerGroup = RocketMqConstants.CANAL_CACHE_CONSUMER_GROUP
)
public class CanalFlatMessageConsumer implements RocketMQListener<String> {

    @Resource
    private ObjectMapper objectMapper;

    @Value("${canal.database:hmdp}")
    private String database;

    @Autowired
    private List<CanalCacheHandler> cacheHandlers;

    private final Map<String, CanalCacheHandler> handlerMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (CanalCacheHandler handler : cacheHandlers) {
            handlerMap.put(handler.getTableName(), handler);
            log.info("注册 Canal MQ 缓存处理器：表={}", handler.getTableName());
        }
    }

    @Override
    public void onMessage(String payload) {
        try {
            FlatMessage message = objectMapper.readValue(payload, FlatMessage.class);
            processMessage(message);
        } catch (Exception e) {
            log.error("处理 Canal RocketMQ 消息失败，payload={}", payload, e);
            throw new IllegalStateException("Canal RocketMQ message process failed", e);
        }
    }

    private void processMessage(FlatMessage message) {
        if (message == null || Boolean.TRUE.equals(message.getIsDdl())) {
            return;
        }
        if (!database.equals(message.getDatabase())) return;

        CanalCacheHandler handler = handlerMap.get(message.getTable());
        if (handler == null) {
            log.debug("表 {} 没有注册 CanalCacheHandler，跳过", message.getTable());
            return;
        }

        CanalEntry.EventType eventType = parseEventType(message.getType());
        if (eventType == null) {
            return;
        }

        List<Map<String, String>> dataRows = message.getData();
        if (dataRows == null || dataRows.isEmpty()) {
            return;
        }

        List<Map<String, String>> oldRows = message.getOld();
        for (int i = 0; i < dataRows.size(); i++) {
            Map<String, String> dataRow = dataRows.get(i);
            Map<String, String> oldRow = oldRows != null && i < oldRows.size()
                    ? oldRows.get(i)
                    : Collections.emptyMap();

            Map<String, String> beforeData = buildBeforeData(eventType, dataRow, oldRow);
            Map<String, String> afterData = buildAfterData(eventType, dataRow);
            handler.handleChange(eventType, beforeData, afterData);
        }
    }

    private CanalEntry.EventType parseEventType(String type) {
        try {
            return CanalEntry.EventType.valueOf(type);
        } catch (Exception e) {
            log.debug("Canal MQ 收到不处理的事件类型：{}", type);
            return null;
        }
    }

    private Map<String, String> buildBeforeData(CanalEntry.EventType eventType,
                                                Map<String, String> dataRow,
                                                Map<String, String> oldRow) {
        if (eventType == CanalEntry.EventType.DELETE) {
            return copyOf(dataRow);
        }
        if (eventType == CanalEntry.EventType.UPDATE) {
            Map<String, String> beforeData = copyOf(dataRow);
            beforeData.putAll(oldRow);
            return beforeData;
        }
        return new HashMap<>();
    }

    private Map<String, String> buildAfterData(CanalEntry.EventType eventType, Map<String, String> dataRow) {
        if (eventType == CanalEntry.EventType.DELETE) {
            return new HashMap<>();
        }
        return copyOf(dataRow);
    }

    private Map<String, String> copyOf(Map<String, String> source) {
        return source == null ? new HashMap<>() : new HashMap<>(source);
    }
}
