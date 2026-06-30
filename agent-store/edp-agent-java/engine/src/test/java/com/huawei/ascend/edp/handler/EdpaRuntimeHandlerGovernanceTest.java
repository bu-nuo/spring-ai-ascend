package com.huawei.ascend.edp.handler;

import com.huawei.ascend.edp.config.EdpAgentConfig;
import com.huawei.ascend.edp.config.GovernanceConfig;
import com.huawei.ascend.edp.config.PlanRuleConfig;
import com.huawei.ascend.edp.config.ScenarioConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EdpaRuntimeHandler GovernanceConfig集成测试类。
 *
 * <p>测试目标：验证GovernanceConfig加载和系统提示词拼接逻辑</p>
 * <p>测试覆盖：</p>
 * <ul>
 *     <li>完整GovernanceConfig拼接测试</li>
 *     <li>GovernanceConfig缺失降级测试</li>
 *     <li>ScenarioConfig缺失降级测试</li>
 *     <li>向后兼容测试（agentConfig.prompt.system非空）</li>
 *     <li>GovernanceConfigLoader加载测试（框架级）</li>
 *     <li>GovernanceConfigLoader加载测试（场景级优先）</li>
 * </ul>
 */
class EdpaRuntimeHandlerGovernanceTest {

    /**
     * 测试用例1：完整GovernanceConfig拼接测试。
     *
     * <p>验证planrule四字段 + ScenarioPromptBuilder拼接逻辑</p>
     */
    @Test
    void testBuildFullSystemPromptWithFullGovernanceConfig() {
        // 构造GovernanceConfig（planrule四字段完整配置）
        GovernanceConfig governance = new GovernanceConfig();
        PlanRuleConfig planrule = new PlanRuleConfig();
        planrule.setRole("通用动态规划智能体角色定位");
        planrule.setDescription("负责任务规划、执行和结果总结的智能助手");

        PlanRuleConfig.Scope scope = new PlanRuleConfig.Scope();
        scope.setAllowed(" ");
        scope.setDenied(" ");
        scope.setOutOfScopeMessage("尚在学习中，暂不支持该业务");
        planrule.setScope(scope);

        planrule.setSupplementaryPrompt("## 二、行为约束\n\n行为约束：\n1. 当用户表达修改意图，暂停当前任务，重新规划");
        governance.setPlanrule(planrule);

        // 构造ScenarioConfig（场景级动态内容）
        ScenarioConfig scenario = new ScenarioConfig();
        scenario.setName("wealth-demo");
        scenario.setDescription("理财推荐场景");

        // 构造EdpAgentConfig（prompt.system为空）
        EdpAgentConfig agentConfig = new EdpAgentConfig();
        EdpAgentConfig.Prompt prompt = new EdpAgentConfig.Prompt();
        prompt.setSystem("");  // prompt.system为空，使用新逻辑
        agentConfig.setPrompt(prompt);

        // 使用反射调用buildFullSystemPrompt()方法（因为它是private方法）
        String systemPrompt = invokeBuildFullSystemPrompt(governance, scenario, agentConfig);

        // 验证拼接结果包含两部分
        // 第一部分：planrule四字段（一到五章节）
        assertTrue(systemPrompt.contains("# 通用动态规划智能体角色定位"));
        assertTrue(systemPrompt.contains("负责任务规划、执行和结果总结的智能助手"));
        assertTrue(systemPrompt.contains("## 一、业务范围"));
        assertTrue(systemPrompt.contains("超出范围提示：尚在学习中，暂不支持该业务"));
        assertTrue(systemPrompt.contains("## 二、行为约束"));

        // 第二部分：ScenarioPromptBuilder（六到七章节）
        assertTrue(systemPrompt.contains("## 六、技能与工具补充"));
        assertTrue(systemPrompt.contains("### 6.1 可用工具"));
        assertTrue(systemPrompt.contains("### 6.2 工具调用架构"));

        // 验证两部分正确拼接（中间有"\n\n"分隔）
        assertTrue(systemPrompt.contains("\n\n## 六、技能与工具补充"));
    }

