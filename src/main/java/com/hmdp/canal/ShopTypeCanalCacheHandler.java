package com.hmdp.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

@Slf4j
@Component
public class ShopTypeCanalCacheHandler implements CanalCacheHandler {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String getTableName() {
        return "tb_shop_type";
    }

    @Override
    public void handleChange(CanalEntry.EventType eventType, Map<String, String> rowData) {
        if (eventType == CanalEntry.EventType.UPDATE
                || eventType == CanalEntry.EventType.DELETE
                || eventType == CanalEntry.EventType.INSERT) {
            Boolean deleted = stringRedisTemplate.delete(RedisConstants.CACHE_SHOPTYPE_KEY);
            log.info("Canal 监听到 tb_shop_type 表 {} 事件，已删除缓存 key={}，结果={}",
                    eventType, RedisConstants.CACHE_SHOPTYPE_KEY, deleted);
        }
    }
}
