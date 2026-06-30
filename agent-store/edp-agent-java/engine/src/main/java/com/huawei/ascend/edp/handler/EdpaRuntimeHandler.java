package com.huawei.ascend.edp.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.edp.channel.ToolDataChannel;
import com.huawei.ascend.edp.config.ActRuleConfig;
import com.huawei.ascend.edp.config.EdpAgentConfig;
import com.huawei.ascend.edp.config.EdpAgentConfig.EnvOverrides;
import com.huawei.ascend.edp.config.EdpAgentConfigLoader;
import com.huawei.ascend.edp.config.EdpConfig;
import com.huawei.ascend.edp.config.EdpConfigLoader;
import com.huawei.ascend.edp.config.EdpConfigValidator;
import com.huawei.ascend.edp.config.EdpaTodolist;
import com.huawei.ascend.edp.config.GovernanceConfig;
import com.huawei.ascend.edp.config.GovernanceConfigLoader;
import com.huawei.ascend.edp.config.ScenarioConfig;
import com.huawei.ascend.edp.config.ScenarioConfigLoader;
import com.huawei.ascend.edp.config.ScenarioDiscoveryConfig;
import com.huawei.ascend.edp.config.ScenarioScopeConfig;
import com.huawei.ascend.edp.enhancer.EdpaAgentEnhancer;
import com.huawei.ascend.edp.enhancer.EdpaEventStreamAdapter;
import com.huawei.ascend.edp.rail.VersatileInterruptRail;
import com.huawei.ascend.edp.rail.VersatileInterruptRail.VersatilePassthroughBuffer;
import com.huawei.ascend.edp.stream.PlanrulePromptBuilder;
import com.huawei.ascend.edp.stream.ScenarioPromptBuilder;
import com.huawei.ascend.edp.stream.SkillScriptsCollector;
import com.huawei.ascend.edp.stream.SysScriptsConfig;
import com.huawei.ascend.runtime.engine.AgentExecutionContext;
import com.huawei.ascend.runtime.engine.openjiuwen.OpenJiuwenAgentRuntimeHandler;
import com.huawei.ascend.runtime.engine.spi.AgentExecutionResult;
import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
import com.huawei.ascend.runtime.engine.spi.StreamAdapter;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * EDPAgent 运行时适配器。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>作为 {@link OpenJiuwenAgentRuntimeHandler} 的 EDPAgent 实现，接入 agent-runtime 的 A2A 执行链路。</li>
 *     <li>加载 EDPAgent 标准 YAML 配置和 EDP 专有配置，并合成为 {@link DeepAgentConfig}。</li>
 *     <li>创建并增强 DeepAgent，注册 EDPAgent 的业务工具和业务 Rails。</li>
 *     <li>向 agent-runtime 暴露可执行的 OpenJiuwen {@link BaseAgent} 实例。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>{@link #EdpaRuntimeHandler()}：构造 handler，并向父类声明固定 agentId。</li>
 *     <li>{@link #init(String, String, EnvOverrides, String)}：初始化配置、DeepAgent、业务工具和 Rails。</li>
 *     <li>{@link #isHealthy()}：供运行时健康检查使用。</li>
 *     <li>{@link #getDeepAgent()}：供测试或诊断读取当前 DeepAgent 实例。</li>
 *     <li>{@link #getEdpConfig()}：供测试或诊断读取当前 EDP 专有配置。</li>
 * </ul>
 *
 * <p>被 agent-runtime 调用的覆写接口：</p>
 * <ul>
 *     <li>{@link #createOpenJiuwenAgent(AgentExecutionContext)}：返回真正参与执行的 OpenJiuwen Agent。</li>
 *     <li>{@link #openJiuwenRails(AgentExecutionContext)}：返回 EDPAgent 业务 Rails。</li>
 * </ul>
 *
 * <p>路径解析说明：</p>
 * <ul>
 *     <li>scenarioHome：活动场景目录路径，由 Spring Boot @Value 注入（edpa.agent.scenario-home）。</li>
 *     <li>场景配置：从 scenarioHome/scenario-config.yaml 加载，不再依赖 yamlDir.resolve("scenarios")。</li>
 *     <li>业务 Skill：从 scenarioHome/skills/ 加载，不再依赖 yamlDir.resolve("./skills")。</li>
 *     <li>密钥类配置：通过 EnvOverrides 从环境变量注入，YAML 中写 PLACEHOLDER_USE_ENV_VAR 占位。</li>
 * </ul>
 */
public class EdpaRuntimeHandler extends OpenJiuwenAgentRuntimeHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaRuntimeHandler.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * EDPAgent 在 agent-runtime 中注册和路由使用的固定 agentId。
     */
    private static final String AGENT_ID = "edp-agent";

    /**
     * DeepAgent 外观对象，负责创建和持有底层 OpenJiuwen BaseAgent。
     */
    private DeepAgent deepAgent;

    /**
     * EDPAgent 标准配置，来自 edp-agent.yaml。
     */
    private EdpAgentConfig agentConfig;

    /**
     * EDPAgent 专有配置，来自 edp-config.yaml。
     */
    private EdpConfig edpConfig;

    /** Todo 数据层（catalog entries + dynamic paths），从 scenario-config.yaml 加载。 */
    private EdpaTodolist edpaTodolist;

    /**
     * Versatile adapter 返回的 USER 透传节点缓冲，与 {@link VersatileInterruptRail} 共享。
     * 由 {@link VersatilePassthroughIterator} 在 DeepAgent 流式帧之间按序刷出。
     */
    private final VersatilePassthroughBuffer versatilePassthroughBuffer = new VersatilePassthroughBuffer();

    /**
     * 活动场景目录的绝对路径，由 Spring Boot @Value 注入后解析。
     */
    private Path scenarioHomePath;

    /**
     * 构造 EDPAgent runtime handler。
     */
    public EdpaRuntimeHandler() {
        super(AGENT_ID);
    }

    /**
     * 初始化 EDPAgent（兼容旧接口，不含 scenarioHome）。
     */
    public void init(String yamlPath, String configPath) {
        init(yamlPath, configPath, null, null);
    }

    /**
     * 初始化 EDPAgent，支持环境变量覆盖密钥类配置。
     */
    public void init(String yamlPath, String configPath, EnvOverrides envOverrides) {
        init(yamlPath, configPath, envOverrides, null);
    }

    /**
     * 初始化 EDPAgent，支持环境变量覆盖密钥类配置和场景路径注入。
     *
     * <p>作用：</p>
     * <ul>
     *     <li>加载标准 agent YAML 配置。</li>
     *     <li>加载 EDP 专有配置。</li>
     *     <li>从 scenarioHome 加载场景配置和业务 Skill。</li>
     *     <li>合成 DeepAgentConfig 并创建 DeepAgent。</li>
     *     <li>注册 EDPAgent 业务工具和业务 Rails。</li>
     *     <li>触发 DeepAgent 初始化，确保后续 A2A 请求可直接执行。</li>
     * </ul>
     *
     * @param yamlPath 标准 agent YAML 配置路径
     * @param configPath EDP 专有配置路径
     * @param envOverrides 环境变量覆盖配置，null 时不从环境变量注入
     * @param scenarioHome 活动场景目录路径，由 Spring Boot @Value 注入；null 时回退到 yamlDir 解析
     */
    public void init(String yamlPath, String configPath, EnvOverrides envOverrides, String scenarioHome) {
        LOGGER.info("EdpaRuntimeHandler init start, yamlPath={}, configPath={}, scenarioHome={}", yamlPath, configPath, scenarioHome);

        // 第一步：加载标准 Agent 配置。
        agentConfig = EdpAgentConfigLoader.load(Path.of(yamlPath));

        // 第二步：应用环境变量覆盖密钥类配置。
        if (envOverrides != null) {
            applyEnvOverrides(envOverrides);
        }

        // 第三步：加载 EDP 专有配置。
        edpConfig = EdpConfigLoader.load(Path.of(configPath));
        Path yamlDir = Path.of(yamlPath).toAbsolutePath().normalize().getParent();

        // 第四步：解析 scenarioHome 路径。
        // scenarioHome 优先由 Spring Boot @Value 注入，指向活动场景目录（如 scenarios/wealth-demo）。
        // 回退逻辑：如果 scenarioHome 未注入，从 yamlDir + scenario_discovery.base_path 解析（兼容旧模式）。
        if (scenarioHome != null && !scenarioHome.isBlank()) {
            scenarioHomePath = Path.of(scenarioHome).toAbsolutePath().normalize();
            LOGGER.info("scenarioHome resolved from Spring @Value: {} -> {}", scenarioHome, scenarioHomePath);
        } else {
            // 回退：从 yamlDir 解析场景根目录（旧模式，resources/scenarios）
            ScenarioDiscoveryConfig discovery = edpConfig.getScenarioDiscovery();
            if (discovery != null) {
                scenarioHomePath = yamlDir.resolve(discovery.getBasePath())
                        .resolve(discovery.getActiveScenario()).toAbsolutePath().normalize();
                LOGGER.info("scenarioHome resolved from yamlDir fallback: {}", scenarioHomePath);
            } else {
                LOGGER.warn("No scenarioHome and no scenario_discovery configured; scenario loading skipped.");
            }
        }

        // 第五步：场景发现与加载（从 scenarioHomePath）。
        if (scenarioHomePath != null && Files.exists(scenarioHomePath)) {
            try {
                Path scenarioConfigPath = ScenarioConfigLoader.findScenarioFile(scenarioHomePath);
                ScenarioConfig scenarioConfig = ScenarioConfigLoader.loadScenarioConfig(scenarioConfigPath);
                edpConfig.setActiveScenario(scenarioConfig);

                // 用场景级 todolistSteps 覆盖框架级占位
                if (scenarioConfig.getTodolistSteps() != null) {
                    edpConfig.setTodolistSteps(scenarioConfig.getTodolistSteps());
                }

                // 加载 Todo 数据层（catalog entries + dynamic paths）
                try {
                    edpaTodolist = new EdpaTodolist(scenarioConfigPath);
                    LOGGER.info("EdpaTodolist loaded: legacyMode={}, entries={}, dynamicPaths={}",
                            edpaTodolist.isLegacyMode(), edpaTodolist.getEntries().size(),
                            edpaTodolist.getDynamicPaths().size());
                } catch (Exception e) {
                    LOGGER.warn("Failed to load EdpaTodolist from {}: {}", scenarioConfigPath, e.getMessage());
                }

                // 用场景级 scope 覆盖框架级
                if (scenarioConfig.getScope() != null) {
                    ScenarioScopeConfig scenarioScope = scenarioConfig.getScope();
                    EdpConfig.Scope frameworkScope = edpConfig.getScope();
                    if (frameworkScope == null) {
                        frameworkScope = new EdpConfig.Scope();
                    }
                    if (scenarioScope.getAllowed() != null && !scenarioScope.getAllowed().isEmpty()) {
                        frameworkScope.setAllowed(String.join("、", scenarioScope.getAllowed()));
                    }
                    edpConfig.setScope(frameworkScope);
                }

                LOGGER.info("Scenario loaded from scenarioHome: name={}, todolistSteps={}, skillRouting={}",
                        scenarioConfig.getName(),
                        scenarioConfig.getTodolistSteps() != null ? scenarioConfig.getTodolistSteps().size() : 0,
                        scenarioConfig.getSkillRouting() != null ? scenarioConfig.getSkillRouting().size() : 0);
            } catch (Exception e) {
                LOGGER.warn("Failed to load scenario config from scenarioHome {}: {}", scenarioHomePath, e.getMessage());
            }
        } else if (scenarioHomePath != null) {
            LOGGER.warn("scenarioHome directory does not exist: {}", scenarioHomePath);
        }

        // 第六步：配置校验 fail-fast。
        EdpConfigValidator.validateModelConfig(agentConfig);
        EdpConfigValidator.validateVersatileUrl(agentConfig);
        EdpConfigValidator.validateTodolistSteps(edpConfig);
        if (scenarioHomePath != null) {
            EdpConfigValidator.validateScenarioConfig(scenarioHomePath);
        }

        // 第七步：加载GovernanceConfig（框架级 + 场景级）。
        GovernanceConfig governanceConfig = loadGovernanceConfig(yamlDir, scenarioHomePath);
        LOGGER.info("GovernanceConfig loaded: planrule={}, actrule={}, scriptconfig={}",
                governanceConfig.getPlanrule() != null ? "present" : "null",
                governanceConfig.getActrule() != null ? "present" : "null",
                governanceConfig.getScriptconfig() != null ? "present" : "null");

        // 第八步：拼接完整系统提示词（planrule + scenario 两部分）。
        // 第一部分：PlanrulePromptBuilder.buildSystemPromptFragment(governance.getPlanrule())
        // 第二部分：ScenarioPromptBuilder.buildSystemPrompt(scenario)
        ScenarioConfig scenario = edpConfig.getActiveScenario();
        String systemPrompt = buildFullSystemPrompt(governanceConfig, scenario, agentConfig);

        // 第九步：构造 DeepAgentConfig。
        // Skill 目录从 scenarioHomePath/skills 解析，不再从 yamlDir.resolve("./skills")。
        // 框架配置从 GovernanceConfig.actrule 加载，不再从 framework.options 读取。
        Path skillsDir = scenarioHomePath != null ? scenarioHomePath.resolve("skills") : null;
        DeepAgentConfig deepAgentConfig = buildDeepAgentConfig(agentConfig, edpConfig, governanceConfig, systemPrompt, skillsDir);

        // 第十步：通过 OpenJiuwen HarnessFactory 创建 DeepAgent。
        deepAgent = HarnessFactory.createDeepAgent(deepAgentConfig);

        // 第十一步：注册 Skill 目录（从 scenarioHomePath/skills）。
        registerSkills(skillsDir);

        // 第十二步：注册 EDPAgent 内置业务工具和业务 Rails（含 Todo 增强 + 思维链事件）。
        EdpaAgentEnhancer.enhance(deepAgent, edpConfig, agentConfig, new ToolDataChannel(), skillsDir,
                versatilePassthroughBuffer, deepAgent, edpaTodolist);

        // 第十三步：加载框架级、场景级、Skill 级话术。
        SysScriptsConfig sysScriptsConfig = new SysScriptsConfig();
        if (edpConfig.getUtterances() != null && edpConfig.getUtterances().getConfigPath() != null) {
            Path scriptsConfigPath = yamlDir.resolve(edpConfig.getUtterances().getConfigPath()).toAbsolutePath().normalize();
            sysScriptsConfig.load(scriptsConfigPath.toString());
        }
        if (scenarioHomePath != null) {
            Path scenarioScriptsConfigPath = scenarioHomePath.resolve("ScriptsConfig.yaml").toAbsolutePath().normalize();
            sysScriptsConfig.load(scenarioScriptsConfigPath.toString());
        }
        if (skillsDir != null && Files.exists(skillsDir)) {
            Map<String, String> skillScripts = SkillScriptsCollector.collectSkillScripts(skillsDir);
            sysScriptsConfig.mergeSkillScripts(skillScripts);
            LOGGER.info("Skill scripts collected: {} entries from {}", skillScripts.size(), skillsDir);
        } else {
            LOGGER.info("No skills directory found; skill scripts collection skipped.");
        }
        LOGGER.info("SysScriptsConfig merged templates: {}", sysScriptsConfig.getTemplates().size());

        // 第十四步：强制完成 DeepAgent 初始化。
        deepAgent.ensureInitialized();

        LOGGER.info("EdpaRuntimeHandler init completed, agentId={}, deepAgent initialized={}, scenarioHome={}",
                AGENT_ID, deepAgent.isInitialized(), scenarioHomePath);
    }

    /**
     * 应用环境变量覆盖密钥类配置。
     */
    private void applyEnvOverrides(EnvOverrides overrides) {
        if (overrides == null) return;

        EdpAgentConfig.Model model = agentConfig.getModel();
        if (model == null) {
            model = new EdpAgentConfig.Model();
            agentConfig.setModel(model);
        }

        if (overrides.getApiKey() != null && !overrides.getApiKey().isBlank()) {
            model.setApiKey(overrides.getApiKey());
            LOGGER.info("Env override applied: apiKey from EDP_AGENT_MODEL_API_KEY");
        }

        if (overrides.getModelProvider() != null && !overrides.getModelProvider().isBlank()) {
            model.setProvider(overrides.getModelProvider());
            LOGGER.info("Env override applied: modelProvider={}", overrides.getModelProvider());
        }

        if (overrides.getModelName() != null && !overrides.getModelName().isBlank()) {
            model.setName(overrides.getModelName());
            LOGGER.info("Env override applied: modelName={}", overrides.getModelName());
        }

        if (overrides.getModelBaseUrl() != null && !overrides.getModelBaseUrl().isBlank()) {
            model.setBaseUrl(overrides.getModelBaseUrl());
            LOGGER.info("Env override applied: modelBaseUrl={}", overrides.getModelBaseUrl());
        }

        if (overrides.getVersatileUrl() != null && !overrides.getVersatileUrl().isBlank()) {
            EdpAgentConfig.Versatile versatile = agentConfig.getVersatile();
            if (versatile != null) {
                versatile.setUrl(overrides.getVersatileUrl());
                LOGGER.info("Env override applied: versatileUrl={}", overrides.getVersatileUrl());
            }
        }
    }

    /**
     * 注册场景级 Skill 目录。
     *
     * Skill 目录从 scenarioHomePath/skills 解析，
     * 不再使用 edp-agent.yaml 中 skills.directories 的硬编码路径。
     *
     * @param skillsDir 场景级 Skill 目录路径（scenarioHomePath/skills）
     */
    private void registerSkills(Path skillsDir) {
        if (skillsDir == null || !Files.exists(skillsDir)) {
            LOGGER.info("Skill load skipped: skills directory not found or not configured");
            return;
        }
        ensureSkillSysOperationId();
        deepAgent.getAgent().registerSkill(skillsDir.toString());
        boolean hasSkill = deepAgent.getAgent().getSkillUtil() != null && deepAgent.getAgent().getSkillUtil().hasSkill();
        int skillCount = hasSkill ? deepAgent.getAgent().getSkillUtil().getSkillManager().count() : 0;
        List<String> skillNames = hasSkill ? deepAgent.getAgent().getSkillUtil().getSkillManager().getNames() : List.of();
        LOGGER.info("Skill load completed: hasSkill={}, skillCount={}, skillNames={}, dir={}",
                hasSkill, skillCount, skillNames, skillsDir);
    }

    /**
     * 为 OpenJiuwen SkillUtil 初始化提供 sysOperationId。
     */
    private void ensureSkillSysOperationId() {
        Object config = deepAgent.getAgent().getConfig();
        if (config instanceof ReActAgentConfig reactConfig && reactConfig.getSysOperationId() == null) {
            reactConfig.setSysOperationId(AGENT_ID);
        }
    }

    /**
     * 构造 DeepAgentConfig。
     *
     * Skill 目录从 scenarioHomePath/skills 注入，不再从 edp-agent.yaml skills.directories 解析。
     * 框架配置从 GovernanceConfig.actrule 加载，不再从 framework.options 读取。
     *
     * @param agentConfig 标准 agent 配置
     * @param edpConfig EDP 专有配置
     * @param governanceConfig 治理配置（包含 actrule）
     * @param systemPrompt 系统提示词
     * @param skillsDir 场景级 Skill 目录路径
     * @return DeepAgentConfig
     */
    private DeepAgentConfig buildDeepAgentConfig(EdpAgentConfig agentConfig, EdpConfig edpConfig, GovernanceConfig governanceConfig, String systemPrompt, Path skillsDir) {
        EdpAgentConfig.Model model = agentConfig.getModel();
        ActRuleConfig actrule = governanceConfig != null ? governanceConfig.getActrule() : null;
        EdpConfig.LlmSampling sampling = edpConfig != null ? edpConfig.getLlmSampling() : null;

        Map<String, Object> modelMap = new LinkedHashMap<>();
        Map<String, Object> backendMap = new LinkedHashMap<>();

        if (model != null) {
            modelMap.put("model", model.getName());
            modelMap.put("model_name", model.getName());

            if (sampling != null) {
                modelMap.put("temperature", sampling.getTemperature());
                modelMap.put("top_p", sampling.getTopP());
            }

            backendMap.put("provider", model.getProvider());
            backendMap.put("client_provider", model.getProvider());
            backendMap.put("apiKey", model.getApiKey());
            backendMap.put("api_key", model.getApiKey());
            backendMap.put("baseUrl", model.getBaseUrl());
            backendMap.put("apiBase", model.getBaseUrl());
            backendMap.put("api_base", model.getBaseUrl());
        }

        // Skill 目录：从 scenarioHomePath/skills 注入，不再从 edp-agent.yaml skills.directories 解析
        List<String> skillDirs = (skillsDir != null && Files.exists(skillsDir))
                ? List.of(skillsDir.toString())
                : List.of();

        String skillMode = agentConfig.getSkills() != null ? agentConfig.getSkills().getMode() : "all";

        return DeepAgentConfig.builder()
                .systemPrompt(systemPrompt != null ? systemPrompt : "")
                .maxIterations(actrule != null && actrule.getMaxSteps() != null && actrule.getMaxSteps() > 0 ? actrule.getMaxSteps() : 15)
                .enableTaskLoop(actrule != null && actrule.getEnableTaskLoop() != null ? actrule.getEnableTaskLoop() : false)
                .enableTaskPlanning(true)
                .skillDirectories(skillDirs)
                .skillMode(skillMode)
                .model(modelMap)
                .backend(backendMap)
                .build();
    }

    /**
     * 创建 OpenJiuwen Agent 执行实例。
     */
    @Override
    protected BaseAgent createOpenJiuwenAgent(AgentExecutionContext context) {
        LOGGER.info("createOpenJiuwenAgent called, returning DeepAgent instance, agentId={}", AGENT_ID);
        return deepAgent.getAgent();
    }

    /**
     * 返回每次请求需要临时安装的 OpenJiuwen Rails。
     */
    @Override
    protected List<AgentRail> openJiuwenRails(AgentExecutionContext context) {
        LOGGER.info("openJiuwenRails called, EDPAgent business rails already installed during init; returning no per-request rails");
        return List.of();
    }

    @Override
    protected Iterator<Object> runOpenJiuwenAgentStreaming(BaseAgent agent, Object input, String conversationId,
            List<StreamMode> streamModes) {
        // 菜单确认等 Versatile 续传输入绕过 LLM，直接二次调用 adapter。
        Map<String, Object> continuationInputs = extractVersatileContinuationInputs(input);
        if (continuationInputs != null) {
            LOGGER.info("runOpenJiuwenAgentStreaming: direct versatile continuation conversationId={} inputs={}",
                    conversationId, continuationInputs);
            VersatileInterruptRail rail = new VersatileInterruptRail(
                    edpConfig, agentConfig != null ? agentConfig.getVersatile() : null,
                    new ToolDataChannel(), versatilePassthroughBuffer);
            Map<String, Object> result = rail.invokeWithInputs(continuationInputs, conversationId);
            if (isTerminalVersatileResult(result)) {
                // 续传完成：把 adapter 结果包装成 InteractiveInput，恢复被中断的 call_versatile。
                String interruptId = versatilePassthroughBuffer.pollInterruptId(conversationId);
                Object resumeInput = versatileToolResumeInput(conversationId, interruptId, result);
                Iterator<Object> delegate = super.runOpenJiuwenAgentStreaming(agent, resumeInput, conversationId, streamModes);
                return new VersatilePassthroughIterator(conversationId, delegate, versatilePassthroughBuffer);
            }
            return versatileContinuationResults(conversationId, result).iterator();
        }
        Iterator<Object> delegate = super.runOpenJiuwenAgentStreaming(agent, input, conversationId, streamModes);
        return new VersatilePassthroughIterator(conversationId, delegate, versatilePassthroughBuffer);
    }

    /**
     * 识别 Versatile 菜单确认续传：query 为 JSON 且同时携带 menu_type 与 menu_confirm。
     */
    private Map<String, Object> extractVersatileContinuationInputs(Object input) {
        if (!(input instanceof Map<?, ?> map)) {
            return null;
        }
        Object query = map.get("query");
        if (!(query instanceof String text) || text.isBlank()) {
            return null;
        }
        Map<String, Object> body = parseJsonObject(text);
        if (body.isEmpty()) {
            return null;
        }
        Object inputs = body.get("inputs");
        Map<String, Object> normalized = inputs instanceof Map<?, ?> inputsMap
                ? normalizeStringMap(inputsMap)
                : normalizeStringMap(body);
        return isVersatileMenuConfirmation(normalized) ? normalized : null;
    }

    private Map<String, Object> parseJsonObject(String text) {
        try {
            return OBJECT_MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> normalizeStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private boolean isVersatileMenuConfirmation(Map<String, Object> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return false;
        }
        return inputs.containsKey("menu_type") && inputs.containsKey("menu_confirm");
    }

    private boolean isTerminalVersatileResult(Map<String, Object> result) {
        return result != null && "completed".equals(String.valueOf(result.get("status")));
    }

    private Object versatileToolResumeInput(String conversationId, String interruptId, Map<String, Object> result) {
        // OpenJiuwen 工具中断恢复约定：query 携带 InteractiveInput，key 为 toolCallId。
        InteractiveInput interactiveInput = new InteractiveInput();
        interactiveInput.update(interruptId != null && !interruptId.isBlank() ? interruptId : "call_versatile",
                toJson(result));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", interactiveInput);
        input.put("conversation_id", conversationId);
        return input;
    }

    private List<Object> versatileContinuationResults(String conversationId, Map<String, Object> result) {
        List<Object> results = new ArrayList<>();
        // 续传路径也要先刷出缓冲中的 USER 透传节点，再发终态帧。
        drainPassthroughNodes(conversationId, results);
        String status = result != null ? String.valueOf(result.get("status")) : "failed";
        if ("input_required".equals(status)) {
            results.add(AgentExecutionResult.interrupted("", AgentExecutionResult.Target.USER));
            return results;
        }
        if ("failed".equals(status)) {
            Object content = result != null ? result.get("content") : "adapter call failed";
            results.add(AgentExecutionResult.failed("VERSATILE_CONTINUATION_FAILED",
                    content == null ? "adapter call failed" : String.valueOf(content), AgentExecutionResult.Target.BOTH));
            return results;
        }
        Object content = result != null ? result.get("content") : "";
        results.add(AgentExecutionResult.completed(content == null ? "" : String.valueOf(content),
                AgentExecutionResult.Target.BOTH));
        return results;
    }

    private void drainPassthroughNodes(String conversationId, List<Object> results) {
        String node = versatilePassthroughBuffer.poll(conversationId);
        while (node != null) {
            results.add(AgentExecutionResult.output(node, AgentExecutionResult.Target.USER));
            node = versatilePassthroughBuffer.poll(conversationId);
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize versatile continuation result", e);
        }
    }

    /**
     * 加载GovernanceConfig（框架级 + 场景级）。
     *
     * <p>配置路径说明：</p>
     * <ul>
     *     <li>框架级governance路径：src/main/resources/governance（固定路径，出厂必带）</li>
     *     <li>场景级governance路径：{scenarioHomePath}/governance（动态路径，从场景目录解析）</li>
     *     <li>优先级：场景级 > 框架级（场景级配置覆盖框架级配置）</li>
     * </ul>
     *
     * @param yamlDir edp-agent.yaml所在目录（用于解析框架级governance路径）
     * @param scenarioHomePath 场景目录路径（用于解析场景级governance路径）
     * @return GovernanceConfig对象，包含planrule、actrule、scriptconfig
     */
    private GovernanceConfig loadGovernanceConfig(Path yamlDir, Path scenarioHomePath) {
        // 框架级governance路径（固定）：src/main/resources/governance
        Path frameworkGovernancePath = yamlDir.resolve("governance");

        // 场景级governance路径（动态）：{scenarioHomePath}/governance
        Path scenarioGovernancePath = scenarioHomePath != null ? scenarioHomePath.resolve("governance") : null;

        // 优先级加载：场景级 > 框架级
        GovernanceConfig governanceConfig;
        if (scenarioGovernancePath != null && Files.exists(scenarioGovernancePath)) {
            LOGGER.info("Loading governance config with priority: scenario={}, framework={}", scenarioGovernancePath, frameworkGovernancePath);
            governanceConfig = GovernanceConfigLoader.loadWithPriority(scenarioGovernancePath, frameworkGovernancePath);
        } else {
            LOGGER.info("Loading framework-level governance config: {}", frameworkGovernancePath);
            governanceConfig = GovernanceConfigLoader.load(frameworkGovernancePath);
        }

        return governanceConfig;
    }

    /**
     * 拼接完整系统提示词（两部分拼接）。
     *
     * <p>对应Python版系统提示词拼接方式：</p>
     * <pre>
     * system_prompt = _agent_rule.markdown_body  // 第一部分：角色定义、职责边界、行为约束（一到五章节）
     * system_prompt = f"{system_prompt.strip()}\n\n{build_system_prompt().strip()}"  // 第二部分：工具说明（第六章节）
     * </pre>
     *
     * <p>Java版拼接逻辑：</p>
     * <ul>
     *     <li>第一部分：PlanrulePromptBuilder.buildSystemPromptFragment(governance.getPlanrule())</li>
     *     <li>第二部分：ScenarioPromptBuilder.buildSystemPrompt(scenario)</li>
     *     <li>拼接方式：第一部分 + "\n\n" + 第二部分</li>
     * </ul>
     *
     * <p>向后兼容：如果agentConfig.prompt.system非空，优先使用用户自定义内容</p>
     *
     * @param governance GovernanceConfig对象，包含planrule配置
     * @param scenario ScenarioConfig对象，包含场景级动态内容
     * @param agentConfig EdpAgentConfig对象，包含用户自定义prompt.system（向后兼容）
     * @return 完整系统提示词（两部分拼接）
     */
    private String buildFullSystemPrompt(GovernanceConfig governance, ScenarioConfig scenario, EdpAgentConfig agentConfig) {
        // 向后兼容：如果agentConfig.prompt.system非空，优先使用用户自定义内容
        if (agentConfig.getPrompt() != null && !agentConfig.getPrompt().getSystem().isEmpty()) {
            LOGGER.info("Using user-defined system prompt from agentConfig.prompt.system (backward compatibility)");
            return agentConfig.getPrompt().getSystem();
        }

        // 第一部分：planrule四字段拼接（替代Python版markdown_body）
        String planruleFragment = "";
        if (governance != null && governance.getPlanrule() != null) {
            planruleFragment = PlanrulePromptBuilder.buildSystemPromptFragment(governance.getPlanrule());
            LOGGER.info("Planrule fragment built: length={}", planruleFragment.length());
        }

        // 第二部分：工具说明 + 场景规则（对应Python版build_system_prompt()）
        String scenarioFragment = "";
        if (scenario != null) {
            scenarioFragment = ScenarioPromptBuilder.buildSystemPrompt(scenario);
            LOGGER.info("Scenario fragment built: length={}", scenarioFragment.length());
        } else {
            // 如果scenario为null，场景提示词部分为空
            scenarioFragment = "";
        }

        // 拼接两部分（对应Python版拼接方式）
        String fullSystemPrompt;
        if (planruleFragment.isEmpty()) {
            fullSystemPrompt = scenarioFragment;  // 如果planrule为空，只返回第二部分
            LOGGER.info("Full system prompt: only scenario fragment (planrule empty)");
        } else if (scenarioFragment.isEmpty()) {
            fullSystemPrompt = planruleFragment;  // 如果scenario为空，只返回第一部分
            LOGGER.info("Full system prompt: only planrule fragment (scenario empty)");
        } else {
            fullSystemPrompt = planruleFragment + "\n\n" + scenarioFragment;  // 拼接两部分，中间空行分隔
            LOGGER.info("Full system prompt: planrule + scenario fragments concatenated");
        }

        LOGGER.info("Full system prompt built: total length={}", fullSystemPrompt.length());
        LOGGER.info("=== FULL SYSTEM PROMPT START ===\n{}\n=== FULL SYSTEM PROMPT END ===", fullSystemPrompt);
        return fullSystemPrompt;
    }

    /**
     * 健康检查接口。
     */
    @Override
    public boolean isHealthy() {
        return deepAgent != null && deepAgent.isInitialized();
    }

    /**
     * 获取 DeepAgent 实例，供测试或诊断使用。
     */
    public DeepAgent getDeepAgent() {
        return deepAgent;
    }

    /**
     * 获取 EDP 专有配置，供测试或诊断使用。
     */
    public EdpConfig getEdpConfig() {
        return edpConfig;
    }

    /**
     * 获取 scenarioHomePath，供测试或诊断使用。
     */
    public Path getScenarioHomePath() {
        return scenarioHomePath;
    }

    /**
     * 覆写流适配器：将 EdpaEventRail 发射的 custom OutputSchema 转为 A2A SSE 帧，
     * 抑制重复的 llm_output（已由事件流发射）。
     */
    @Override
    public StreamAdapter resultAdapter() {
        return new EdpaEventStreamAdapter();
    }

    private static final class VersatilePassthroughIterator implements Iterator<Object> {
        private final String conversationId;
        private final Iterator<Object> delegate;
        private final VersatilePassthroughBuffer passthroughBuffer;
        /** 当透传节点与 delegate 帧同时就绪时，暂存 delegate 帧以保证 USER 节点优先输出。 */
        private Object deferredRaw;
        private boolean delegateDrained;

        private VersatilePassthroughIterator(String conversationId, Iterator<Object> delegate,
                VersatilePassthroughBuffer passthroughBuffer) {
            this.conversationId = conversationId;
            this.delegate = delegate;
            this.passthroughBuffer = passthroughBuffer;
        }

        @Override
        public boolean hasNext() {
            if (passthroughBuffer.hasPending(conversationId) || deferredRaw != null) {
                return true;
            }
            if (delegateDrained || delegate == null) {
                return false;
            }
            boolean hasNext = delegate.hasNext();
            if (!hasNext) {
                delegateDrained = true;
                passthroughBuffer.clear(conversationId);
            }
            return hasNext;
        }

        @Override
        public Object next() {
            String node = passthroughBuffer.poll(conversationId);
            if (node != null) {
                return AgentExecutionResult.output(node, AgentExecutionResult.Target.USER);
            }
            if (deferredRaw != null) {
                Object raw = deferredRaw;
                deferredRaw = null;
                return raw;
            }
            if (delegate == null || !delegate.hasNext()) {
                delegateDrained = true;
                passthroughBuffer.clear(conversationId);
                throw new NoSuchElementException();
            }
            Object raw = delegate.next();
            node = passthroughBuffer.poll(conversationId);
            if (node != null) {
                deferredRaw = raw;
                return AgentExecutionResult.output(node, AgentExecutionResult.Target.USER);
            }
            return raw;
        }
    }
}
