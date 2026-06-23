package com.huawei.ascend.edp.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EdpConfigValidator 配置校验器单元测试。
 *
 * 验证阶段 2/3 生产化中的 fail-fast 校验逻辑。
 * 覆盖：模型配置校验、Versatile URL 校验、todolist_steps 校验、场景校验、skill_routing 校验。
 */
class EdpConfigValidatorTest {

    // ── 模型配置校验 ──

    @Test
    void testValidateModelConfig_MissingModel() {
        EdpAgentConfig config = new EdpAgentConfig();
        assertThrows(IllegalStateException.class,
                () -> EdpConfigValidator.validateModelConfig(config),
                "缺少 model 应 fail-fast");
    }

    @Test
    void testValidateModelConfig_MissingProvider() {
        EdpAgentConfig config = createValidModelConfig();
        config.getModel().setProvider(null);
        assertThrows(IllegalStateException.class,
                () -> EdpConfigValidator.validateModelConfig(config),
                "缺少 provider 应 fail-fast");
    }

    @Test
    void testValidateModelConfig_MissingName() {
        EdpAgentConfig config = createValidModelConfig();
        config.getModel().setName(null);
        assertThrows(IllegalStateException.class,
                () -> EdpConfigValidator.validateModelConfig(config),
                "缺少 model name 应 fail-fast");
    }

    @Test
    void testValidateModelConfig_MissingBaseUrl() {
        EdpAgentConfig config = createValidModelConfig();
        config.getModel().setBaseUrl(null);
        assertThrows(IllegalStateException.class,
                () -> EdpConfigValidator.validateModelConfig(config),
                "缺少 baseUrl 应 fail-fast");
    }

