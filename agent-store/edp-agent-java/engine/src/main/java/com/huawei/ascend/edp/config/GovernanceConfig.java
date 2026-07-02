package com.huawei.ascend.edp.config;

/**
 * Governance配置聚合模型。
 *
 * <p>定位：聚合三个治理域配置（planrule、actrule、scriptconfig）</p>
 * <p>作用：</p>
 * <ul>
 *     <li>统一管理三个治理域配置</li>
 *     <li>提供配置继承覆盖机制</li>
 *     <li>支持场景级配置覆盖框架级配置</li>
 * </ul>
 *
 * <p>继承覆盖规则：</p>
 * <ul>
 *     <li>planrule.scope字段：替代式覆盖（完全覆盖）</li>
 *     <li>planrule.supplementaryPrompt字段：替代式覆盖</li>
 *     <li>actrule.maxSubtasks等参数字段：继承式覆盖（只写差异）</li>
 *     <li>scriptconfig.summary字段：替代式覆盖</li>
 *     <li>scriptconfig.generalScripts字段：替代式覆盖</li>
 * </ul>
 */
public class GovernanceConfig {

    /** 身份域配置（planrule.yaml）。 */
    private PlanRuleConfig planrule;

    /** 规划域+执行域配置（actrule.yaml）。 */
    private ActRuleConfig actrule;

    /** 交互域配置（scriptconfig.yaml）。 */
    private ScriptConfig scriptconfig;

    public PlanRuleConfig getPlanrule() { return planrule; }
    public void setPlanrule(PlanRuleConfig planrule) { this.planrule = planrule; }

    public ActRuleConfig getActrule() { return actrule; }
    public void setActrule(ActRuleConfig actrule) { this.actrule = actrule; }

    public ScriptConfig getScriptconfig() { return scriptconfig; }
    public void setScriptconfig(ScriptConfig scriptconfig) { this.scriptconfig = scriptconfig; }

    /**
     * 合并场景级配置（场景级覆盖框架级）。
     *
     * <p>继承覆盖规则：字段级别的继承覆盖，不是文件级别的完全覆盖。</p>
     *
     * @param scenarioConfig 场景级GovernanceConfig
     */
    public void mergeScenarioConfig(GovernanceConfig scenarioConfig) {
        if (scenarioConfig == null) {
            return;
        }

        // 合并planrule配置
        if (scenarioConfig.getPlanrule() != null) {
            mergePlanrule(scenarioConfig.getPlanrule());
        }

        // 合并actrule配置
        if (scenarioConfig.getActrule() != null) {
            mergeActrule(scenarioConfig.getActrule());
        }

        // 合并scriptconfig配置
        if (scenarioConfig.getScriptconfig() != null) {
            mergeScriptconfig(scenarioConfig.getScriptconfig());
        }
    }

    /**
     * 合并planrule配置（替代式覆盖）。
     */
    private void mergePlanrule(PlanRuleConfig scenarioPlanrule) {
        if (this.planrule == null) {
            this.planrule = scenarioPlanrule;
            return;
        }

        // role: 继承式覆盖（只写差异）
        if (scenarioPlanrule.getRole() != null) {
            this.planrule.setRole(scenarioPlanrule.getRole());
        }

        // description: 继承式覆盖
        if (scenarioPlanrule.getDescription() != null) {
            this.planrule.setDescription(scenarioPlanrule.getDescription());
        }

        // scenarioName: 继承式覆盖（仅场景级配置，框架默认无值）
        if (scenarioPlanrule.getScenarioName() != null) {
            this.planrule.setScenarioName(scenarioPlanrule.getScenarioName());
        }

        // scenarioDescription: 继承式覆盖（仅场景级配置，框架默认无值）
        if (scenarioPlanrule.getScenarioDescription() != null) {
            this.planrule.setScenarioDescription(scenarioPlanrule.getScenarioDescription());
        }

        // scope: 替代式覆盖（完全覆盖）
        if (scenarioPlanrule.getScope() != null) {
            this.planrule.setScope(scenarioPlanrule.getScope());
        }

        // supplementaryPrompt: 替代式覆盖
        if (scenarioPlanrule.getSupplementaryPrompt() != null) {
            this.planrule.setSupplementaryPrompt(scenarioPlanrule.getSupplementaryPrompt());
        }
    }

