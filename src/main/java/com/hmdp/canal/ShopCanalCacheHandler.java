package com.hmdp.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

/**
 * tb_shop 表的 Canal 缓存处理器
 * 监听 tb_shop 表的 binlog 变更，自动删除对应的 Redis 缓存
 */
@Slf4j
@Component
public class ShopCanalCacheHandler implements CanalCacheHandler {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String getTableName() {
        return "tb_shop";
    }

    @Override
    public void handleChange(CanalEntry.EventType eventType, Map<String, String> rowData) {
        handleChange(eventType, rowData, rowData);
    }

    @Override
    public void handleChange(CanalEntry.EventType eventType, Map<String, String> beforeData, Map<String, String> afterData) {
        // 获取主键 id
        String id = eventType == CanalEntry.EventType.DELETE ? beforeData.get("id") : afterData.get("id");
        if (id == null) {
            log.warn("Canal 收到 tb_shop 变更事件但无 id 字段，跳过");
            return;
        }

        String cacheKey = RedisConstants.CACHE_SHOP_KEY + id;

        switch (eventType) {
            case UPDATE:
            case DELETE:
                // 删除缓存，让下次查询时重新从数据库加载
                Boolean deleted = stringRedisTemplate.delete(cacheKey);
                refreshGeoCache(eventType, beforeData, afterData);
                log.info("Canal 监听到 tb_shop 表 {} 事件，已删除缓存 key={}，结果={}",
                        eventType, cacheKey, deleted);
                break;
            case INSERT:
                deleted = stringRedisTemplate.delete(cacheKey);
                refreshGeoCache(eventType, beforeData, afterData);
                log.info("Canal 监听到 tb_shop 表 INSERT 事件，已删除缓存 key={}，结果={}，并同步 GEO 缓存",
                        cacheKey, deleted);
                break;
            default:
                log.debug("Canal 监听到 tb_shop 表 {} 事件，不处理", eventType);
                break;
        }
    }

    private void refreshGeoCache(CanalEntry.EventType eventType, Map<String, String> beforeData, Map<String, String> afterData) {
        if (eventType == CanalEntry.EventType.DELETE || eventType == CanalEntry.EventType.UPDATE) {
            removeGeo(beforeData);
        }
        if (eventType == CanalEntry.EventType.INSERT || eventType == CanalEntry.EventType.UPDATE) {
            addGeo(afterData);
        }
    }

    private void removeGeo(Map<String, String> rowData) {
        String id = rowData.get("id");
        String typeId = rowData.get("type_id");
        if (id == null || typeId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(RedisConstants.SHOP_GEO_KEY + typeId, id);
    }

    private void addGeo(Map<String, String> rowData) {
        String id = rowData.get("id");
        String typeId = rowData.get("type_id");
        String x = rowData.get("x");
        String y = rowData.get("y");
        if (id == null || typeId == null || x == null || y == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().add(RedisConstants.SHOP_GEO_KEY + typeId,
                new Point(Double.parseDouble(x), Double.parseDouble(y)), id);
    }
}
