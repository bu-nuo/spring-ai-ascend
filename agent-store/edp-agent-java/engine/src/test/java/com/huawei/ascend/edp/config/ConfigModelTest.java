package com.huawei.ascend.edp.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置模型类单元测试（ScenarioConfig, ScenarioScopeConfig, ScenarioSkillRouting, ScenarioArchitectureConfig）。
 *
 * 验证阶段 2 架构包结构生产化中的场景解耦模型类。
 */
class ConfigModelTest {

    // ── ScenarioConfig ──

    @Test
    void testScenarioConfig_SetAndGet() {
        ScenarioConfig config = new ScenarioConfig();
        config.setName("测试场景");
        config.setDescription("测试描述");
        assertEquals("测试场景", config.getName());
        assertEquals("测试描述", config.getDescription());
    }

    @Test
    void testScenarioConfig_Scope() {
        ScenarioConfig config = new ScenarioConfig();
        ScenarioScopeConfig scope = new ScenarioScopeConfig();
        scope.setAllowed(List.of("业务A", "业务B"));
        scope.setDenied(List.of("业务C"));
        config.setScope(scope);
        assertEquals(2, config.getScope().getAllowed().size());
        assertEquals(1, config.getScope().getDenied().size());
    }

    @Test
    void testScenarioConfig_TodolistSteps() {
        ScenarioConfig config = new ScenarioConfig();
        EdpConfig.TodolistStep step = new EdpConfig.TodolistStep();
        step.setStepId(1);
        step.setContent("步骤1");
        step.setSkill("skill1");
        config.setTodolistSteps(List.of(step));
        assertEquals(1, config.getTodolistSteps().size());
        assertEquals(1, config.getTodolistSteps().get(0).getStepId());
    }

    @Test
    void testScenarioConfig_SkillRouting() {
        ScenarioConfig config = new ScenarioConfig();
        ScenarioSkillRouting routing = new ScenarioSkillRouting();
        routing.setTrigger("用户请求推荐");
        routing.setSkill("product_recommend_skill");
        routing.setPriority(1);
        config.setSkillRouting(List.of(routing));
        assertEquals(1, config.getSkillRouting().size());
        assertEquals("product_recommend_skill", config.getSkillRouting().get(0).getSkill());
    }

    @Test
    void testScenarioConfig_Architecture() {
        ScenarioConfig config = new ScenarioConfig();
        ScenarioArchitectureConfig arch = new ScenarioArchitectureConfig();
        arch.setType("mcp_first");
        arch.setDescription("MCP 先行架构");
        config.setArchitecture(arch);
        assertEquals("mcp_first", config.getArchitecture().getType());
    }

    // ── ScenarioScopeConfig ──

    @Test
    void testScenarioScopeConfig_Defaults() {
        ScenarioScopeConfig scope = new ScenarioScopeConfig();
        assertNull(scope.getAllowed(), "默认 allowed 应为 null");
        assertNull(scope.getDenied(), "默认 denied 应为 null");
    }

    // ── ScenarioSkillRouting ──

    @Test
    void testScenarioSkillRouting_Fields() {
        ScenarioSkillRouting routing = new ScenarioSkillRouting();
        routing.setTrigger("触发条件");
        routing.setSkill("目标Skill");
        routing.setPriority(3);
        assertEquals("触发条件", routing.getTrigger());
        assertEquals("目标Skill", routing.getSkill());
        assertEquals(3, routing.getPriority());
    }

    // ── ScenarioArchitectureConfig ──

    @Test
    void testScenarioArchitectureConfig_Fields() {
        ScenarioArchitectureConfig arch = new ScenarioArchitectureConfig();
        arch.setType("mcp_first");
        arch.setDescription("描述");
        assertNotNull(arch.getType());
    }

    @Test
    void testScenarioArchitectureConfig_Steps() {
        ScenarioArchitectureConfig arch = new ScenarioArchitectureConfig();
        ScenarioArchitectureConfig.ArchitectureStep step = new ScenarioArchitectureConfig.ArchitectureStep();
        step.setStepId(1);
        step.setDescription("第一步");
        step.setTool("call_mcp");
        arch.setSteps(List.of(step));
        assertEquals(1, arch.getSteps().size());
        assertEquals("call_mcp", arch.getSteps().get(0).getTool());
    }

    @Test
    void testScenarioArchitectureConfig_ApplicableSkills() {
        ScenarioArchitectureConfig arch = new ScenarioArchitectureConfig();
        arch.setApplicableSkills(List.of("interact_finance_rec_skill"));
        arch.setNotApplicableSkills(List.of("product_recommend_skill"));
        assertEquals(1, arch.getApplicableSkills().size());
        assertEquals(1, arch.getNotApplicableSkills().size());
    }

    // ── ScenarioDiscoveryConfig ──

    @Test
    void testScenarioDiscoveryConfig_Fields() {
        ScenarioDiscoveryConfig discovery = new ScenarioDiscoveryConfig();
        discovery.setBasePath("scenarios");
        discovery.setActiveScenario("wealth-demo");
        assertEquals("scenarios", discovery.getBasePath());
        assertEquals("wealth-demo", discovery.getActiveScenario());
    }

    // ── EdpConfig.TodolistStep ──

    @Test
    void testTodolistStep_Fields() {
        EdpConfig.TodolistStep step = new EdpConfig.TodolistStep();
        step.setStepId(2);
        step.setContent("交互式理财筛选");
        step.setSkill("interact_finance_rec_skill");
        step.setDependsOn(List.of(1));
        assertEquals(2, step.getStepId());
        assertEquals("交互式理财筛选", step.getContent());
        assertEquals("interact_finance_rec_skill", step.getSkill());
        assertEquals(List.of(1), step.getDependsOn());
    }

    // ── EdpConfig.QueryPattern ──

    @Test
    void testQueryPattern_Fields() {
        EdpConfig.QueryPattern pattern = new EdpConfig.QueryPattern();
        pattern.setKeywords(List.of("推荐", "理财"));
        pattern.setScripts(List.of("正在搜索理财产品..."));
        assertEquals(2, pattern.getKeywords().size());
        assertEquals(1, pattern.getScripts().size());
    }

    // ── EdpAgentConfig.EnvOverrides ──

    @Test
    void testEnvOverrides_Fields() {
        EdpAgentConfig.EnvOverrides overrides = new EdpAgentConfig.EnvOverrides();
        overrides.setApiKey("test-key");
        overrides.setModelProvider("OpenAI");
        overrides.setModelName("deepseek-v4");
        overrides.setModelBaseUrl("https://api.test.com");
        overrides.setVersatileUrl("http://localhost:30001");
        assertEquals("test-key", overrides.getApiKey());
        assertEquals("OpenAI", overrides.getModelProvider());
        assertEquals("deepseek-v4", overrides.getModelName());
        assertEquals("https://api.test.com", overrides.getModelBaseUrl());
        assertEquals("http://localhost:30001", overrides.getVersatileUrl());
    }

    @Test
    void testEnvOverrides_DefaultNull() {
        EdpAgentConfig.EnvOverrides overrides = new EdpAgentConfig.EnvOverrides();
        assertNull(overrides.getApiKey(), "默认 apiKey 应为 null");
        assertNull(overrides.getModelProvider(), "默认 modelProvider 应为 null");
        assertNull(overrides.getModelName(), "默认 modelName 应为 null");
        assertNull(overrides.getModelBaseUrl(), "默认 modelBaseUrl 应为 null");
        assertNull(overrides.getVersatileUrl(), "默认 versatileUrl 应为 null");
    }
}
