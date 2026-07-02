package com.huawei.ascend.edp.stream;

import com.huawei.ascend.edp.config.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ScenarioPromptBuilder 场景提示词拼接单元测试。
 *
 * 注意：ScenarioPromptBuilder 已清空（所有字段已迁移至 governance 的 planrule.yaml），
 * buildSystemPrompt 无论输入均返回空字符串。此类待全局清理时一并删除。
 */
class ScenarioPromptBuilderTest {

    @Test
    void testBuildSystemPrompt_NullScenario() {
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(null);
        assertNotNull(prompt, "null scenario 应返回空字符串");
        assertTrue(prompt.isEmpty(), "所有字段已迁移，应返回空字符串");
    }

    @Test
    void testBuildSystemPrompt_WithScenario() {
        ScenarioConfig scenario = createWealthDemoScenario();
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertNotNull(prompt);
        assertTrue(prompt.isEmpty(), "所有字段已迁移至 governance，应返回空字符串");
    }

    @Test
    void testBuildSystemPrompt_Scope() {
        // scope 已迁移至 PlanrulePromptBuilder
        ScenarioConfig scenario = createWealthDemoScenario();
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.isEmpty(), "scope 已迁移，应返回空字符串");
    }

    @Test
    void testBuildSystemPrompt_TodolistSteps() {
        // todolist 已由 EdpaTodoRail 动态注入
        ScenarioConfig scenario = createWealthDemoScenario();
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.isEmpty(), "todolist 已由 Rail 注入，应返回空字符串");
    }

    @Test
    void testBuildSystemPrompt_SkillRouting() {
        // skill_routing 已迁移至 PlanrulePromptBuilder
        ScenarioConfig scenario = createWealthDemoScenario();
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.isEmpty(), "skill_routing 已迁移，应返回空字符串");
    }

    // architecture 已删除——MCP 先行架构已作为框架默认配置放入 engine/src/main/resources/governance/planrule.yaml 的 supplementary_prompt

    @Test
    void testBuildSystemPrompt_ScopeOnlyAllowed() {
        // scope 已迁移至 PlanrulePromptBuilder
        ScenarioConfig scenario = new ScenarioConfig();
        scenario.setName("简单场景");
        ScenarioScopeConfig scope = new ScenarioScopeConfig();
        scope.setAllowed(List.of("业务A"));
        scenario.setScope(scope);
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.isEmpty(), "所有字段已迁移至 governance，应返回空字符串");
    }

    @Test
    void testBuildSystemPrompt_NoScope() {
        // scope 已迁移至 PlanrulePromptBuilder
        ScenarioConfig scenario = new ScenarioConfig();
        scenario.setName("无范围场景");
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.isEmpty(), "所有字段已迁移至 governance，应返回空字符串");
    }

    private ScenarioConfig createWealthDemoScenario() {
        ScenarioConfig scenario = new ScenarioConfig();
        scenario.setName("理财购买");
        scenario.setDescription("理财产品推荐、筛选、购买全流程");

        ScenarioScopeConfig scope = new ScenarioScopeConfig();
        scope.setAllowed(List.of("理财产品推荐、筛选、购买", "银行账户余额查询"));
        scope.setDenied(List.of("基金相关业务", "股票相关业务"));
        scenario.setScope(scope);

        EdpConfig.TodolistStep step1 = new EdpConfig.TodolistStep();
        step1.setStepId(1);
        step1.setContent("推荐理财产品");
        step1.setSkill("product_recommend_skill");
        scenario.setTodolistSteps(List.of(step1));

        ScenarioSkillRouting routing = new ScenarioSkillRouting();
        routing.setTrigger("用户首次请求推荐理财产品");
        routing.setSkill("product_recommend_skill");
        routing.setPriority(1);
        scenario.setSkillRouting(List.of(routing));

        // architecture 已删除——MCP 先行架构已作为框架默认配置放入 framework planrule.yaml

        return scenario;
    }
}
