package com.huawei.ascend.edp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GovernanceConfigLoader 测试用例。
 *
 * <p>测试范围：</p>
 * <ul>
 *     <li>YAML配置文件加载</li>
 *     <li>字段解析正确性</li>
 *     <li>默认值处理</li>
 *     <li>继承覆盖机制</li>
 *     <li>配置路径优先级</li>
 * </ul>
 */
@DisplayName("Governance配置加载器测试")
class GovernanceConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("测试4.1：场景名称和描述的继承式覆盖（空模板+增量）")
    void testScenarioNameAndDescriptionMerge() throws Exception {
        // 准备框架级配置（不含 scenarioName/scenarioDescription）
        Path frameworkDir = tempDir.resolve("framework-no-scenario");
        Files.createDirectories(frameworkDir);
        createFrameworkConfig(frameworkDir);
        
        // 准备场景级配置（含 scenarioName/scenarioDescription）
        Path scenarioDir = tempDir.resolve("scenario-with-name");
        Files.createDirectories(scenarioDir);
        createScenarioConfig(scenarioDir);
        
        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);
        
        // 验证：场景级 scenarioName 覆盖生效
        assertNotNull(mergedConfig.getPlanrule(), "planrule配置应存在");
        assertEquals("理财购买", mergedConfig.getPlanrule().getScenarioName(),
                "场景级 scenarioName 应覆盖框架级（框架级为null）");
        assertEquals("理财产品推荐、筛选、购买全流程", mergedConfig.getPlanrule().getScenarioDescription(),
                "场景级 scenarioDescription 应覆盖框架级（框架级为null）");
        
        // 验证：框架默认 planrule 不含 scenarioName（确保非场景模式不受影响）
        GovernanceConfig frameworkOnly = GovernanceConfigLoader.load(frameworkDir);
        assertNull(frameworkOnly.getPlanrule().getScenarioName(),
                "框架默认 planrule 应不含 scenarioName");
        assertNull(frameworkOnly.getPlanrule().getScenarioDescription(),
                "框架默认 planrule 应不含 scenarioDescription");
    }

    @Test
    @DisplayName("测试4.2：场景只配名称不配描述时的继承式覆盖")
    void testScenarioNameOnlyWithoutDescription() throws Exception {
        // 准备框架级配置
        Path frameworkDir = tempDir.resolve("framework-name-only");
        Files.createDirectories(frameworkDir);
        createFrameworkConfig(frameworkDir);
        
        // 准备场景级配置（仅 scenarioName，无 scenarioDescription）
        Path scenarioDir = tempDir.resolve("scenario-name-only");
        Files.createDirectories(scenarioDir);
        String planruleYaml = "planrule:\n" +
                "  scenario_name: 杭研智贷通\n";
        Files.writeString(scenarioDir.resolve("planrule.yaml"), planruleYaml);
        createMinimalActruleAndScriptconfig(scenarioDir);
        
        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);
        
        // 验证：scenarioName 生效，scenarioDescription 继承框架（null）
        assertEquals("杭研智贷通", mergedConfig.getPlanrule().getScenarioName(),
                "只配 scenarioName 时应覆盖生效");
        assertNull(mergedConfig.getPlanrule().getScenarioDescription(),
                "未配 scenarioDescription 时应为 null（继承框架默认）");
        
        // 验证：role 仍继承框架
        assertEquals("通用动态规划智能体角色定位", mergedConfig.getPlanrule().getRole(),
                "未覆盖的 role 应继承框架默认");
    }

    @Test
    @DisplayName("测试1：从框架级governance目录加载配置")
    void testLoadFrameworkConfig() throws Exception {
        // 准备测试数据：使用实际的框架级配置
        Path frameworkDir = Path.of("src/main/resources/governance").toAbsolutePath();
        
        // 执行加载
        GovernanceConfig config = GovernanceConfigLoader.load(frameworkDir);
        
        // 验证planrule配置
        assertNotNull(config.getPlanrule(), "planrule配置应存在");
        assertEquals("你的身份是通用动态规划智能体", config.getPlanrule().getRole(), "role字段应正确解析");
        assertNotNull(config.getPlanrule().getScope(), "scope配置应存在");
        
        // 验证actrule配置
        assertNotNull(config.getActrule(), "actrule配置应存在");
        assertEquals(50, config.getActrule().getMaxSubtasks(), "maxSubtasks字段应正确解析");
        assertEquals(100, config.getActrule().getMaxSteps(), "maxSteps字段应正确解析");
        assertNotNull(config.getActrule().getAllowedTools(), "allowedTools列表应存在");
        assertTrue(config.getActrule().getAllowedTools().contains("bash"), "allowedTools应包含bash工具");
        assertEquals("all", config.getActrule().getSkillMode(), "skillMode应从actrule.yaml解析为all");
        
        // 验证scriptconfig配置
        assertNotNull(config.getScriptconfig(), "scriptconfig配置应存在");
        assertNotNull(config.getScriptconfig().getGeneralScripts(), "generalScripts配置应存在");
        assertEquals("正在调用：{tool_name}", config.getScriptconfig().getGeneralScripts().getToolStart(), "toolStart字段应正确解析");
        
        // 验证thinkChunk配置
        assertNotNull(config.getScriptconfig().getThinkChunkScripts(), "thinkChunkScripts配置应存在");
        assertEquals("fixed_script", config.getScriptconfig().getThinkChunkScripts().getThinkChunkMode(), "thinkChunkMode字段应正确解析");
        
        // 验证summary配置
        assertNotNull(config.getScriptconfig().getSummary(), "summary配置应存在");
        assertEquals(500, config.getScriptconfig().getSummary().getMaxLength(), "maxLength字段应正确解析");
    }

    @Test
    @DisplayName("测试2：配置文件不存在时的默认值处理")
    void testLoadNonExistentConfig() {
        // 执行加载（路径不存在）
        Path nonExistentPath = tempDir.resolve("non-existent-governance");
        GovernanceConfig config = GovernanceConfigLoader.load(nonExistentPath);
        
        // 验证：返回空配置对象，不抛异常
        assertNotNull(config, "应返回空的GovernanceConfig对象");
        assertNull(config.getPlanrule(), "planrule应为null");
        assertNull(config.getActrule(), "actrule应为null");
        assertNull(config.getScriptconfig(), "scriptconfig应为null");
    }

    @Test
    @DisplayName("测试3：场景级配置覆盖框架级配置（继承覆盖）")
    void testScenarioConfigOverride() throws Exception {
        // 准备框架级配置（Default）
        Path frameworkDir = tempDir.resolve("framework");
        Files.createDirectories(frameworkDir);
        createFrameworkConfig(frameworkDir);
        
        // 准备场景级配置（Scenario）
        Path scenarioDir = tempDir.resolve("scenario");
        Files.createDirectories(scenarioDir);
        createScenarioConfig(scenarioDir);
        
        // 执行优先级加载：场景级 > 框架级
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);
        
        // 验证继承覆盖规则
        // 1. planrule.role: 继承式覆盖（只写差异）
        assertEquals("理财场景智能助手", mergedConfig.getPlanrule().getRole(), "场景级role应覆盖框架级");
        
        // 2. planrule.scope: 替代式覆盖（完全覆盖）
        assertEquals("理财产品查询、理财产品推荐", mergedConfig.getPlanrule().getScope().getAllowed(), "场景级scope.allowed应完全覆盖");
        assertEquals("基金相关业务", mergedConfig.getPlanrule().getScope().getDenied(), "场景级scope.denied应完全覆盖");
        
        // 3. actrule.maxSubtasks: 继承式覆盖（只写差异）
        assertEquals(30, mergedConfig.getActrule().getMaxSubtasks(), "场景级maxSubtasks应覆盖框架级");
        // 未覆盖的字段应继承框架级默认值
        assertEquals(100, mergedConfig.getActrule().getMaxSteps(), "未覆盖的maxSteps应继承框架级默认值");
        assertEquals("auto_list", mergedConfig.getActrule().getSkillMode(), "场景级skillMode应覆盖框架级all为auto_list");
        
        // 4. scriptconfig.generalScripts: 继承式覆盖
        assertEquals("正在为您查询理财产品...", mergedConfig.getScriptconfig().getGeneralScripts().getToolStart(), "场景级toolStart应覆盖框架级");
        
        // 5. scriptconfig.thinkChunkScripts: 继承式覆盖
        assertEquals("real_stream", mergedConfig.getScriptconfig().getThinkChunkScripts().getThinkChunkMode(), "场景级thinkChunkMode应覆盖框架级");
    }

    @Test
    @DisplayName("测试4：只加载场景级配置（无框架级配置）")
    void testLoadOnlyScenarioConfig() throws Exception {
        // 只准备场景级配置
        Path scenarioDir = tempDir.resolve("scenario-only");
        Files.createDirectories(scenarioDir);
        createScenarioConfig(scenarioDir);
        
        // 框架级路径不存在
        Path frameworkDir = tempDir.resolve("non-existent-framework");
        
        // 执行加载
        GovernanceConfig config = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);
        
        // 验证：场景级配置正常加载，框架级未加载
        assertNotNull(config.getPlanrule(), "planrule配置应存在");
        assertEquals("理财场景智能助手", config.getPlanrule().getRole(), "场景级role应正确解析");
    }

    @Test
    @DisplayName("测试5：字段解析的snake_case映射")
    void testSnakeCaseFieldMapping() throws Exception {
        // 准备测试配置文件
        Path testDir = tempDir.resolve("test-governance");
        Files.createDirectories(testDir);
        
        // 创建actrule.yaml（使用snake_case字段名）
        String actruleYaml = "actrule:\n" +
                "  max_subtasks: 25\n" +
                "  skill_mode: auto_list\n";
        Files.writeString(testDir.resolve("actrule.yaml"), actruleYaml);
        
        // 执行加载
        GovernanceConfig config = GovernanceConfigLoader.load(testDir);
        
        // 验证snake_case字段映射
        assertNotNull(config.getActrule(), "actrule配置应存在");
        assertEquals(25, config.getActrule().getMaxSubtasks(), "max_subtasks应映射到maxSubtasks");
        assertEquals("auto_list", config.getActrule().getSkillMode(), "skill_mode应映射到skillMode");
    }

    @Test
    @DisplayName("测试6：配置加载的日志输出")
    void testConfigLoadLogging() throws Exception {
        // 准备配置
        Path frameworkDir = Path.of("src/main/resources/governance").toAbsolutePath();
        
        // 执行加载（会输出日志）
        GovernanceConfig config = GovernanceConfigLoader.load(frameworkDir);
        
        // 验证：配置已加载（日志可通过查看console输出确认）
        assertNotNull(config, "配置应成功加载");
        assertNotNull(config.getPlanrule(), "planrule应已加载");
        assertNotNull(config.getActrule(), "actrule应已加载");
        assertNotNull(config.getScriptconfig(), "scriptconfig应已加载");
    }

    /**
     * 创建框架级配置文件。
     */
    private void createFrameworkConfig(Path frameworkDir) throws Exception {
        // planrule.yaml
        String planruleYaml = "planrule:\n" +
                "  role: 通用动态规划智能体角色定位\n" +
                "  description: '负责任务规划、执行和结果总结的智能助手'\n" +
                "  scope:\n" +
                "    allowed: ' '\n" +
                "    denied: ' '\n";
        Files.writeString(frameworkDir.resolve("planrule.yaml"), planruleYaml);
        
        // actrule.yaml
        String actruleYaml = "actrule:\n" +
                "  max_subtasks: 50\n" +
                "  max_steps: 100\n" +
                "  skill_mode: all\n" +
                "  allowed_tools:\n" +
                "    - bash\n" +
                "    - skill_tool\n";
        Files.writeString(frameworkDir.resolve("actrule.yaml"), actruleYaml);
        
        // scriptconfig.yaml
        String scriptconfigYaml = "scriptconfig:\n" +
                "  general_scripts:\n" +
                "    tool_start: '正在调用：{tool_name}'\n" +
                "  think_chunk_scripts:\n" +
                "    think_chunk_mode: fixed_script\n";
        Files.writeString(frameworkDir.resolve("scriptconfig.yaml"), scriptconfigYaml);
    }

    /**
     * 创建场景级配置文件（用于测试继承覆盖）。
     */
    private void createScenarioConfig(Path scenarioDir) throws Exception {
        // planrule.yaml（场景级覆盖）
        String planruleYaml = "planrule:\n" +
                "  role: 理财场景智能助手\n" +
                "  scenario_name: 理财购买\n" +
                "  scenario_description: 理财产品推荐、筛选、购买全流程\n" +
                "  scope:\n" +
                "    allowed: '理财产品查询、理财产品推荐'\n" +
                "    denied: '基金相关业务'\n";
        Files.writeString(scenarioDir.resolve("planrule.yaml"), planruleYaml);
        
        // actrule.yaml（场景级覆盖）
        String actruleYaml = "actrule:\n" +
                "  max_subtasks: 30\n" +  // 只覆盖maxSubtasks，其他字段继承框架级
                "  skill_mode: auto_list\n";  // 覆盖skillMode
        Files.writeString(scenarioDir.resolve("actrule.yaml"), actruleYaml);
        
        // scriptconfig.yaml（场景级覆盖）
        String scriptconfigYaml = "scriptconfig:\n" +
                "  general_scripts:\n" +
                "    tool_start: '正在为您查询理财产品...'\n" +
                "  think_chunk_scripts:\n" +
                "    think_chunk_mode: real_stream\n";
        Files.writeString(scenarioDir.resolve("scriptconfig.yaml"), scriptconfigYaml);
    }

    /**
     * 创建最简场景级配置（仅含 actrule 和 scriptconfig 空壳），
     * 用于测试仅 planrule 有差异的场景。
     */
    private void createMinimalActruleAndScriptconfig(Path scenarioDir) throws Exception {
        String actruleYaml = "actrule:\n";
        Files.writeString(scenarioDir.resolve("actrule.yaml"), actruleYaml);
        String scriptconfigYaml = "scriptconfig:\n";
        Files.writeString(scenarioDir.resolve("scriptconfig.yaml"), scriptconfigYaml);
    }

    @Test
    @DisplayName("测试7：skill_routing 的继承式覆盖（框架无值，场景有值）")
    void testSkillRoutingMergeFromScenario() throws Exception {
        // 准备框架级配置（不含 skill_routing）
        Path frameworkDir = tempDir.resolve("framework-sr");
        Files.createDirectories(frameworkDir);
        createFrameworkConfig(frameworkDir);

        // 准备场景级配置（含 skill_routing）
        Path scenarioDir = tempDir.resolve("scenario-sr");
        Files.createDirectories(scenarioDir);
        String planruleWithSkillRouting = "planrule:\n" +
                "  skill_routing:\n" +
                "    - trigger: '用户首次请求推荐理财产品'\n" +
                "      skill: 'product_recommend_skill'\n" +
                "      priority: 1\n" +
                "    - trigger: '用户从推荐结果中选择产品'\n" +
                "      skill: 'product_select_skill'\n" +
                "      priority: 2\n";
        Files.writeString(scenarioDir.resolve("planrule.yaml"), planruleWithSkillRouting);
        createMinimalActruleAndScriptconfig(scenarioDir);

        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        // 验证：场景级 skill_routing 覆盖生效
        assertNotNull(mergedConfig.getPlanrule(), "planrule配置应存在");
        assertNotNull(mergedConfig.getPlanrule().getSkillRouting(), "skillRouting 应存在");
        assertEquals(2, mergedConfig.getPlanrule().getSkillRouting().size(), "应有 2 条路由规则");

        PlanRuleConfig.SkillRoute r1 = mergedConfig.getPlanrule().getSkillRouting().get(0);
        assertEquals("用户首次请求推荐理财产品", r1.getTrigger());
        assertEquals("product_recommend_skill", r1.getSkill());
        assertEquals(1, r1.getPriority());

        PlanRuleConfig.SkillRoute r2 = mergedConfig.getPlanrule().getSkillRouting().get(1);
        assertEquals("用户从推荐结果中选择产品", r2.getTrigger());
        assertEquals("product_select_skill", r2.getSkill());
        assertEquals(2, r2.getPriority());

        // 验证：框架级仍为 null（未受影响）
        GovernanceConfig frameworkOnly = GovernanceConfigLoader.load(frameworkDir);
        assertNull(frameworkOnly.getPlanrule().getSkillRouting(),
                "框架默认 planrule 应不含 skillRouting");
    }

    @Test
    @DisplayName("测试8：skill_routing 仅加载场景级配置（无框架）")
    void testSkillRoutingScenarioOnly() throws Exception {
        Path scenarioDir = tempDir.resolve("scenario-sr-only");
        Files.createDirectories(scenarioDir);
        String planruleYaml = "planrule:\n" +
                "  skill_routing:\n" +
                "    - trigger: '用户确认购买'\n" +
                "      skill: 'fund_planning_skill'\n" +
                "      priority: 1\n";
        Files.writeString(scenarioDir.resolve("planrule.yaml"), planruleYaml);
        createMinimalActruleAndScriptconfig(scenarioDir);

        Path frameworkDir = tempDir.resolve("non-existent-framework-sr");
        GovernanceConfig config = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        assertNotNull(config.getPlanrule().getSkillRouting(), "仅场景配置时 skillRouting 应存在");
        assertEquals(1, config.getPlanrule().getSkillRouting().size());
        assertEquals("用户确认购买", config.getPlanrule().getSkillRouting().get(0).getTrigger());
        assertEquals("fund_planning_skill", config.getPlanrule().getSkillRouting().get(0).getSkill());
        assertEquals(1, config.getPlanrule().getSkillRouting().get(0).getPriority());
    }

    @Test
    @DisplayName("测试9：scope.denied 的追加拼接（并集）策略")
    void testScopeDeniedMergeUnion() throws Exception {
        // 准备框架级配置（含 denied）
        Path frameworkDir = tempDir.resolve("framework-denied");
        Files.createDirectories(frameworkDir);
        String frameworkPlanrule = "planrule:\n" +
                "  scope:\n" +
                "    allowed: '通用业务范围'\n" +
                "    denied: '基金相关业务、股票相关业务'\n";
        Files.writeString(frameworkDir.resolve("planrule.yaml"), frameworkPlanrule);
        createMinimalActruleAndScriptconfig(frameworkDir);

        // 准备场景级配置（含 denied）
        Path scenarioDir = tempDir.resolve("scenario-denied");
        Files.createDirectories(scenarioDir);
        String scenarioPlanrule = "planrule:\n" +
                "  scope:\n" +
                "    allowed: '理财产品推荐、筛选、购买'\n" +
                "    denied: '保险相关业务、贷款相关业务'\n";
        Files.writeString(scenarioDir.resolve("planrule.yaml"), scenarioPlanrule);
        createMinimalActruleAndScriptconfig(scenarioDir);

        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        // 验证：allowed 替代式覆盖
        assertEquals("理财产品推荐、筛选、购买", mergedConfig.getPlanrule().getScope().getAllowed(),
                "allowed 应替代式覆盖");

        // 验证：denied 追加拼接（并集）
        String mergedDenied = mergedConfig.getPlanrule().getScope().getDenied();
        assertTrue(mergedDenied.contains("基金相关业务"), "应保留框架的基金禁止项");
        assertTrue(mergedDenied.contains("股票相关业务"), "应保留框架的股票禁止项");
        assertTrue(mergedDenied.contains("保险相关业务"), "应追加场景的保险禁止项");
        assertTrue(mergedDenied.contains("贷款相关业务"), "应追加场景的贷款禁止项");
        // 验证无重复项
        String[] items = mergedDenied.split("、");
        assertEquals(4, items.length, "并集后应有4个禁止项");
    }

    @Test
    @DisplayName("测试10：supplementary_prompt 的拆分和合并")
    void testSupplementaryPromptSplitMerge() throws Exception {
        // 准备框架级配置（含 base_protocol 和 additional_prompt）
        Path frameworkDir = tempDir.resolve("framework-prompt");
        Files.createDirectories(frameworkDir);
        String frameworkPlanrule = "planrule:\n" +
                "  supplementary_prompt:\n" +
                "    base_protocol: '框架核心协议内容'\n" +
                "    additional_prompt: '框架默认补充说明'\n";
        Files.writeString(frameworkDir.resolve("planrule.yaml"), frameworkPlanrule);
        createMinimalActruleAndScriptconfig(frameworkDir);

        // 准备场景级配置（仅含 additional_prompt）
        Path scenarioDir = tempDir.resolve("scenario-prompt");
        Files.createDirectories(scenarioDir);
        String scenarioPlanrule = "planrule:\n" +
                "  supplementary_prompt:\n" +
                "    additional_prompt: '理财场景特有规则'\n";
        Files.writeString(scenarioDir.resolve("planrule.yaml"), scenarioPlanrule);
        createMinimalActruleAndScriptconfig(scenarioDir);

        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        // 验证：base_protocol 保持框架内置，不可覆盖
        assertEquals("框架核心协议内容", mergedConfig.getPlanrule().getSupplementaryPrompt().getBaseProtocol(),
                "base_protocol 应保持框架内置");

        // 验证：additional_prompt 有序拼接（框架 + 场景）
        String mergedAdditional = mergedConfig.getPlanrule().getSupplementaryPrompt().getAdditionalPrompt();
        assertTrue(mergedAdditional.contains("框架默认补充说明"), "应包含框架的 additional_prompt");
        assertTrue(mergedAdditional.contains("理财场景特有规则"), "应追加场景的 additional_prompt");
        assertTrue(mergedAdditional.contains("\n\n"), "拼接应使用双换行分隔");
    }

    @Test
    @DisplayName("测试11：skill_routing 的叠加合并（框架 + 场景）")
    void testSkillRoutingStackMerge() throws Exception {
        // 准备框架级配置（含 skill_routing）
        Path frameworkDir = tempDir.resolve("framework-routing");
        Files.createDirectories(frameworkDir);
        String frameworkPlanrule = "planrule:\n" +
                "  skill_routing:\n" +
                "    - trigger: '框架通用触发条件'\n" +
                "      skill: 'framework_skill'\n" +
                "      priority: 1\n";
        Files.writeString(frameworkDir.resolve("planrule.yaml"), frameworkPlanrule);
        createMinimalActruleAndScriptconfig(frameworkDir);

        // 准备场景级配置（含 skill_routing）
        Path scenarioDir = tempDir.resolve("scenario-routing");
        Files.createDirectories(scenarioDir);
        String scenarioPlanrule = "planrule:\n" +
                "  skill_routing:\n" +
                "    - trigger: '理财场景触发条件'\n" +
                "      skill: 'wealth_skill'\n" +
                "      priority: 2\n";
        Files.writeString(scenarioDir.resolve("planrule.yaml"), scenarioPlanrule);
        createMinimalActruleAndScriptconfig(scenarioDir);

        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        // 验证：叠加合并（框架路由 + 场景路由）
        assertNotNull(mergedConfig.getPlanrule().getSkillRouting(), "skillRouting 应存在");
        assertEquals(2, mergedConfig.getPlanrule().getSkillRouting().size(), "应有 2 条路由规则（框架1条+场景1条）");

        // 验证顺序：框架在前，场景在后
        PlanRuleConfig.SkillRoute frameworkRoute = mergedConfig.getPlanrule().getSkillRouting().get(0);
        assertEquals("框架通用触发条件", frameworkRoute.getTrigger(), "第一条应为框架路由");
        assertEquals("framework_skill", frameworkRoute.getSkill());

        PlanRuleConfig.SkillRoute scenarioRoute = mergedConfig.getPlanrule().getSkillRouting().get(1);
        assertEquals("理财场景触发条件", scenarioRoute.getTrigger(), "第二条应为场景路由");
        assertEquals("wealth_skill", scenarioRoute.getSkill());
    }

    @Test
    @DisplayName("测试14：actrule.maxSubtasks/maxSteps资源限制min合并")
    void testResourceLimitsMinMerge() throws Exception {
        // 准备框架级配置
        Path frameworkDir = tempDir.resolve("framework-resource");
        Files.createDirectories(frameworkDir);
        String frameworkActrule = "actrule:\n" +
                "  max_subtasks: 50\n" +
                "  max_steps: 100\n";
        Files.writeString(frameworkDir.resolve("actrule.yaml"), frameworkActrule);
        createMinimalActruleAndScriptconfig(frameworkDir);

        // 准备场景级配置（收紧框架上限）
        Path scenarioDir = tempDir.resolve("scenario-resource");
        Files.createDirectories(scenarioDir);
        String scenarioActrule = "actrule:\n" +
                "  max_subtasks: 30\n" +  // 收紧框架上限（30 < 50）
                "  max_steps: 80\n";       // 收紧框架步数上限（80 < 100）
        Files.writeString(scenarioDir.resolve("actrule.yaml"), scenarioActrule);
        createMinimalActruleAndScriptconfig(scenarioDir);

        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        // 验证：资源限制取min（场景不能放宽框架上限）
        assertEquals(30, mergedConfig.getActrule().getMaxSubtasks(), "maxSubtasks应取min（30）");
        assertEquals(80, mergedConfig.getActrule().getMaxSteps(), "maxSteps应取min（80）");
    }

    @Test
    @DisplayName("测试15：actrule.toolLimits逐key合并（取min）")
    void testToolLimitsKeyMerge() throws Exception {
        // 准备框架级配置
        Path frameworkDir = tempDir.resolve("framework-tool");
        Files.createDirectories(frameworkDir);
        String frameworkActrule = "actrule:\n" +
                "  tool_limits:\n" +
                "    call_versatile: 50\n" +
                "    call_mcp: 50\n" +
                "    ask_user: 50\n";
        Files.writeString(frameworkDir.resolve("actrule.yaml"), frameworkActrule);
        createMinimalActruleAndScriptconfig(frameworkDir);

        // 准备场景级配置
        Path scenarioDir = tempDir.resolve("scenario-tool");
        Files.createDirectories(scenarioDir);
        String scenarioActrule = "actrule:\n" +
                "  tool_limits:\n" +
                "    call_versatile: 30\n" +  // 收紧框架限制（30 < 50）
                "    call_mcp: 20\n" +         // 收紧框架限制（20 < 50）
                "    execute_cmd: 10\n";       // 新增工具限制（框架无此限制）
        Files.writeString(scenarioDir.resolve("actrule.yaml"), scenarioActrule);
        createMinimalActruleAndScriptconfig(scenarioDir);

        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        // 验证：逐key合并，取min
        assertNotNull(mergedConfig.getActrule().getToolLimits(), "toolLimits应存在");
        assertEquals(30, mergedConfig.getActrule().getToolLimits().get("call_versatile"), "call_versatile应取min（30）");
        assertEquals(20, mergedConfig.getActrule().getToolLimits().get("call_mcp"), "call_mcp应取min（20）");
        assertEquals(50, mergedConfig.getActrule().getToolLimits().get("ask_user"), "ask_user应保持框架值（50）");
        assertEquals(10, mergedConfig.getActrule().getToolLimits().get("execute_cmd"), "execute_cmd应为新增限制（10）");
        assertEquals(4, mergedConfig.getActrule().getToolLimits().size(), "应有4个工具限制");
    }

    @Test
    @DisplayName("测试16：actrule场景放宽框架上限应被拒绝（验证min策略）")
    void testResourceLimitsCannotRelax() throws Exception {
        // 准备框架级配置
        Path frameworkDir = tempDir.resolve("framework-relax");
        Files.createDirectories(frameworkDir);
        String frameworkActrule = "actrule:\n" +
                "  max_subtasks: 50\n" +
                "  max_steps: 100\n" +
                "  tool_limits:\n" +
                "    call_versatile: 50\n";
        Files.writeString(frameworkDir.resolve("actrule.yaml"), frameworkActrule);
        createMinimalActruleAndScriptconfig(frameworkDir);

        // 准备场景级配置（尝试放宽框架上限，应被拒绝）
        Path scenarioDir = tempDir.resolve("scenario-relax");
        Files.createDirectories(scenarioDir);
        String scenarioActrule = "actrule:\n" +
                "  max_subtasks: 80\n" +  // 尝试放宽框架上限（80 > 50，应被拒绝）
                "  max_steps: 150\n" +    // 尝试放宽框架步数上限（150 > 100，应被拒绝）
                "  tool_limits:\n" +
                "    call_versatile: 80\n"; // 尝试放宽框架限制（80 > 50，应被拒绝）
        Files.writeString(scenarioDir.resolve("actrule.yaml"), scenarioActrule);
        createMinimalActruleAndScriptconfig(scenarioDir);

        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        // 验证：场景放宽框架上限应被拒绝，应取min（框架上限）
        assertEquals(50, mergedConfig.getActrule().getMaxSubtasks(), "maxSubtasks应保持框架上限（50），拒绝场景放宽");
        assertEquals(100, mergedConfig.getActrule().getMaxSteps(), "maxSteps应保持框架上限（100），拒绝场景放宽");
        assertEquals(50, mergedConfig.getActrule().getToolLimits().get("call_versatile"), "call_versatile应保持框架限制（50），拒绝场景放宽");
    }

    @Test
    @DisplayName("测试17：框架无配置，场景配置完全生效")
    void testFrameworkNullScenarioConfig生效() throws Exception {
        // 准备框架级配置（无资源限制配置）
        Path frameworkDir = tempDir.resolve("framework-null");
        Files.createDirectories(frameworkDir);
        String frameworkActrule = "actrule:\n" +
                "  skill_mode: all\n";  // 只配置其他字段
        Files.writeString(frameworkDir.resolve("actrule.yaml"), frameworkActrule);
        createMinimalActruleAndScriptconfig(frameworkDir);

        // 准备场景级配置（新增资源限制）
        Path scenarioDir = tempDir.resolve("scenario-new-limits");
        Files.createDirectories(scenarioDir);
        String scenarioActrule = "actrule:\n" +
                "  max_subtasks: 30\n" +  // 框架无此配置，场景新增
                "  max_steps: 80\n" +      // 框架无此配置，场景新增
                "  tool_limits:\n" +
                "    call_versatile: 30\n" +  // 框架无此配置，场景新增
                "    call_mcp: 20\n";          // 框架无此配置，场景新增
        Files.writeString(scenarioDir.resolve("actrule.yaml"), scenarioActrule);
        createMinimalActruleAndScriptconfig(scenarioDir);

        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        // 验证：框架无配置时，场景配置完全生效
        assertEquals(30, mergedConfig.getActrule().getMaxSubtasks(), "框架无maxSubtasks，场景配置30应生效");
        assertEquals(80, mergedConfig.getActrule().getMaxSteps(), "框架无maxSteps，场景配置80应生效");
        assertNotNull(mergedConfig.getActrule().getToolLimits(), "toolLimits应存在");
        assertEquals(30, mergedConfig.getActrule().getToolLimits().get("call_versatile"), "框架无call_versatile限制，场景新增30应生效");
        assertEquals(20, mergedConfig.getActrule().getToolLimits().get("call_mcp"), "框架无call_mcp限制，场景新增20应生效");
    }

    @Test
    @DisplayName("测试18：框架和场景都无配置（边界验证）")
    void testBothNullConfig() throws Exception {
        // 准备框架级配置（完全不配置资源限制）
        Path frameworkDir = tempDir.resolve("framework-both-null");
        Files.createDirectories(frameworkDir);
        String frameworkActrule = "actrule:\n" +
                "  skill_mode: all\n" +
                "  enable_task_loop: true\n";
        Files.writeString(frameworkDir.resolve("actrule.yaml"), frameworkActrule);
        createMinimalActruleAndScriptconfig(frameworkDir);

        // 准备场景级配置（完全不配置资源限制）
        Path scenarioDir = tempDir.resolve("scenario-both-null");
        Files.createDirectories(scenarioDir);
        String scenarioActrule = "actrule:\n" +
                "  skill_mode: auto_list\n";  // 只配置其他字段
        Files.writeString(scenarioDir.resolve("actrule.yaml"), scenarioActrule);
        createMinimalActruleAndScriptconfig(scenarioDir);

        // 执行优先级加载
        GovernanceConfig mergedConfig = GovernanceConfigLoader.loadWithPriority(scenarioDir, frameworkDir);

        // 验证：双方都无配置时，资源限制字段为null（或系统默认值）
        assertNull(mergedConfig.getActrule().getMaxSubtasks(), "双方都无maxSubtasks配置，应为null");
        assertNull(mergedConfig.getActrule().getMaxSteps(), "双方都无maxSteps配置，应为null");
        assertNull(mergedConfig.getActrule().getToolLimits(), "双方都无toolLimits配置，应为null");
        // 其他字段正常继承/覆盖
        assertEquals("auto_list", mergedConfig.getActrule().getSkillMode(), "skillMode应覆盖为auto_list");
        assertEquals(true, mergedConfig.getActrule().getEnableTaskLoop(), "enableTaskLoop应继承框架值true");
    }
}