package com.huawei.ascend.edp.config;

import java.util.List;

/**
 * 场景配置模型（AgentRule 与 Skill 解耦的核心）。
 *
 * 对齐 Python 解耦版 agent_rule.py ScenarioConfig。
 * 从 scenario-config.yaml 直接解析（纯 YAML，无需 frontmatter）。
 */
public class ScenarioConfig {
    /** 场景名称，例如 "理财购买"。 */
    private String name;

    /** 场景描述。 */
    private String description;

    /** 业务范围配置（结构化 allowed/denied）。 */
    private ScenarioScopeConfig scope;

    /** Todolist 业务步骤目录（按场景差异化）。 */
    private List<EdpConfig.TodolistStep> todolistSteps;

    /** Skill 路由规则（trigger→skill→priority）。 */
    private List<ScenarioSkillRouting> skillRouting;

    /** 工具调用架构配置（mcp_first 等）。 */
    private ScenarioArchitectureConfig architecture;

    /** 固定话术关键词匹配（场景级覆盖系统级）。 */
    private List<EdpConfig.QueryPattern> queryPatterns;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ScenarioScopeConfig getScope() { return scope; }
    public void setScope(ScenarioScopeConfig scope) { this.scope = scope; }

    public List<EdpConfig.TodolistStep> getTodolistSteps() { return todolistSteps; }
    public void setTodolistSteps(List<EdpConfig.TodolistStep> todolistSteps) { this.todolistSteps = todolistSteps; }

    public List<ScenarioSkillRouting> getSkillRouting() { return skillRouting; }
    public void setSkillRouting(List<ScenarioSkillRouting> skillRouting) { this.skillRouting = skillRouting; }

    public ScenarioArchitectureConfig getArchitecture() { return architecture; }
    public void setArchitecture(ScenarioArchitectureConfig architecture) { this.architecture = architecture; }

    public List<EdpConfig.QueryPattern> getQueryPatterns() { return queryPatterns; }
    public void setQueryPatterns(List<EdpConfig.QueryPattern> queryPatterns) { this.queryPatterns = queryPatterns; }
}