    /**
     * 合并actrule配置（继承式覆盖）。
     */
    private void mergeActrule(ActRuleConfig scenarioActrule) {
        if (this.actrule == null) {
            this.actrule = scenarioActrule;
            return;
        }

        // 继承式覆盖：只写差异，未覆盖字段自动继承Default值
        if (scenarioActrule.getMaxSubtasks() != null) {
            this.actrule.setMaxSubtasks(scenarioActrule.getMaxSubtasks());
        }
        if (scenarioActrule.getReplanEnabled() != null) {
            this.actrule.setReplanEnabled(scenarioActrule.getReplanEnabled());
        }
        if (scenarioActrule.getMaxReplanCount() != null) {
            this.actrule.setMaxReplanCount(scenarioActrule.getMaxReplanCount());
        }
        if (scenarioActrule.getMaxSteps() != null) {
            this.actrule.setMaxSteps(scenarioActrule.getMaxSteps());
        }
        if (scenarioActrule.getRetryEnabled() != null) {
            this.actrule.setRetryEnabled(scenarioActrule.getRetryEnabled());
        }
        if (scenarioActrule.getMaxRetryCount() != null) {
            this.actrule.setMaxRetryCount(scenarioActrule.getMaxRetryCount());
        }
        if (scenarioActrule.getAllowedTools() != null) {
            // 叠加合并：框架工具 + 场景扩展工具，去重但保持顺序
            java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(
                    this.actrule.getAllowedTools() != null ? this.actrule.getAllowedTools() : java.util.List.of());
            merged.addAll(scenarioActrule.getAllowedTools());
            this.actrule.setAllowedTools(new java.util.ArrayList<>(merged));
        }
        if (scenarioActrule.getEnableTaskLoop() != null) {
            this.actrule.setEnableTaskLoop(scenarioActrule.getEnableTaskLoop());
        }
        if (scenarioActrule.getSkillMode() != null) {
            this.actrule.setSkillMode(scenarioActrule.getSkillMode());
        }
        if (scenarioActrule.getToolLimits() != null) {
            this.actrule.setToolLimits(scenarioActrule.getToolLimits());
        }
    }

    /**
     * 合成scriptconfig配置（替代式覆盖）。
     */
    private void mergeScriptconfig(ScriptConfig scenarioScriptconfig) {
        if (this.scriptconfig == null) {
            this.scriptconfig = scenarioScriptconfig;
            return;
        }

        // generalScripts: 替代式覆盖
        if (scenarioScriptconfig.getGeneralScripts() != null) {
            this.scriptconfig.setGeneralScripts(scenarioScriptconfig.getGeneralScripts());
        }

        // thinkChunkScripts: 继承式覆盖
        if (scenarioScriptconfig.getThinkChunkScripts() != null) {
            mergeThinkChunkScripts(scenarioScriptconfig.getThinkChunkScripts());
        }

        // summary: 替代式覆盖
        if (scenarioScriptconfig.getSummary() != null) {
            this.scriptconfig.setSummary(scenarioScriptconfig.getSummary());
        }
    }

    /**
     * 合并thinkChunkScripts配置（继承式覆盖）。
     */
    private void mergeThinkChunkScripts(ScriptConfig.ThinkChunkScripts scenarioThinkChunk) {
        if (this.scriptconfig.getThinkChunkScripts() == null) {
            this.scriptconfig.setThinkChunkScripts(scenarioThinkChunk);
            return;
        }

        ScriptConfig.ThinkChunkScripts defaultThinkChunk = this.scriptconfig.getThinkChunkScripts();

        if (scenarioThinkChunk.getThinkChunkMode() != null) {
            defaultThinkChunk.setThinkChunkMode(scenarioThinkChunk.getThinkChunkMode());
        }
        if (scenarioThinkChunk.getThinkChunkFixedScripts() != null) {
            defaultThinkChunk.setThinkChunkFixedScripts(scenarioThinkChunk.getThinkChunkFixedScripts());
        }
    }
}