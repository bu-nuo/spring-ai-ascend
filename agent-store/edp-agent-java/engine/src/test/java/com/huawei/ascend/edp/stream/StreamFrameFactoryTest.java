package com.huawei.ascend.edp.stream;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamFrameFactory 流式帧工厂单元测试。
 *
 * 验证阶段 2 架构包结构生产化中的 stream 包。
 */
class StreamFrameFactoryTest {

    @Test
    void testTextFrame() {
        String content = "Hello World";
        assertEquals(content, StreamFrameFactory.textFrame(content), "textFrame 应返回原内容");
    }

    @Test
    void testTextFrame_Null() {
        assertNull(StreamFrameFactory.textFrame(null), "null 输入应返回 null");
    }

    @Test
    void testThinkFrame_ShortContent() {
        String content = "short";
        assertEquals(content, StreamFrameFactory.thinkFrame(content, 10), "短内容应完整返回");
    }

    @Test
    void testThinkFrame_LongContent() {
        String content = "This is a very long thinking content that should be truncated";
        String result = StreamFrameFactory.thinkFrame(content, 10);
        assertEquals(content.substring(0, 10), result, "长内容应截断到 charsPerFrame");
        assertTrue(result.length() <= 10, "截断后长度不应超过 charsPerFrame");
    }

    @Test
    void testThinkFrame_Null() {
        assertNull(StreamFrameFactory.thinkFrame(null, 10), "null 输入应返回 null");
    }

    @Test
    void testThinkFrame_ExactLength() {
        String content = "12345";
        assertEquals(content, StreamFrameFactory.thinkFrame(content, 5), "恰好等于 charsPerFrame 应完整返回");
    }
}
