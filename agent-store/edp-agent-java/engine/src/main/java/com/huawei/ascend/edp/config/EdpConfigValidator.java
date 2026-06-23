package com.huawei.ascend.edp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 启动时配置校验器。
 *
 * fail-fast 校验：缺配置 → 启动失败并报明确错误。
 *
 * V2 方案 B：场景校验从 scenarioHome 出发，
 * 不再依赖 yamlDir.resolve(basePath) 解析。
 */
public class EdpConfigValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdpConfigValidator.class);

    /**
     * 校验模型配置完整性。
     */
    public static void validateModelConfig(EdpAgentConfig agentConfig) {
        EdpAgentConfig.Model model = agentConfig.getModel();
        if (model == null) {
            throw new IllegalStateException("Model config missing. Set edp-agent.yaml model section.");
        }
        if (model.getProvider() == null || model.getProvider().isBlank()) {
            throw new IllegalStateException("Model provider missing. Set edp-agent.yaml model.provider.");
        }
        if (model.getName() == null || model.getName().isBlank()) {
            throw new IllegalStateException("Model name missing. Set edp-agent.yaml model.name.");
        }
        if (model.getBaseUrl() == null || model.getBaseUrl().isBlank()) {
            throw new IllegalStateException("Model baseUrl missing. Set edp-agent.yaml model.baseUrl.");
        }

        String apiKey = model.getApiKey();
        String envApiKey = System.getenv("EDP_AGENT_MODEL_API_KEY");
        boolean hasValidApiKey = (apiKey != null && !apiKey.isBlank() && !apiKey.equals("PLACEHOLDER_USE_ENV_VAR"))
                || (envApiKey != null && !envApiKey.isBlank());
        if (!hasValidApiKey) {
            throw new IllegalStateException("Model apiKey missing. Set EDP_AGENT_MODEL_API_KEY environment variable.");
        }

        LOGGER.info("Model config validated: provider={}, name={}, apiKeySource={}",
                model.getProvider(), model.getName(),
                (envApiKey != null && !envApiKey.isBlank()) ? "ENV_VAR" : "YAML");
    }

    /**
     * 校验 Versatile URL 合法性。
     */
    public static void validateVersatileUrl(EdpAgentConfig agentConfig) {
        EdpAgentConfig.Versatile versatile = agentConfig.getVersatile();
        if (versatile != null && versatile.getUrl() != null) {
            String url = versatile.getUrl();
            if (url.startsWith("${")) {
                String envUrl = System.getenv("EDP_AGENT_VERSATILE_URL");
                if (envUrl != null && !envUrl.isBlank()) {
                    LOGGER.info("Versatile URL from env var validated: {}", envUrl);
                } else {
                    throw new IllegalStateException("Versatile URL is a Spring placeholder but env var EDP_AGENT_VERSATILE_URL not set.");
                }
            } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalStateException("Versatile URL invalid: " + url + ". Must start with http:// or https://.");
            } else {
                LOGGER.info("Versatile URL validated: {}", url);
            }
        }
    }

    /**
     * 校验 Skill 目录存在。
     */
    public static void validateSkillDir(Path skillDir) {
        if (skillDir != null && !Files.exists(skillDir)) {
            throw new IllegalStateException("Skill directory not found: " + skillDir);
        }
    }

    /**
     * 校验 todolist_steps 与 step_id 一致性。
     */
    public static void validateTodolistSteps(EdpConfig edpConfig) {
        List<EdpConfig.TodolistStep> steps = edpConfig.getTodolistSteps();
        if (steps != null) {
            for (EdpConfig.TodolistStep step : steps) {
                if ("_placeholder_".equals(step.getSkill())) {
                    throw new IllegalStateException("TodolistSteps contains placeholder step. "
                        + "Set EDP_AGENT_ACTIVE_SCENARIO or check scenario-config.yaml.");
                }
            }
        }
    }

    /**
     * 校验场景配置（方案 B：从 scenarioHome 直接定位）。
     *
     * scenarioHome 已指向活动场景目录，直接在该目录下校验 scenario-config.yaml。
     *
     * @param scenarioHome 活动场景目录路径（绝对路径）
     */
    public static void validateScenarioConfig(Path scenarioHome) {
        if (scenarioHome == null) {
            LOGGER.info("No scenarioHome configured, skipping scenario validation.");
            return;
        }
        if (!Files.exists(scenarioHome)) {
            throw new IllegalStateException("scenarioHome directory not found: " + scenarioHome);
        }
        try {
            Path scenarioPath = ScenarioConfigLoader.findScenarioFile(scenarioHome);
            ScenarioConfig config = ScenarioConfigLoader.loadScenarioConfig(scenarioPath);
            LOGGER.info("Scenario config validated: name={}, todolistSteps={}",
                config.getName(),
                config.getTodolistSteps() != null ? config.getTodolistSteps().size() : 0);
            if (config.getTodolistSteps() != null) {
                for (EdpConfig.TodolistStep step : config.getTodolistSteps()) {
                    if ("_placeholder_".equals(step.getSkill())) {
                        throw new IllegalStateException("Scenario todolist_steps contains placeholder. Check " + scenarioPath);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load scenario config from scenarioHome: " + e.getMessage());
        }
    }

    /**
     * 校验 skill_routing 中声明的 Skill 在 skills 目录中存在。
     */
    public static void validateSkillRouting(ScenarioConfig scenario, Path skillsDir) {
        if (scenario == null || scenario.getSkillRouting() == null) return;
        for (ScenarioSkillRouting routing : scenario.getSkillRouting()) {
            Path skillDir = skillsDir.resolve(routing.getSkill());
            if (!Files.exists(skillDir)) {
                throw new IllegalStateException("Skill routing references non-existent skill: " + routing.getSkill());
            }
        }
    }
}
