package com.huawei.ascend.edp.config;

import java.util.List;

/**
 * actrule.yaml 配置模型。
 *
 * <p>定位：定义Agent的任务执行约束与行为边界</p>
 * <p>作用：</p>
 * <ul>
 *     <li>回答Agent如何执行任务</li>
 *     <li>控制执行过程中的重试、重规划、步数限制</li>
 *     <li>约束Agent可调用的工具集合</li>
 * </ul>
 */
public class ActRuleConfig {

    /** 限制单层最大子任务数量。 */
    private Integer maxSubtasks;

    /** 允许失败后重新规划。 */
    private Boolean replanEnabled;

    /** 限制最大重规划次数。 */
    private Integer maxReplanCount;

    /** 限制最大执行步数。 */
    private Integer maxSteps;

    /** 是否允许失败重试。 */
    private Boolean retryEnabled;

    /** 限制最大重试次数。 */
    private Integer maxRetryCount;

    /** 允许调用的工具列表（继承式覆盖）。 */
    private List<String> allowedTools;

    /** 是否启用任务循环（从 framework.options.enableTaskLoop 迁移）。 */
    private Boolean enableTaskLoop;

    public Integer getMaxSubtasks() { return maxSubtasks; }
    public void setMaxSubtasks(Integer maxSubtasks) { this.maxSubtasks = maxSubtasks; }

    public Boolean getReplanEnabled() { return replanEnabled; }
    public void setReplanEnabled(Boolean replanEnabled) { this.replanEnabled = replanEnabled; }

    public Integer getMaxReplanCount() { return maxReplanCount; }
    public void setMaxReplanCount(Integer maxReplanCount) { this.maxReplanCount = maxReplanCount; }

    public Integer getMaxSteps() { return maxSteps; }
    public void setMaxSteps(Integer maxSteps) { this.maxSteps = maxSteps; }

    public Boolean getRetryEnabled() { return retryEnabled; }
    public void setRetryEnabled(Boolean retryEnabled) { this.retryEnabled = retryEnabled; }

    public Integer getMaxRetryCount() { return maxRetryCount; }
    public void setMaxRetryCount(Integer maxRetryCount) { this.maxRetryCount = maxRetryCount; }

    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }

    public Boolean getEnableTaskLoop() { return enableTaskLoop; }
    public void setEnableTaskLoop(Boolean enableTaskLoop) { this.enableTaskLoop = enableTaskLoop; }
}