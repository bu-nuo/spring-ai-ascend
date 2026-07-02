package com.huawei.ascend.edp.stream;

import com.huawei.ascend.edp.config.ScenarioConfig;
import com.huawei.ascend.edp.config.ScenarioSkillRouting;
import com.huawei.ascend.edp.config.ScenarioScopeConfig;
import com.huawei.ascend.edp.config.EdpConfig;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 按场景动态拼接系统提示词。
 *
 * 对齐 Python 解耦版 prompt.py build_system_prompt(scenario)。
 * 无 scenario 时返回框架级基础提示词（向后兼容）。
 * 有 scenario 时拼接：业务范围、todolist 步骤、Skill 路由、工具调用架构。
 */
public class ScenarioPromptBuilder {

    /**
     * 按场景动态拼接系统提示词。
     *
     * <p>提示词内容完全来自 scenario-config.yaml，不再硬编码工具清单。</p>
     *
     * @param scenario 场景配置，null 时返回空字符串
     * @return 拼接后的场景系统提示词
     */
    public static String buildSystemPrompt(ScenarioConfig scenario) {
        if (scenario == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // name 和 description 已迁移至 PlanrulePromptBuilder（从 governance/planrule.yaml 的 scenarioName/scenarioDescription 读取）
        // sb.append("**当前场景**：").append(scenario.getName()).append("\n");
        // if (scenario.getDescription() != null) {
        //     sb.append(scenario.getDescription()).append("\n");
        // }

        // scope 已迁移至 PlanrulePromptBuilder（从 governance/planrule.yaml 的 scope 读取）
        // ScenarioScopeConfig scope = scenario.getScope();
        // if (scope != null) {
        //     if (scope.getAllowed() != null && !scope.getAllowed().isEmpty()) {
        //         sb.append("**允许业务**：").append(joinList(scope.getAllowed())).append("\n");
        //     }
        //     if (scope.getDenied() != null && !scope.getDenied().isEmpty()) {
        //         sb.append("**禁止业务**：").append(joinList(scope.getDenied())).append("\n");
        //     }
        // }

        // Todo 步骤已由 EdpaTodoRail.init() 通过 addPromptBuilderSection 动态注入，
        // 此处不再拼接，避免 LLM 看到重复的任务清单。

        // Skill 路由已迁移至 PlanrulePromptBuilder（从 governance/planrule.yaml 的 skill_routing 读取）
        // 工具调用架构已迁移至框架默认 supplementary_prompt（MCP 先行架构）

        return sb.toString();
    }

    private static String joinList(List<String> items) {
        return items.stream().collect(Collectors.joining("、"));
    }
}
