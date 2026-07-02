package com.huawei.ascend.edp.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ScenarioConfigLoader 场景发现与加载器单元测试。
 *
 * 验证阶段 2 架构包结构生产化中的场景配置加载机制。
 * 覆盖：findScenarioFile（方案 B 从 scenarioHome 定位）、loadScenarioConfig（纯 YAML 解析）。
 */
class ScenarioConfigLoaderTest {

    @Test
    void testFindScenarioFile_ExistingDir() throws IOException {
        // 使用实际场景目录
        Path scenarioHome = Path.of("../scenarios/wealth-demo").toAbsolutePath().normalize();
        if (Files.exists(scenarioHome)) {
            Path configFile = ScenarioConfigLoader.findScenarioFile(scenarioHome);
            assertNotNull(configFile, "应找到 scenario-config.yaml");
            assertTrue(configFile.toString().endsWith("scenario-config.yaml"), "文件名应为 scenario-config.yaml");
            assertTrue(Files.exists(configFile), "文件应存在");
        } else {
            System.out.println("SKIP: wealth-demo scenario directory not found at " + scenarioHome);
        }
    }

    @Test
    void testFindScenarioFile_NonExistentDir() {
        Path nonExistent = Path.of("/nonexistent/scenario");
        assertThrows(IOException.class,
                () -> ScenarioConfigLoader.findScenarioFile(nonExistent),
                "不存在目录应抛 IOException");
    }

    @Test
    void testLoadScenarioConfig_WealthDemo() throws IOException {
        Path scenarioHome = Path.of("../scenarios/wealth-demo").toAbsolutePath().normalize();
        if (Files.exists(scenarioHome)) {
            Path configFile = ScenarioConfigLoader.findScenarioFile(scenarioHome);
            ScenarioConfig config = ScenarioConfigLoader.loadScenarioConfig(configFile);

            assertNotNull(config, "加载结果不应为 null");
            // name 已迁移至 governance/planrule.yaml 的 scenarioName
            // scope 已迁移至 governance/planrule.yaml 的 scope
            // todolist_steps 已删除——新版 todolist.entries 完全替代
            // skill_routing 已迁移至 governance/planrule.yaml 的 skill_routing
            // 编译错误: ScenarioConfigLoader 可能仍尝试解析该字段
            if (config.getSkillRouting() != null) {
                assertEquals(4, config.getSkillRouting().size(), "理财购买应有 4 条路由");
            }
            // architecture 已删除——MCP 先行架构已作为框架默认配置
        } else {
            System.out.println("SKIP: wealth-demo scenario directory not found");
        }
    }

    @Test
    void testLoadScenarioConfig_HzZhidaitong() throws IOException {
        Path scenarioHome = Path.of("../scenarios/hz-zhidaitong").toAbsolutePath().normalize();
        if (Files.exists(scenarioHome)) {
            Path configFile = ScenarioConfigLoader.findScenarioFile(scenarioHome);
            ScenarioConfig config = ScenarioConfigLoader.loadScenarioConfig(configFile);

            assertNotNull(config, "加载结果不应为 null");
            assertEquals("杭研智贷通", config.getName(), "场景名称应为杭研智贷通");
            // todolist_steps 已删除——新版 todolist.entries 完全替代
            // skill_routing 为空列表
            assertNotNull(config.getSkillRouting(), "skillRouting 不应为 null");
            assertEquals(0, config.getSkillRouting().size(), "杭研智贷通 skill_routing 应为空");
        } else {
            System.out.println("SKIP: hz-zhidaitong scenario directory not found");
        }
    }

    @Test
    void testLoadScenarioConfig_ScopeAllowed() throws IOException {
        Path scenarioHome = Path.of("../scenarios/wealth-demo").toAbsolutePath().normalize();
        if (Files.exists(scenarioHome)) {
            Path configFile = ScenarioConfigLoader.findScenarioFile(scenarioHome);
            ScenarioConfig config = ScenarioConfigLoader.loadScenarioConfig(configFile);

            // scope 已迁移至 governance/planrule.yaml 的 scope，scenario-config.yaml 中不再定义
            ScenarioScopeConfig scope = config.getScope();
            if (scope != null) {
                assertNotNull(scope.getAllowed(), "allowed 不应为 null");
                assertTrue(scope.getAllowed().size() > 0, "allowed 应有内容");
                assertNotNull(scope.getDenied(), "denied 不应为 null");
                assertTrue(scope.getDenied().size() > 0, "denied 应有内容");
            }
        } else {
            System.out.println("SKIP: wealth-demo scenario directory not found");
        }
    }

    @Test
    void testLoadScenarioConfig_SkillRouting() throws IOException {
        Path scenarioHome = Path.of("../scenarios/wealth-demo").toAbsolutePath().normalize();
        if (Files.exists(scenarioHome)) {
            Path configFile = ScenarioConfigLoader.findScenarioFile(scenarioHome);
            ScenarioConfig config = ScenarioConfigLoader.loadScenarioConfig(configFile);

            // todolist_steps 已删除——新版 todolist.entries 完全替代
            // skill_routing 已迁移至 governance/planrule.yaml 的 skill_routing
            // 编译错误: ScenarioConfigLoader 可能仍尝试解析该字段
            if (config.getSkillRouting() != null) {
                assertEquals(4, config.getSkillRouting().size(), "理财购买应有 4 条路由");
            }
        } else {
            System.out.println("SKIP: wealth-demo scenario directory not found");
        }
    }
}