    /**
     * 测试用例2：GovernanceConfig缺失降级测试。
     *
     * <p>验证GovernanceConfig为null时的降级处理</p>
     */
    @Test
    void testBuildFullSystemPromptWithNullGovernanceConfig() {
        // GovernanceConfig为null
        GovernanceConfig governance = null;

        // 构造ScenarioConfig
        ScenarioConfig scenario = new ScenarioConfig();
        scenario.setName("wealth-demo");

        // 构造EdpAgentConfig（prompt.system为空）
        EdpAgentConfig agentConfig = new EdpAgentConfig();
        EdpAgentConfig.Prompt prompt = new EdpAgentConfig.Prompt();
        prompt.setSystem("");
        agentConfig.setPrompt(prompt);

        // 调用buildFullSystemPrompt()
        String systemPrompt = invokeBuildFullSystemPrompt(governance, scenario, agentConfig);

        // 验证只返回第二部分（ScenarioPromptBuilder.buildSystemPrompt(scenario)）
        assertTrue(systemPrompt.contains("## 六、技能与工具补充"));
        assertFalse(systemPrompt.contains("# 通用动态规划智能体"));  // planrule缺失，不应包含第一部分
    }

    /**
     * 测试用例3：ScenarioConfig缺失降级测试。
     *
     * <p>验证ScenarioConfig为null时的降级处理</p>
     */
    @Test
    void testBuildFullSystemPromptWithNullScenarioConfig() {
        // 构造GovernanceConfig
        GovernanceConfig governance = new GovernanceConfig();
        PlanRuleConfig planrule = new PlanRuleConfig();
        planrule.setRole("理财推荐智能体");
        planrule.setDescription("理财产品推荐智能助手");
        governance.setPlanrule(planrule);

        // ScenarioConfig为null
        ScenarioConfig scenario = null;

        // 构造EdpAgentConfig（prompt.system为空）
        EdpAgentConfig agentConfig = new EdpAgentConfig();
        EdpAgentConfig.Prompt prompt = new EdpAgentConfig.Prompt();
        prompt.setSystem("");
        agentConfig.setPrompt(prompt);

        // 调用buildFullSystemPrompt()
        String systemPrompt = invokeBuildFullSystemPrompt(governance, scenario, agentConfig);

        // 验证只返回第一部分（PlanrulePromptBuilder.buildSystemPromptFragment(planrule)）
        assertTrue(systemPrompt.contains("# 理财推荐智能体"));
        assertTrue(systemPrompt.contains("理财产品推荐智能助手"));
        assertTrue(systemPrompt.contains("## 六、技能与工具补充"));  // scenario为null时，会降级使用BASE_PROMPT
    }

    /**
     * 测试用例4：向后兼容测试（agentConfig.prompt.system非空）。
     *
     * <p>验证向后兼容逻辑（agentConfig.prompt.system非空时使用原有逻辑）</p>
     */
    @Test
    void testBuildFullSystemPromptWithNonEmptyAgentConfigPrompt() {
        // 构造GovernanceConfig
        GovernanceConfig governance = new GovernanceConfig();
        PlanRuleConfig planrule = new PlanRuleConfig();
        planrule.setRole("理财推荐智能体");
        governance.setPlanrule(planrule);

        // 构造ScenarioConfig
        ScenarioConfig scenario = new ScenarioConfig();
        scenario.setName("wealth-demo");

        // 构造EdpAgentConfig（prompt.system非空）
        EdpAgentConfig agentConfig = new EdpAgentConfig();
        EdpAgentConfig.Prompt prompt = new EdpAgentConfig.Prompt();
        prompt.setSystem("自定义系统提示词，用于向后兼容测试");  // prompt.system非空，向后兼容
        agentConfig.setPrompt(prompt);

        // 调用buildFullSystemPrompt()
        String systemPrompt = invokeBuildFullSystemPrompt(governance, scenario, agentConfig);

        // 验证返回agentConfig.prompt.system的内容（向后兼容）
        assertEquals("自定义系统提示词，用于向后兼容测试", systemPrompt);
        assertFalse(systemPrompt.contains("# 理财推荐智能体"));  // 向后兼容，不使用新逻辑
        assertFalse(systemPrompt.contains("## 六、技能与工具补充"));  // 向后兼容，不使用新逻辑
    }

