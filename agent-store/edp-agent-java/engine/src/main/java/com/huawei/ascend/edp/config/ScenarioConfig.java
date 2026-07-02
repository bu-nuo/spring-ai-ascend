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

    /** Skill 路由规则（trigger→skill→priority）。 */
    private List<ScenarioSkillRouting> skillRouting;

    /** 固定话术关键词匹配（场景级覆盖系统级）。 */
    private List<QueryPattern> queryPatterns;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ScenarioScopeConfig getScope() { return scope; }
    public void setScope(ScenarioScopeConfig scope) { this.scope = scope; }

    public List<ScenarioSkillRouting> getSkillRouting() { return skillRouting; }
    public void setSkillRouting(List<ScenarioSkillRouting> skillRouting) { this.skillRouting = skillRouting; }

    public List<QueryPattern> getQueryPatterns() { return queryPatterns; }
    public void setQueryPatterns(List<QueryPattern> queryPatterns) { this.queryPatterns = queryPatterns; }

    /**
     * 用户 query 关键词到脚本的匹配配置。
     */
    public static class QueryPattern {
        /** 关键词列表。 */
        private List<String> keywords;

        /** 匹配关键词后使用的脚本列表。 */
        private List<String> scripts;

        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }

        public List<String> getScripts() { return scripts; }
        public void setScripts(List<String> scripts) { this.scripts = scripts; }
    }
}
