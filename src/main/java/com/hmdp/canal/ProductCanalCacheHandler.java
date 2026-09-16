package com.hmdp.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.hmdp.utils.ProductCache;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ProductCanalCacheHandler implements CanalCacheHandler {
    private final ProductCache cache;
    public ProductCanalCacheHandler(ProductCache cache) { this.cache = cache; }
    @Override public String getTableName() { return "tb_product"; }
    @Override public void handleChange(CanalEntry.EventType type, Map<String, String> row) {
        if (type == CanalEntry.EventType.INSERT || type == CanalEntry.EventType.UPDATE
                || type == CanalEntry.EventType.DELETE) {
            if (row.get("id") == null) throw new IllegalArgumentException("商品变更缺少 id");
            cache.invalidate(Long.valueOf(row.get("id")));
        }
    }
}
