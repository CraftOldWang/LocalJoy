package com.hmdp.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;

import java.util.Map;

/**
 * Canal 缓存处理器接口
 * 每个需要监听 binlog 变更来维护缓存一致性的表，实现此接口
 */
public interface CanalCacheHandler {

    /**
     * 获取监听的表名（不含库名）
     */
    String getTableName();

    /**
     * 处理数据变更事件
     *
     * @param eventType 事件类型（INSERT/UPDATE/DELETE）
     * @param rowData   变更行数据，key 为列名，value 为列值
     */
    void handleChange(CanalEntry.EventType eventType, Map<String, String> rowData);

    default void handleChange(CanalEntry.EventType eventType, Map<String, String> beforeData, Map<String, String> afterData) {
        handleChange(eventType, eventType == CanalEntry.EventType.DELETE ? beforeData : afterData);
    }
}
