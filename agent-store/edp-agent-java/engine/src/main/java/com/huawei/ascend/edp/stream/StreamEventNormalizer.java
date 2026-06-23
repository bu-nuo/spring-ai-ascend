package com.huawei.ascend.edp.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流式事件归一化器。
 *
 * 对齐 agent-runtime A2A 流式事件输出格式。
 */
public class StreamEventNormalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamEventNormalizer.class);

    /**
     * 归一化工具调用结果为 A2A artifactUpdate 格式。
     */
    public static String normalizeToolResult(String toolName, String result) {
        return result;
    }

    /**
     * 归一化错误为 A2A 流式事件格式。
     */
    public static String normalizeError(String toolName, String error) {
        LOGGER.warn("Stream error from {}: {}", toolName, error);
        return "[error] " + toolName + ": " + error;
    }
}
