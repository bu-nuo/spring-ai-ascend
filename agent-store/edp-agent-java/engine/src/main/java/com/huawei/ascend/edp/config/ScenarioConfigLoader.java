package com.huawei.ascend.edp.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 场景配置发现与加载器。
 *
 * V2 方案 B：场景路径由 Spring Boot @Value 注入（scenarioHome），
 * 不再依赖 yamlDir.resolve(basePath) 解析。
 *
 * findScenarioFile 直接从 scenarioHome 目录搜索 scenario-config.yaml。
 */
public class ScenarioConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioConfigLoader.class);

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * 场景文件发现（方案 B：从 scenarioHome 直接定位）。
     *
     * scenarioHome 已经指向活动场景目录（如 scenarios/wealth-demo），
     * 直接在该目录下搜索 scenario-config.yaml。
     *
     * @param scenarioHome 活动场景目录路径（绝对路径）
     * @return scenario-config.yaml 文件路径
     * @throws IOException 场景文件不存在时抛出
     */
    public static Path findScenarioFile(Path scenarioHome) throws IOException {
        Path configFile = scenarioHome.resolve("scenario-config.yaml");

        if (Files.exists(configFile)) {
            LOGGER.info("Scenario config found: {}", configFile);
            return configFile;
        }

        throw new IOException("Scenario config not found: " + configFile);
    }

    /**
     * 加载场景配置。
     *
     * 直接从 .yaml 文件解析为 ScenarioConfig，无需 frontmatter 提取。
     *
     * @param scenarioPath scenario-config.yaml 文件路径
     * @return ScenarioConfig
     */
    public static ScenarioConfig loadScenarioConfig(Path scenarioPath) throws IOException {
        String content = Files.readString(scenarioPath);
        return YAML_MAPPER.readValue(content, ScenarioConfig.class);
    }
}
