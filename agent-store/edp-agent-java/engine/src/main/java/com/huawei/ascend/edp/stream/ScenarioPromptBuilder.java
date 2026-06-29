package com.huawei.ascend.edp.stream;

import com.huawei.ascend.edp.config.ScenarioConfig;
import com.huawei.ascend.edp.config.ScenarioSkillRouting;
import com.huawei.ascend.edp.config.ScenarioScopeConfig;
import com.huawei.ascend.edp.config.ScenarioArchitectureConfig;
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

        sb.append("**当前场景**：").append(scenario.getName()).append("\n");

        if (scenario.getDescription() != null) {
            sb.append(scenario.getDescription()).append("\n");
        }

        // 业务范围
        ScenarioScopeConfig scope = scenario.getScope();
        if (scope != null) {
            if (scope.getAllowed() != null && !scope.getAllowed().isEmpty()) {
                sb.append("**允许业务**：").append(joinList(scope.getAllowed())).append("\n");
            }
            if (scope.getDenied() != null && !scope.getDenied().isEmpty()) {
                sb.append("**禁止业务**：").append(joinList(scope.getDenied())).append("\n");
            }
        }

        // Todolist 步骤
        List<EdpConfig.TodolistStep> todolistSteps = scenario.getTodolistSteps();
        if (todolistSteps != null && !todolistSteps.isEmpty()) {
            sb.append("\n**任务规划**：\n");
            for (EdpConfig.TodolistStep step : todolistSteps) {
                sb.append("- step_id=").append(step.getStepId())
                  .append("：").append(step.getContent())
                  .append("（skill=").append(step.getSkill()).append("）\n");
            }
        }

        // Skill 路由
        List<ScenarioSkillRouting> routing = scenario.getSkillRouting();
        if (routing != null && !routing.isEmpty()) {
            sb.append("\n**Skill 路由**：\n");
            for (ScenarioSkillRouting r : routing) {
                sb.append("- ").append(r.getTrigger())
                  .append(" → ").append(r.getSkill())
                  .append("（priority=").append(r.getPriority()).append("）\n");
            }
        }

        // 工具调用架构
        ScenarioArchitectureConfig arch = scenario.getArchitecture();
        if (arch != null && arch.getType() != null) {
            sb.append("\n**工具调用架构**：\n");
            sb.append("类型：").append(arch.getType()).append("\n");
            if (arch.getDescription() != null) {
                sb.append(arch.getDescription()).append("\n");
            }
        }

        return sb.toString();
    }

    private static String joinList(List<String> items) {
        return items.stream().collect(Collectors.joining("、"));
    }
}
