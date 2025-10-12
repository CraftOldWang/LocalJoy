package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;
    // 最小的时间戳(也就是当前结果里面最早的消息的时间戳)
    private Long minTime;
    // 偏移量查询几条记录(这是用来处理 存在多个时间戳相同的blog 的情况用的)
    private Integer offset;
}