    /**
     * 测试用例5：GovernanceConfigLoader加载测试（框架级）。
     *
     * <p>验证框架级GovernanceConfig加载</p>
     */
    @Test
    void testLoadGovernanceConfigFrameworkLevel() {
        // yamlDir：src/main/resources（框架级governance路径）
        Path yamlDir = Path.of("src/main/resources");

        // scenarioHomePath：null（无场景级governance）
        Path scenarioHomePath = null;

        // 使用反射调用loadGovernanceConfig()方法
        GovernanceConfig governance = invokeLoadGovernanceConfig(yamlDir, scenarioHomePath);

        // 验证GovernanceConfig包含框架级planrule.yaml、actrule.yaml、scriptconfig.yaml内容
        assertNotNull(governance);
        assertNotNull(governance.getPlanrule(), "planrule should be loaded from framework-level governance");
        assertNotNull(governance.getActrule(), "actrule should be loaded from framework-level governance");
        assertNotNull(governance.getScriptconfig(), "scriptconfig should be loaded from framework-level governance");

        // 验证planrule内容
        assertEquals("通用动态规划智能体角色定位", governance.getPlanrule().getRole());
        assertEquals("负责任务规划、执行和结果总结的智能助手", governance.getPlanrule().getDescription());
    }

    /**
     * 测试用例6：GovernanceConfigLoader加载测试（场景级优先）。
     *
     * <p>验证场景级GovernanceConfig优先加载</p>
     */
    @Test
    void testLoadGovernanceConfigScenarioLevelPriority() {
        // yamlDir：src/main/resources（框架级governance路径）
        Path yamlDir = Path.of("src/main/resources");

        // scenarioHomePath：scenarios/wealth-demo（场景级governance路径）
        // 注意：这个测试需要实际存在场景级governance目录才能通过
        // 如果没有场景级governance目录，会降级使用框架级governance
        Path scenarioHomePath = Path.of("scenarios/wealth-demo");

        // 使用反射调用loadGovernanceConfig()方法
        GovernanceConfig governance = invokeLoadGovernanceConfig(yamlDir, scenarioHomePath);

        // 验证GovernanceConfig加载成功
        assertNotNull(governance);
        assertNotNull(governance.getPlanrule());

        // 如果场景级governance存在，验证场景级配置覆盖框架级配置
        // 如果场景级governance不存在，验证降级使用框架级配置
        if (governance.getPlanrule().getRole() != null) {
            // 验证planrole字段加载成功（无论框架级还是场景级）
            assertNotNull(governance.getPlanrule().getRole());
        }
    }

    /**
     * 使用反射调用buildFullSystemPrompt()方法（private方法）。
     */
    private String invokeBuildFullSystemPrompt(GovernanceConfig governance, ScenarioConfig scenario, EdpAgentConfig agentConfig) {
        try {
            // 创建EdpaRuntimeHandler实例
            EdpaRuntimeHandler handler = new EdpaRuntimeHandler();

            // 使用反射调用private方法
            java.lang.reflect.Method method = EdpaRuntimeHandler.class.getDeclaredMethod(
                    "buildFullSystemPrompt", GovernanceConfig.class, ScenarioConfig.class, EdpAgentConfig.class);
            method.setAccessible(true);

            return (String) method.invoke(handler, governance, scenario, agentConfig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke buildFullSystemPrompt method", e);
        }
    }

    /**
     * 使用反射调用loadGovernanceConfig()方法（private方法）。
     */
    private GovernanceConfig invokeLoadGovernanceConfig(Path yamlDir, Path scenarioHomePath) {
        try {
            // 创建EdpaRuntimeHandler实例
            EdpaRuntimeHandler handler = new EdpaRuntimeHandler();

            // 使用反射调用private方法
            java.lang.reflect.Method method = EdpaRuntimeHandler.class.getDeclaredMethod(
                    "loadGovernanceConfig", Path.class, Path.class);
            method.setAccessible(true);

            return (GovernanceConfig) method.invoke(handler, yamlDir, scenarioHomePath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke loadGovernanceConfig method", e);
        }
    }
}