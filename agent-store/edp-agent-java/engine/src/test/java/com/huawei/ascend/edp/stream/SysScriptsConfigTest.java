package com.huawei.ascend.edp.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SysScriptsConfig 系统话术配置管理器单元测试。
 *
 * 验证阶段 2 架构包结构生产化中的 stream 包。
 */
class SysScriptsConfigTest {

    private SysScriptsConfig config;

    @BeforeEach
    void setUp() {
        config = new SysScriptsConfig();
    }

    @Test
    void testDefaultTemplates() {
        assertNotNull(config.getTemplate("thinking"), "应有默认 thinking 模板");
        assertNotNull(config.getTemplate("out_of_scope"), "应有默认 out_of_scope 模板");
        assertNotNull(config.getTemplate("ask_user_confirm"), "应有默认 ask_user_confirm 模板");
    }

    @Test
    void testGetTemplate_NotExist() {
        assertNull(config.getTemplate("nonexistent"), "不存在的 key 应返回 null");
    }

    @Test
    void testLoadYaml() throws Exception {
        Path temp = Files.createTempFile("sys-scripts", ".yaml");
        Files.writeString(temp, """
                thinking:
                  default: "测试默认话术"
                  scripts:
                    - "测试脚本A"
                    - "测试脚本B"
                ask_user_confirm:
                  default_confirm: "测试确认话术"
                """);

        try {
            config.load(temp.toString());
            assertEquals("测试默认话术", config.getTemplate("thinking"));
            assertEquals("测试确认话术", config.getTemplate("ask_user_confirm"));
            assertEquals("测试脚本A\n测试脚本B", config.getTemplate("thinking.scripts"));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    void testLoadYaml_NotExistNoError() {
        config.load("/nonexistent/SysScriptsConfig.yaml");
        assertNotNull(config.getTemplate("thinking"), "不存在的配置文件不应破坏默认模板");
    }

    @Test
    void testMergeSkillScripts() {
        Map<String, String> skillScripts = new LinkedHashMap<>();
        skillScripts.put("example_success", "示例处理完成");
        skillScripts.put("thinking", "场景级思考覆盖");

        config.mergeSkillScripts(skillScripts);
        assertEquals("场景级思考覆盖", config.getTemplate("thinking"), "场景级应覆盖系统级同名 key");
        assertEquals("示例处理完成", config.getTemplate("example_success"), "新增 key 应可获取");
    }

    @Test
    void testMergeSkillScripts_Null() {
        config.mergeSkillScripts(null);
        assertNotNull(config.getTemplate("thinking"), "null 合并不应破坏现有模板");
    }

    @Test
    void testRender_DoubleBraces() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("item_name", "示例项目");
        vars.put("amount", "10000");

        String template = "您选择了{{item_name}}，金额{{amount}}元";
        String result = config.render(template, vars);
        assertEquals("您选择了示例项目，金额10000元", result, "render 应替换双大括号变量");
    }

    @Test
    void testRender_SingleBraces() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("item_name", "示例项目");
        vars.put("amount", "10000");

        String template = "确认项目：{item_name}，金额 {amount} 元。";
        String result = config.render(template, vars);
        assertEquals("确认项目：示例项目，金额 10000 元。", result, "render 应替换单大括号变量");
    }

    @Test
    void testRender_NullTemplate() {
        assertEquals("", config.render(null, null), "null 模板应返回空字符串");
    }

    @Test
    void testRender_NullVars() {
        String template = "无变量模板{{missing}}";
        assertEquals("无变量模板{{missing}}", config.render(template, null), "null 变量应保留占位符");
    }

    @Test
    void testRender_PartialVars() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("name", "test");
        String template = "{{name}} and {{missing}}";
        String result = config.render(template, vars);
        assertEquals("test and {{missing}}", result, "部分变量替换应保留未匹配的占位符");
    }
}
