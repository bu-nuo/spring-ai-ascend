package com.huawei.ascend.edp.handler;

import com.huawei.ascend.edp.channel.ToolDataChannel;
import com.huawei.ascend.edp.config.EdpAgentConfig;
import com.huawei.ascend.edp.config.EdpAgentConfig.EnvOverrides;
import com.huawei.ascend.edp.config.EdpAgentConfigLoader;
import com.huawei.ascend.edp.config.EdpConfig;
import com.huawei.ascend.edp.config.EdpConfigLoader;
import com.huawei.ascend.edp.config.EdpConfigValidator;
import com.huawei.ascend.edp.config.ScenarioConfig;
import com.huawei.ascend.edp.config.ScenarioConfigLoader;
import com.huawei.ascend.edp.config.ScenarioDiscoveryConfig;
import com.huawei.ascend.edp.config.ScenarioScopeConfig;
import com.huawei.ascend.edp.enhancer.EdpaAgentEnhancer;
import com.huawei.ascend.edp.stream.ScenarioPromptBuilder;
import com.huawei.ascend.edp.stream.SkillScriptsCollector;
import com.huawei.ascend.edp.stream.SysScriptsConfig;
import com.huawei.ascend.runtime.engine.AgentExecutionContext;
import com.huawei.ascend.runtime.engine.openjiuwen.OpenJiuwenAgentRuntimeHandler;
import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // 第七步：按场景动态拼接系统提示词。
        ScenarioConfig scenario = edpConfig.getActiveScenario();
        String systemPrompt;
        if (scenario != null && agentConfig.getPrompt() != null && agentConfig.getPrompt().getSystem().isEmpty()) {
            systemPrompt = ScenarioPromptBuilder.buildSystemPrompt(scenario);
        } else {
            systemPrompt = agentConfig.getPrompt() != null ? agentConfig.getPrompt().getSystem() : "";
        }

        // 第八步：构造 DeepAgentConfig。
        // Skill 目录从 scenarioHomePath/skills 解析，不再从 yamlDir.resolve("./skills")。
        Path skillsDir = scenarioHomePath != null ? scenarioHomePath.resolve("skills") : null;
        DeepAgentConfig deepAgentConfig = buildDeepAgentConfig(agentConfig, edpConfig, yamlDir, systemPrompt, skillsDir);

        // 第九步：通过 OpenJiuwen HarnessFactory 创建 DeepAgent。
        deepAgent = HarnessFactory.createDeepAgent(deepAgentConfig);

        // 第十步：注册 Skill 目录（从 scenarioHomePath/skills）。
        registerSkills(skillsDir);

        // 第十一步：注册 EDPAgent 内置业务工具和业务 Rails。
        EdpaAgentEnhancer.enhance(deepAgent, edpConfig, agentConfig, new ToolDataChannel(), skillsDir);

        // 第十二步：加载框架级、场景级、Skill 级话术。
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

        // 第十三步：强制完成 DeepAgent 初始化。
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
     *
     * @param agentConfig 标准 agent 配置
     * @param edpConfig EDP 专有配置
     * @param yamlDir edp-agent.yaml 所在目录（用于兼容旧逻辑）
     * @param systemPrompt 系统提示词
     * @param skillsDir 场景级 Skill 目录路径
     * @return DeepAgentConfig
     */
    private DeepAgentConfig buildDeepAgentConfig(EdpAgentConfig agentConfig, EdpConfig edpConfig, Path yamlDir, String systemPrompt, Path skillsDir) {
        EdpAgentConfig.Model model = agentConfig.getModel();
        EdpAgentConfig.Options options = agentConfig.getFramework() != null ? agentConfig.getFramework().getOptions() : null;
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
                .maxIterations(options != null && options.getMaxIterations() > 0 ? options.getMaxIterations() : 15)
                .enableTaskLoop(options != null && options.isEnableTaskLoop())
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
}
