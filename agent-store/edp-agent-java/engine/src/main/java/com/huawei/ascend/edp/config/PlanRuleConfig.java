package com.huawei.ascend.edp.config;

/**
 * planrule.yaml 配置模型。
 *
 * <p>定位：定义Agent的角色定位、职责边界和行为约束</p>
 * <p>作用：</p>
 * <ul>
 *     <li>回答Agent是谁（角色定义）</li>
 *     <li>回答Agent负责什么（职责范围）</li>
 *     <li>回答Agent边界是什么（允许/禁止的业务范围）</li>
 *     <li>定义Agent的行为约束规则</li>
 * </ul>
 */
public class PlanRuleConfig {

    /** Agent角色。 */
    private String role;

    /** Agent描述。 */
    private String description;

    /** 场景名称（仅场景级配置，框架默认无值）。仅在 scenario 模式下注入系统提示词。 */
    private String scenarioName;

    /** 场景描述（仅场景级配置，框架默认无值）。仅在 scenario 模式下注入系统提示词。 */
    private String scenarioDescription;

    /** Agent职责边界配置。 */
    private Scope scope;

    /** 补充提示词（行为约束规则）。 */
    private String supplementaryPrompt;

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public String getScenarioDescription() { return scenarioDescription; }
    public void setScenarioDescription(String scenarioDescription) { this.scenarioDescription = scenarioDescription; }

    public Scope getScope() { return scope; }
    public void setScope(Scope scope) { this.scope = scope; }

    public String getSupplementaryPrompt() { return supplementaryPrompt; }
    public void setSupplementaryPrompt(String supplementaryPrompt) { this.supplementaryPrompt = supplementaryPrompt; }

    /**
     * Agent职责边界配置。
     */
    public static class Scope {
        /** 允许的业务范围列表（替代式覆盖）。 */
        private String allowed;

        /** 禁止的业务范围列表（替代式覆盖）。 */
        private String denied;

        /** 超出业务范围时的提示消息（替代式覆盖）。 */
        private String outOfScopeMessage;

        public String getAllowed() { return allowed; }
        public void setAllowed(String allowed) { this.allowed = allowed; }

        public String getDenied() { return denied; }
        public void setDenied(String denied) { this.denied = denied; }

        public String getOutOfScopeMessage() { return outOfScopeMessage; }
        public void setOutOfScopeMessage(String outOfScopeMessage) { this.outOfScopeMessage = outOfScopeMessage; }
    }
}