package com.huawei.ascend.edp.stream;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamEventNormalizer 流式事件归一化器单元测试。
 *
 * 验证阶段 2 架构包结构生产化中的 stream 包。
 */
class StreamEventNormalizerTest {

    @Test
    void testNormalizeToolResult() {
        String result = StreamEventNormalizer.normalizeToolResult("call_mcp", "success");
        assertEquals("success", result, "normalizeToolResult 应返回原始结果");
    }

    @Test
    void testNormalizeError() {
        String result = StreamEventNormalizer.normalizeError("call_mcp", "timeout");
        assertTrue(result.startsWith("[error]"), "错误归一化应以 [error] 开头");
        assertTrue(result.contains("call_mcp"), "错误归一化应包含工具名");
        assertTrue(result.contains("timeout"), "错误归一化应包含错误信息");
    }

    @Test
    void testNormalizeError_Format() {
        String result = StreamEventNormalizer.normalizeError("ask_user", "interrupted");
        assertEquals("[error] ask_user: interrupted", result, "错误格式应为 [error] toolName: errorMsg");
    }
}
