package com.huawei.ascend.edp.stream;

import com.huawei.ascend.edp.config.PlanRuleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlanrulePromptBuilder测试类。
 *
 * <p>测试目标：验证planrule四字段拼接逻辑的正确性</p>
 * <p>测试覆盖：</p>
 * <ul>
 *     <li>完整配置拼接测试</li>
 *     <li>部分字段缺失测试</li>
 *     <li>null配置降级测试</li>
 *     <li>空字符串字段测试</li>
 *     <li>Python版markdown_body格式对齐测试</li>
 *     <li>多行supplementaryPrompt测试</li>
 * </ul>
 */
class PlanrulePromptBuilderTest {

    /**
     * 测试用例1：完整配置拼接测试。
     *
     * <p>验证planrule四字段完整拼接逻辑</p>
     */
    @Test
    void testBuildSystemPromptFragmentWithFullConfig() {
        PlanRuleConfig planrule = new PlanRuleConfig();
        planrule.setRole("通用动态规划智能体角色定位");
        planrule.setDescription("负责任务规划、执行和结果总结的智能助手");

        PlanRuleConfig.Scope scope = new PlanRuleConfig.Scope();
        scope.setAllowed("理财产品推荐、筛选、购买");
        scope.setDenied("股票交易、期货交易");
        scope.setOutOfScopeMessage("尚在学习中，暂不支持该业务");
        planrule.setScope(scope);

        planrule.setSupplementaryPrompt("## 二、行为约束\n\n行为约束规则：\n1. 当用户表达修改意图，暂停当前任务，重新规划");

        String result = PlanrulePromptBuilder.buildSystemPromptFragment(planrule);

        // 验证拼接结果包含所有字段
        assertTrue(result.contains("# 通用动态规划智能体角色定位"));
        assertTrue(result.contains("负责任务规划、执行和结果总结的智能助手"));
        assertTrue(result.contains("## 一、业务范围"));
        assertTrue(result.contains("**当前支持的业务**：理财产品推荐、筛选、购买"));
        assertTrue(result.contains("**禁止的业务**：股票交易、期货交易"));
        assertTrue(result.contains("超出范围提示：尚在学习中，暂不支持该业务"));
        // supplementaryPrompt直接拼接，不加固定标题
        assertTrue(result.contains("## 二、行为约束\n\n行为约束规则：\n1. 当用户表达修改意图，暂停当前任务，重新规划"));
    }

    /**
     * 测试用例2：部分字段缺失测试。
     *
     * <p>验证planrule部分字段缺失时的降级处理</p>
     */
    @Test
    void testBuildSystemPromptFragmentWithPartialConfig() {
        PlanRuleConfig planrule = new PlanRuleConfig();
        planrule.setRole("理财推荐智能体");
        planrule.setDescription(null);  // description缺失

        PlanRuleConfig.Scope scope = new PlanRuleConfig.Scope();
        scope.setAllowed("理财产品推荐");
        scope.setDenied(null);  // denied缺失
        scope.setOutOfScopeMessage(null);  // outOfScopeMessage缺失
        planrule.setScope(scope);

        planrule.setSupplementaryPrompt(null);  // supplementaryPrompt缺失

        String result = PlanrulePromptBuilder.buildSystemPromptFragment(planrule);

        // 验证拼接结果只包含存在的字段
        assertTrue(result.contains("# 理财推荐智能体"));
        assertFalse(result.contains("负责任务规划"));  // description缺失，不应包含
        assertTrue(result.contains("## 一、业务范围"));
        assertTrue(result.contains("**当前支持的业务**：理财产品推荐"));
        assertFalse(result.contains("**禁止的业务**"));  // denied缺失，不应包含
        assertFalse(result.contains("超出范围提示"));  // outOfScopeMessage缺失，不应包含
        // supplementaryPrompt缺失，不应包含任何补充内容
        assertFalse(result.contains("行为约束"));  // supplementaryPrompt缺失，不应包含
    }

    /**
     * 测试用例3：null配置降级测试。
     *
     * <p>验证planrule配置为null时的默认提示词返回</p>
     */
    @Test
    void testBuildSystemPromptFragmentWithNullConfig() {
        PlanRuleConfig planrule = null;

        String result = PlanrulePromptBuilder.buildSystemPromptFragment(planrule);

        // 验证返回默认提示词
        assertEquals("# 通用动态规划智能体\n\n你是一个智能助手，负责任务规划、执行和结果总结。", result);
    }

    /**
     * 测试用例4：空字符串字段测试。
     *
     * <p>验证planrule字段为空字符串时的跳过处理</p>
     */
    @Test
    void testBuildSystemPromptFragmentWithEmptyStrings() {
        PlanRuleConfig planrule = new PlanRuleConfig();
        planrule.setRole("  ");  // 空字符串（只有空格）
        planrule.setDescription("");  // 空字符串

        PlanRuleConfig.Scope scope = new PlanRuleConfig.Scope();
        scope.setAllowed(" ");  // 空格字符串（默认配置标识）
        scope.setDenied("");  // 空字符串
        scope.setOutOfScopeMessage("  ");  // 空格字符串
        planrule.setScope(scope);

        planrule.setSupplementaryPrompt("");  // 空字符串

        String result = PlanrulePromptBuilder.buildSystemPromptFragment(planrule);

        // 验证空字符串字段都被跳过，返回空字符串
        assertTrue(result.isEmpty());
    }

