package com.huawei.ascend.edp.stream;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FixedScriptFeeder 固定思考脚本输出器单元测试。
 *
 * 验证阶段 2 架构包结构生产化中的 stream 包。
 */
class FixedScriptFeederTest {

    @Test
    void testSelectScripts_NullQuery() {
        List<String> defaults = List.of("正在分析...");
        FixedScriptFeeder feeder = new FixedScriptFeeder(defaults, null);
        assertEquals(defaults, feeder.selectScripts(null), "null query 应返回默认脚本");
    }

    @Test
    void testSelectScripts_BlankQuery() {
        List<String> defaults = List.of("正在分析...");
        FixedScriptFeeder feeder = new FixedScriptFeeder(defaults, null);
        assertEquals(defaults, feeder.selectScripts("  "), "blank query 应返回默认脚本");
    }

    @Test
    void testSelectScripts_MatchKeyword() {
        List<String> defaults = List.of("正在分析...");
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("keywords", List.of("推荐", "理财"));
        pattern.put("scripts", List.of("正在搜索理财产品..."));

        FixedScriptFeeder feeder = new FixedScriptFeeder(defaults, List.of(pattern));
        List<String> result = feeder.selectScripts("帮我推荐理财产品");
        assertEquals(List.of("正在搜索理财产品..."), result, "匹配关键词应返回对应脚本");
    }

    @Test
    void testSelectScripts_NoMatchKeyword() {
        List<String> defaults = List.of("正在分析...");
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("keywords", List.of("推荐", "理财"));
        pattern.put("scripts", List.of("正在搜索理财产品..."));

        FixedScriptFeeder feeder = new FixedScriptFeeder(defaults, List.of(pattern));
        List<String> result = feeder.selectScripts("帮我查余额");
        assertEquals(defaults, result, "无匹配关键词应返回默认脚本");
    }

    @Test
    void testSelectScripts_MultiplePatterns() {
        List<String> defaults = List.of("正在分析...");
        Map<String, Object> pattern1 = new LinkedHashMap<>();
        pattern1.put("keywords", List.of("推荐"));
        pattern1.put("scripts", List.of("正在搜索理财产品..."));
        Map<String, Object> pattern2 = new LinkedHashMap<>();
        pattern2.put("keywords", List.of("购买"));
        pattern2.put("scripts", List.of("正在确认购买信息..."));

        FixedScriptFeeder feeder = new FixedScriptFeeder(defaults, List.of(pattern1, pattern2));
        assertEquals(List.of("正在确认购买信息..."), feeder.selectScripts("我要购买"), "应匹配第二个 pattern");
    }
}