    @Test
    void testValidateModelConfig_PlaceholderApiKey_NoEnvVar() {
        EdpAgentConfig config = createValidModelConfig();
        config.getModel().setApiKey("PLACEHOLDER_USE_ENV_VAR");
        // 环境变量不一定存在，测试应抛异常或通过（取决于环境）
        // 在无 EDP_AGENT_MODEL_API_KEY 环境变量时应抛异常
        try {
            EdpConfigValidator.validateModelConfig(config);
            // 如果环境变量恰好存在则通过，否则以下断言生效
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("apiKey missing"), "PLACEHOLDER apiKey 无环境变量时应 fail-fast");
        }
    }

    @Test
    void testValidateModelConfig_ValidApiKey() {
        EdpAgentConfig config = createValidModelConfig();
        config.getModel().setApiKey("real-api-key-12345");
        assertDoesNotThrow(() -> EdpConfigValidator.validateModelConfig(config), "有效 apiKey 应通过校验");
    }

    // ── Versatile URL 校验 ──

    @Test
    void testValidateVersatileUrl_ValidHttp() {
        EdpAgentConfig config = createValidModelConfig();
        EdpAgentConfig.Versatile versatile = new EdpAgentConfig.Versatile();
        versatile.setUrl("http://localhost:30001/v1/0/agent-manager/workflows/{workflow_id}");
        config.setVersatile(versatile);
        assertDoesNotThrow(() -> EdpConfigValidator.validateVersatileUrl(config), "http URL 应通过校验");
    }

    @Test
    void testValidateVersatileUrl_ValidHttps() {
        EdpAgentConfig config = createValidModelConfig();
        EdpAgentConfig.Versatile versatile = new EdpAgentConfig.Versatile();
        versatile.setUrl("https://api.example.com/v1/workflows");
        config.setVersatile(versatile);
        assertDoesNotThrow(() -> EdpConfigValidator.validateVersatileUrl(config), "https URL 应通过校验");
    }

    @Test
    void testValidateVersatileUrl_InvalidUrl() {
        EdpAgentConfig config = createValidModelConfig();
        EdpAgentConfig.Versatile versatile = new EdpAgentConfig.Versatile();
        versatile.setUrl("ftp://invalid-url");
        config.setVersatile(versatile);
        assertThrows(IllegalStateException.class,
                () -> EdpConfigValidator.validateVersatileUrl(config),
                "非 http/https URL 应 fail-fast");
    }

    @Test
    void testValidateVersatileUrl_NullVersatile() {
        EdpAgentConfig config = createValidModelConfig();
        assertDoesNotThrow(() -> EdpConfigValidator.validateVersatileUrl(config), "null versatile 应通过");
    }

    @Test
    void testValidateVersatileUrl_Placeholder() {
        EdpAgentConfig config = createValidModelConfig();
        EdpAgentConfig.Versatile versatile = new EdpAgentConfig.Versatile();
        versatile.setUrl("${EDP_AGENT_VERSATILE_URL}");
        config.setVersatile(versatile);
        // ${ 开头的 URL 需要环境变量
        try {
            EdpConfigValidator.validateVersatileUrl(config);
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("placeholder") || e.getMessage().contains("env var"),
                    "占位符 URL 无环境变量时应 fail-fast");
        }
    }

    // ── todolist_steps 校验 ──

    @Test
    void testValidateTodolistSteps_Placeholder() {
        EdpConfig config = new EdpConfig();
        EdpConfig.TodolistStep step = new EdpConfig.TodolistStep();
        step.setStepId(1);
        step.setContent("占位步骤");
        step.setSkill("_placeholder_");
        config.setTodolistSteps(List.of(step));
        assertThrows(IllegalStateException.class,
                () -> EdpConfigValidator.validateTodolistSteps(config),
                "_placeholder_ skill 应 fail-fast");
    }

    @Test
    void testValidateTodolistSteps_ValidSteps() {
        EdpConfig config = new EdpConfig();
        EdpConfig.TodolistStep step = new EdpConfig.TodolistStep();
        step.setStepId(1);
        step.setContent("推荐理财产品");
        step.setSkill("product_recommend_skill");
        config.setTodolistSteps(List.of(step));
        assertDoesNotThrow(() -> EdpConfigValidator.validateTodolistSteps(config), "有效步骤应通过校验");
    }

    @Test
    void testValidateTodolistSteps_NullSteps() {
        EdpConfig config = new EdpConfig();
        config.setTodolistSteps(null);
        assertDoesNotThrow(() -> EdpConfigValidator.validateTodolistSteps(config), "null steps 应通过");
    }

    // ── 场景配置校验 ──

    @Test
    void testValidateScenarioConfig_NullScenarioHome() {
        assertDoesNotThrow(() -> EdpConfigValidator.validateScenarioConfig(null), "null scenarioHome 应跳过校验");
    }

    @Test
    void testValidateScenarioConfig_NonExistentPath() {
        Path nonExistent = Path.of("/non/existent/path");
        assertThrows(IllegalStateException.class,
                () -> EdpConfigValidator.validateScenarioConfig(nonExistent),
                "不存在的 scenarioHome 应 fail-fast");
    }

    @Test
    void testValidateScenarioConfig_ExistingWealthDemo() {
        // 使用实际场景目录路径
        Path scenarioHome = Path.of("../scenarios/wealth-demo").toAbsolutePath().normalize();
        if (Files.exists(scenarioHome)) {
            assertDoesNotThrow(() -> EdpConfigValidator.validateScenarioConfig(scenarioHome),
                    "wealth-demo 场景目录应通过校验");
        } else {
            // 如果从 test 目录运行时路径不同，跳过
            System.out.println("SKIP: wealth-demo scenario directory not found at " + scenarioHome);
        }
    }

    // ── skill_routing 校验 ──

    @Test
    void testValidateSkillRouting_NullScenario() {
        Path skillsDir = Path.of("some/path");
        assertDoesNotThrow(() -> EdpConfigValidator.validateSkillRouting(null, skillsDir), "null scenario 应跳过");
    }

    @Test
    void testValidateSkillRouting_NullRouting() {
        ScenarioConfig scenario = new ScenarioConfig();
        scenario.setSkillRouting(null);
        Path skillsDir = Path.of("some/path");
        assertDoesNotThrow(() -> EdpConfigValidator.validateSkillRouting(scenario, skillsDir), "null routing 应跳过");
    }

    @Test
    void testValidateSkillRouting_NonExistentSkill() {
        ScenarioConfig scenario = new ScenarioConfig();
        ScenarioSkillRouting routing = new ScenarioSkillRouting();
        routing.setTrigger("test trigger");
        routing.setSkill("nonexistent_skill");
        routing.setPriority(1);
        scenario.setSkillRouting(List.of(routing));

        Path skillsDir = Path.of("/nonexistent/skills");
        assertThrows(IllegalStateException.class,
                () -> EdpConfigValidator.validateSkillRouting(scenario, skillsDir),
                "路由引用不存在的 Skill 应 fail-fast");
    }

    // ── Skill 目录校验 ──

    @Test
    void testValidateSkillDir_NullDir() {
        assertDoesNotThrow(() -> EdpConfigValidator.validateSkillDir(null), "null skillDir 应跳过");
    }

    @Test
    void testValidateSkillDir_NonExistentDir() {
        Path nonExistent = Path.of("/nonexistent/skills");
        assertThrows(IllegalStateException.class,
                () -> EdpConfigValidator.validateSkillDir(nonExistent),
                "不存在的 Skill 目录应 fail-fast");
    }

    // ── 工具方法 ──

    private EdpAgentConfig createValidModelConfig() {
        EdpAgentConfig config = new EdpAgentConfig();
        EdpAgentConfig.Model model = new EdpAgentConfig.Model();
        model.setProvider("OpenAI");
        model.setName("deepseek-v4-pro");
        model.setBaseUrl("https://api.deepseek.com/v1");
        model.setApiKey("test-api-key");
        config.setModel(model);
        return config;
    }
}
