package com.hmdp.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hmdp.config.CanalProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Canal 客户端
 * 应用启动后连接 Canal Server，持续监听 MySQL binlog 变更事件，
 * 并将变更分发给对应的 CanalCacheHandler 处理缓存一致性。
 *
 * <p>核心架构：MySQL binlog → Canal Server → 本客户端 → CanalCacheHandler → 删除/更新 Redis 缓存</p>
 */
@Slf4j
@Component
public class CanalClient implements CommandLineRunner {

    @Autowired
    private CanalProperties canalProperties;

    @Autowired
    private List<CanalCacheHandler> cacheHandlers;

    /** 按表名索引的处理器映射 */
    private final Map<String, CanalCacheHandler> handlerMap = new ConcurrentHashMap<>();

    private CanalConnector connector;
    private volatile boolean running = true;
    private Thread workerThread;

    @Override
    public void run(String... args) {
        if (!Boolean.TRUE.equals(canalProperties.getEnabled())) {
            log.info("Canal 客户端未启用");
            return;
        }
        // 构建 handler 映射
        for (CanalCacheHandler handler : cacheHandlers) {
            handlerMap.put(handler.getTableName(), handler);
            log.info("注册 Canal 缓存处理器：表={}", handler.getTableName());
        }

        // 在独立线程中启动 Canal 消费循环
        workerThread = new Thread(this::startCanalLoop, "canal-client-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void startCanalLoop() {
        String host = canalProperties.getServer().getHost();
        int port = canalProperties.getServer().getPort();
        String destination = canalProperties.getDestination();
        String subscribe = canalProperties.getSubscribe();

        log.info("Canal 客户端启动，连接 {}:{}，destination={}，subscribe={}", host, port, destination, subscribe);

        while (running) {
            try {
                // 创建连接
                connector = CanalConnectors.newSingleConnector(
                        new InetSocketAddress(host, port),
                        destination,
                        canalProperties.getUsername(),
                        canalProperties.getPassword()
                );
                connector.connect();
                connector.subscribe(subscribe);
                connector.rollback();

                log.info("Canal 客户端连接成功");

                // 消费循环
                while (running) {
                    // 批量获取数据，最多 100 条，超时 1 秒
                    Message message = connector.getWithoutAck(canalProperties.getBatchSize());
                    long batchId = message.getId();
                    int size = message.getEntries().size();

                    if (batchId == -1 || size == 0) {
                        // 没有数据，短暂休眠
                        TimeUnit.MILLISECONDS.sleep(canalProperties.getEmptySleepMillis());
                        continue;
                    }

                    try {
                        processEntries(message.getEntries());
                        connector.ack(batchId);
                    } catch (Exception e) {
                        log.error("Canal 处理消息失败，batchId={}", batchId, e);
                        connector.rollback(batchId);
                    }
                }

            } catch (Exception e) {
                if (running) {
                    log.error("Canal 客户端异常，{} 秒后重连...", canalProperties.getReconnectSeconds(), e);
                    disconnect();
                    try {
                        TimeUnit.SECONDS.sleep(canalProperties.getReconnectSeconds());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * 处理 Canal Entry 列表
     */
    private void processEntries(List<CanalEntry.Entry> entries) {
        for (CanalEntry.Entry entry : entries) {
            // 跳过事务开始/结束标记
            if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONBEGIN
                    || entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND) {
                continue;
            }

            CanalEntry.RowChange rowChange;
            try {
                rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Canal 解析失败，必须重试而非确认消费", e);
            }

            String tableName = entry.getHeader().getTableName();
            CanalEntry.EventType eventType = rowChange.getEventType();

            // 查找对应的缓存处理器
            CanalCacheHandler handler = handlerMap.get(tableName);
            if (handler == null) {
                log.debug("表 {} 没有注册 CanalCacheHandler，跳过", tableName);
                continue;
            }

            // 遍历每一行变更
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                Map<String, String> beforeData = toMap(rowData.getBeforeColumnsList());
                Map<String, String> afterData = toMap(rowData.getAfterColumnsList());

                // 分发给处理器
                try {
                    handler.handleChange(eventType, beforeData, afterData);
                } catch (Exception e) {
                    log.error("Canal 处理器执行失败，表={}，事件={}", tableName, eventType, e);
                    throw e;
                }
            }
        }
    }

    private Map<String, String> toMap(List<CanalEntry.Column> columns) {
        Map<String, String> dataMap = new HashMap<>();
        for (CanalEntry.Column column : columns) {
            dataMap.put(column.getName(), column.getValue());
        }
        return dataMap;
    }

    private void disconnect() {
        if (connector != null) {
            try {
                connector.disconnect();
            } catch (Exception e) {
                log.warn("Canal 断开连接异常", e);
            }
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Canal 客户端关闭中...");
        running = false;
        disconnect();
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}
