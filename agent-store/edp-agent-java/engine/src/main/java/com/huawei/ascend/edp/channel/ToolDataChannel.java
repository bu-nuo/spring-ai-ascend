package com.huawei.ascend.edp.channel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 跨工具数据通道。
 *
 * 对齐 Python 解耦版 rail/tool_data_channel.py 的单字段设计（ADR-006）。
 * 外层用 ToolDataKey 四元组隔离（多租户支持），
 * 内层存储改为单字段 dict 结构。
 * 禁止单独维护 key 索引列表。
 */
public class ToolDataChannel {

    private final ConcurrentHashMap<ToolDataKey, ConcurrentHashMap<String, Object>> channel = new ConcurrentHashMap<>();

    /**
     * 存储数据。
     *
     * @param key 四元组隔离键
     * @param resultKey 单字段结果键
     * @param data 数据值
     */
    public void store(ToolDataKey key, String resultKey, Map<String, Object> data) {
        channel.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(resultKey, data);
    }

    /**
     * 获取数据。
     *
     * @param key 四元组隔离键
     * @param resultKey 单字段结果键
     * @return 数据值，null 表示不存在
     */
    public Map<String, Object> get(ToolDataKey key, String resultKey) {
        ConcurrentHashMap<String, Object> scope = channel.get(key);
        return scope != null ? (Map<String, Object>) scope.get(resultKey) : null;
    }

    /**
     * 移除数据。
     */
    public void remove(ToolDataKey key, String resultKey) {
        ConcurrentHashMap<String, Object> scope = channel.get(key);
        if (scope != null) {
            scope.remove(resultKey);
            if (scope.isEmpty()) {
                channel.remove(key);
            }
        }
    }

    /**
     * 清理指定 key 的所有数据。
     */
    public void clear(ToolDataKey key) {
        channel.remove(key);
    }

    /**
     * 清理所有数据。
     */
    public void clearAll() {
        channel.clear();
    }
}
