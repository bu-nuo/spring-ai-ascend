package com.huawei.ascend.edp.config;

import java.util.List;

/**
 * 工具调用架构配置。
 *
 * 对齐 Python 解耦版 agent_rule.py ScenarioArchitectureConfig。
 */
public class ScenarioArchitectureConfig {
    /** 架构类型，例如 "mcp_first"。 */
    private String type;

    /** 架构描述。 */
    private String description;

    /** 调用步骤列表。 */
    private List<ArchitectureStep> steps;

    /** 适用该架构的 Skill 列表。 */
    private List<String> applicableSkills;

    /** 不适用该架构的 Skill 列表。 */
    private List<String> notApplicableSkills;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<ArchitectureStep> getSteps() { return steps; }
    public void setSteps(List<ArchitectureStep> steps) { this.steps = steps; }

    public List<String> getApplicableSkills() { return applicableSkills; }
    public void setApplicableSkills(List<String> applicableSkills) { this.applicableSkills = applicableSkills; }

    public List<String> getNotApplicableSkills() { return notApplicableSkills; }
    public void setNotApplicableSkills(List<String> notApplicableSkills) { this.notApplicableSkills = notApplicableSkills; }

    /**
     * 架构调用步骤。
     */
    public static class ArchitectureStep {
        private int stepId;
        private String description;
        private String tool;

        public int getStepId() { return stepId; }
        public void setStepId(int stepId) { this.stepId = stepId; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
    }
}
