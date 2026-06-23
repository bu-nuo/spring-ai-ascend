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

    private static final String BASE_PROMPT = """
## 六、技能与工具补充

### 6.1 可用工具

- call_mcp：通用脚本调用，通过 script_command 指定脚本路径、script_params 传入业务参数 JSON
- call_versatile：通用业务工作流调用，通过 workflow_id 指定工作流、params 传入业务参数
- ask_user：在关键信息缺失或敏感操作确认时向用户追问
- lite_todo_write：管理待办清单（覆盖式写入），用于多步任务规划与进度展示

### 6.2 工具调用架构

工具调用架构由当前场景配置定义。LLM 应：
1. 启动时从场景配置中读取 architecture 字段
2. 按照 architecture 中定义的调用模式和步骤执行工具调用
3. 若场景配置未指定 architecture，使用默认的单一工具调用模式
""";

    /**
     * 按场景动态拼接系统提示词。
     *
     * @param scenario 场景配置，null 时返回基础提示词
     * @return 拼接后的完整系统提示词
     */
    public static String buildSystemPrompt(ScenarioConfig scenario) {
        if (scenario == null) {
            return BASE_PROMPT;
        }

        StringBuilder sb = new StringBuilder(BASE_PROMPT);
        sb.append("\n\n## 七、场景规则\n");
        sb.append("### 7.1 场景：").append(scenario.getName()).append("\n");

        if (scenario.getDescription() != null) {
            sb.append(scenario.getDescription()).append("\n");
        }

        // 业务范围
        ScenarioScopeConfig scope = scenario.getScope();
        if (scope != null) {
            if (scope.getAllowed() != null && !scope.getAllowed().isEmpty()) {
                sb.append("\n**允许业务**：").append(joinList(scope.getAllowed())).append("\n");
            }
            if (scope.getDenied() != null && !scope.getDenied().isEmpty()) {
                sb.append("**禁止业务**：").append(joinList(scope.getDenied())).append("\n");
            }
        }

        // Todolist 步骤
        List<EdpConfig.TodolistStep> todolistSteps = scenario.getTodolistSteps();
        if (todolistSteps != null && !todolistSteps.isEmpty()) {
            sb.append("\n### 7.2 任务规划\n");
            for (EdpConfig.TodolistStep step : todolistSteps) {
                sb.append("- step_id=").append(step.getStepId())
                  .append("：").append(step.getContent())
                  .append("（skill=").append(step.getSkill()).append("）\n");
            }
        }

        // Skill 路由
        List<ScenarioSkillRouting> routing = scenario.getSkillRouting();
        if (routing != null && !routing.isEmpty()) {
            sb.append("\n### 7.3 Skill 路由\n");
            for (ScenarioSkillRouting r : routing) {
                sb.append("- ").append(r.getTrigger())
                  .append(" → ").append(r.getSkill())
                  .append("（priority=").append(r.getPriority()).append("）\n");
            }
        }

        // 工具调用架构
        ScenarioArchitectureConfig arch = scenario.getArchitecture();
        if (arch != null && arch.getType() != null) {
            sb.append("\n### 7.4 工具调用架构\n");
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
