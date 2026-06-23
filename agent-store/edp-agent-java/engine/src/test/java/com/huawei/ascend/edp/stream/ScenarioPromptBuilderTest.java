package com.huawei.ascend.edp.stream;

import com.huawei.ascend.edp.config.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ScenarioPromptBuilder 场景提示词拼接单元测试。
 *
 * 验证阶段 2 架构包结构生产化中的场景级提示词动态拼接。
 */
class ScenarioPromptBuilderTest {

    @Test
    void testBuildSystemPrompt_NullScenario() {
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(null);
        assertNotNull(prompt, "null scenario 应返回基础提示词");
        assertTrue(prompt.contains("六、技能与工具补充"), "基础提示词应包含工具章节");
    }

    @Test
    void testBuildSystemPrompt_WithScenario() {
        ScenarioConfig scenario = createWealthDemoScenario();
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertNotNull(prompt);
        assertTrue(prompt.contains("理财购买"), "提示词应包含场景名称");
        assertTrue(prompt.contains("七、场景规则"), "提示词应包含场景规则章节");
    }

    @Test
    void testBuildSystemPrompt_Scope() {
        ScenarioConfig scenario = createWealthDemoScenario();
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.contains("允许业务"), "提示词应包含允许业务");
        assertTrue(prompt.contains("禁止业务"), "提示词应包含禁止业务");
        assertTrue(prompt.contains("理财产品推荐"), "提示词应包含具体允许业务内容");
    }

    @Test
    void testBuildSystemPrompt_TodolistSteps() {
        ScenarioConfig scenario = createWealthDemoScenario();
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.contains("7.2 任务规划"), "提示词应包含任务规划章节");
        assertTrue(prompt.contains("step_id=1"), "提示词应包含步骤 ID");
        assertTrue(prompt.contains("product_recommend_skill"), "提示词应包含 Skill 名称");
    }

    @Test
    void testBuildSystemPrompt_SkillRouting() {
        ScenarioConfig scenario = createWealthDemoScenario();
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.contains("7.3 Skill 路由"), "提示词应包含 Skill 路由章节");
        assertTrue(prompt.contains("priority=1"), "提示词应包含路由优先级");
    }

    @Test
    void testBuildSystemPrompt_Architecture() {
        ScenarioConfig scenario = createWealthDemoScenario();
        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.contains("7.4 工具调用架构"), "提示词应包含工具调用架构章节");
        assertTrue(prompt.contains("mcp_first"), "提示词应包含架构类型");
    }

    @Test
    void testBuildSystemPrompt_ScopeOnlyAllowed() {
        ScenarioConfig scenario = new ScenarioConfig();
        scenario.setName("简单场景");
        ScenarioScopeConfig scope = new ScenarioScopeConfig();
        scope.setAllowed(List.of("业务A"));
        scenario.setScope(scope);

        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertTrue(prompt.contains("允许业务"), "只有 allowed 也应显示");
        assertFalse(prompt.contains("禁止业务"), "无 denied 不应显示禁止业务");
    }

    @Test
    void testBuildSystemPrompt_NoScope() {
        ScenarioConfig scenario = new ScenarioConfig();
        scenario.setName("无范围场景");

        String prompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        assertFalse(prompt.contains("允许业务"), "无 scope 不应显示范围");
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

        ScenarioArchitectureConfig arch = new ScenarioArchitectureConfig();
        arch.setType("mcp_first");
        arch.setDescription("本场景采用 MCP 先行架构");
        scenario.setArchitecture(arch);

        return scenario;
    }
}