    /**
     * 测试用例5：默认配置加载测试。
     *
     * <p>验证governance配置文件加载后的拼接结果（对齐planrule.yaml默认配置）</p>
     */
    @Test
    void testBuildSystemPromptFragmentWithDefaultGovernanceConfig() {
        PlanRuleConfig planrule = new PlanRuleConfig();
        planrule.setRole("通用动态规划智能体角色定位");
        planrule.setDescription("负责任务规划、执行和结果总结的智能助手");

        PlanRuleConfig.Scope scope = new PlanRuleConfig.Scope();
        scope.setAllowed(" ");  // 默认配置：空格字符串（无业务范围限制）
        scope.setDenied(" ");  // 默认配置：空格字符串（无禁止业务）
        scope.setOutOfScopeMessage("尚在学习中，暂不支持该业务");
        planrule.setScope(scope);

        // supplementaryPrompt内容灵活，可以是行为约束、使用说明、注意事项等
        planrule.setSupplementaryPrompt("## 二、行为约束\n\n行为约束：\n1. 当用户表达修改意图，暂停当前任务，重新规划\n2. 当遇到以下情况，**调用 `ask_user` 工具**暂停执行，等待用户补充：\n- 关键参数缺失\n- 敏感操作需用户确认\n- 用户输入有歧义");

        String result = PlanrulePromptBuilder.buildSystemPromptFragment(planrule);

        // 验证拼接结果符合planrule.yaml默认配置
        assertTrue(result.contains("# 通用动态规划智能体角色定位"));
        assertTrue(result.contains("负责任务规划、执行和结果总结的智能助手"));
        assertTrue(result.contains("## 一、业务范围"));
        assertFalse(result.contains("**当前支持的业务**"));  // allowed为" "，应该被跳过
        assertFalse(result.contains("**禁止的业务**"));  // denied为" "，应该被跳过
        assertTrue(result.contains("超出范围提示：尚在学习中，暂不支持该业务"));
        // supplementaryPrompt直接拼接（包含自己的章节标题）
        assertTrue(result.contains("## 二、行为约束\n\n行为约束："));
        assertTrue(result.contains("关键参数缺失"));
        assertTrue(result.contains("敏感操作需用户确认"));
    }

    /**
     * 测试用例6：多行supplementaryPrompt测试。
     *
     * <p>验证多行supplementaryPrompt的正确拼接（内容灵活，可以是行为约束、使用说明等）</p>
     */
    @Test
    void testBuildSystemPromptFragmentWithMultilineSupplementaryPrompt() {
        PlanRuleConfig planrule = new PlanRuleConfig();
        // supplementaryPrompt内容灵活，这里测试"使用说明"示例（不加"## 二、行为约束"固定标题）
        planrule.setSupplementaryPrompt(
            "## 三、使用说明\n\n" +
            "工具使用规则：\n" +
            "1. 当用户表达修改意图，暂停当前任务，重新规划\n" +
            "2. 当遇到以下情况，调用 ask_user 工具暂停执行：\n" +
            "   - 关键参数缺失\n" +
            "   - 敏感操作需用户确认"
        );

        String result = PlanrulePromptBuilder.buildSystemPromptFragment(planrule);

        // 验证多行supplementaryPrompt直接拼接（不加固定标题）
        assertTrue(result.contains("## 三、使用说明\n\n"));  // supplementaryPrompt自己的章节标题
        assertTrue(result.contains("工具使用规则："));
        assertTrue(result.contains("1. 当用户表达修改意图，暂停当前任务，重新规划"));
        assertTrue(result.contains("2. 当遇到以下情况，调用 ask_user 工具暂停执行："));
        assertTrue(result.contains("- 关键参数缺失"));
        assertTrue(result.contains("- 敏感操作需用户确认"));
    }

    /**
     * 测试用例7：与Python版markdown_body格式对齐测试。
     *
     * <p>验证Java版拼接结果与Python版markdown_body格式对齐</p>
     */
    @Test
    void testAlignmentWithPythonMarkdownBodyFormat() {
        PlanRuleConfig planrule = new PlanRuleConfig();
        planrule.setRole("EDP 动态规划智能体");
        planrule.setDescription("你是一名企业级动态规划智能体，使用思考—规划—执行—观察—反思循环处理用户请求");

        PlanRuleConfig.Scope scope = new PlanRuleConfig.Scope();
        scope.setAllowed("理财产品推荐、筛选、购买");
        scope.setDenied("股票交易、期货交易");
        scope.setOutOfScopeMessage("正在学习中，暂不支持该业务");
        planrule.setScope(scope);

        // supplementaryPrompt内容灵活，这里使用"行为约束"示例（包含自己的章节标题）
        planrule.setSupplementaryPrompt("## 二、行为约束\n\n行为约束：\n1. 工具执行失败时，在 thought 中记录原因");

        String result = PlanrulePromptBuilder.buildSystemPromptFragment(planrule);

        // 验证格式对齐Python版markdown_body
        // 标题格式：# [role]
        assertTrue(result.startsWith("# EDP 动态规划智能体"));

        // 章节编号格式：## 一、业务范围（scope字段固定章节标题）
        assertTrue(result.contains("## 一、业务范围"));

        // supplementaryPrompt直接拼接，可以是任意章节标题（这里包含自己的"## 二、行为约束"）
        assertTrue(result.contains("## 二、行为约束\n\n行为约束："));

        // 业务范围格式：**当前支持的业务**：
        assertTrue(result.contains("**当前支持的业务**：理财产品推荐、筛选、购买"));
        assertTrue(result.contains("**禁止的业务**：股票交易、期货交易"));
        assertTrue(result.contains("超出范围提示：正在学习中，暂不支持该业务"));
    }
}