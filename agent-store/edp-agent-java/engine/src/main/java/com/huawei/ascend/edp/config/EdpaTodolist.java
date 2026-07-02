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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * EDPAgent Todo 数据层（单一文件）。
 *
 * <p>命名说明（v2 §10.2）：「Catalog」是 todo 内部 {@code entries.catalog_id} 字段的语义，
 * 留在字段层；承载它的容器类用 Todo 命名。故本类从 {@code EdpaTodoCatalog} 重命名为 {@code EdpaTodolist}，
 * 直接映射 scenario-config.yaml 的 {@code todolist} 一级配置段。</p>
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>加载 scenario-config.yaml 的 {@code todolist} 一级配置段（entries + dynamic_paths）。</li>
 *     <li>向后兼容：未配置 {@code todolist} 时，从旧版 {@code todolist_steps} 生成 entries。</li>
 *     <li>提供 O(1) 的 catalog_id 查询和路径规则访问，供 Rail 层注入 prompt 和参数补全使用。</li>
 *     <li>启动期校验：catalog_id 唯一、depends_on 引用存在、skip_steps 引用存在、依赖图无环。</li>
 * </ul>
 *
 * <p>{@link TodoEntry} 和 {@link DynamicPath} 作为本类的 static inner class，不单独拆分文件。
 * {@code catalog_id} 作为 entry 的内部主键字段名保留（字段层语义）。</p>
 */
public class EdpaTodolist {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaTodolist.class);

    /**
     * YAML 解析器，与 {@link ScenarioConfigLoader} 保持一致的 Jackson 配置。
     */
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * 任务定义列表（保持配置顺序）。
     */
    private final List<TodoEntry> entries;

    /**
     * 动态路径规则列表。
     */
    private final List<DynamicPath> dynamicPaths;

    /**
     * catalog_id 到 TodoEntry 的索引，O(1) 查询。
     */
    private final Map<String, TodoEntry> index;

    /**
     * 是否来自旧版 todolist_steps（向后兼容模式）。
     */
    private final boolean legacyMode;

    /**
     * 从 scenario-config.yaml 加载 todo 数据。
     *
     * <p>加载策略：</p>
     * <ol>
     *     <li>优先读取 {@code todolist} 一级配置段（entries + dynamic_paths）。</li>
     *     <li>未配置 {@code todolist} 时，回退到旧版 {@code todolist_steps}，生成无 description 的 entries，
     *         且 dynamic_paths 为空（旧版不支持动态路径）。</li>
     * </ol>
     *
     * @param yamlPath scenario-config.yaml 路径
     * @throws IllegalArgumentException 校验失败（catalog_id 重复、引用不存在、依赖图有环）
     */
    @SuppressWarnings("unchecked")
    public EdpaTodolist(Path yamlPath) {
        Objects.requireNonNull(yamlPath, "scenario-config.yaml path must not be null");
        Map<String, Object> root = loadYaml(yamlPath);

        Object todolistNode = root.get("todolist");
        if (todolistNode instanceof Map<?, ?> todolistMap) {
            this.entries = parseEntries(asMapList(todolistMap.get("entries")));
            this.dynamicPaths = parseDynamicPaths(asMapList(todolistMap.get("dynamic_paths")));
            this.legacyMode = false;
            LOGGER.info("EdpaTodolist loaded from todolist section: entries={}, dynamicPaths={}",
                    entries.size(), dynamicPaths.size());
        } else {
            // 向后兼容：从旧版 todolist_steps 生成 entries。
            this.entries = parseLegacySteps(asMapList(root.get("todolist_steps")));
            this.dynamicPaths = Collections.emptyList();
            this.legacyMode = true;
            LOGGER.info("EdpaTodolist loaded from legacy todolist_steps: entries={}, dynamicPaths=0 (legacy mode)",
                    entries.size());
        }

        this.index = new LinkedHashMap<>();
        for (TodoEntry entry : entries) {
            if (index.put(entry.getCatalogId(), entry) != null) {
                throw new IllegalArgumentException(
                        "Duplicate catalog_id in todolist.entries: " + entry.getCatalogId());
            }
        }

        validateReferences();
        validateAcyclic();
    }

    public List<TodoEntry> getEntries() {
        return entries;
    }

    public List<DynamicPath> getDynamicPaths() {
        return dynamicPaths;
    }

    public TodoEntry findByCatalogId(String catalogId) {
        return index.get(catalogId);
    }

    /**
     * 是否处于旧版 todolist_steps 兼容模式。
     *
     * @return 旧版模式返回 true，使用新 todolist 段返回 false
     */
    public boolean isLegacyMode() {
        return legacyMode;
    }

    /**
     * 是否存在动态路径规则。
     *
     * @return 存在 dynamic_paths 返回 true
     */
    public boolean hasDynamicPaths() {
        return !dynamicPaths.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path yamlPath) {
        try {
            String content = Files.readString(yamlPath);
            return YAML_MAPPER.readValue(content, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load scenario-config.yaml: " + yamlPath, e);
        }
    }

    private static List<Map<String, Object>> asMapList(Object node) {
        if (node instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> typed = new LinkedHashMap<>();
                    map.forEach((k, v) -> typed.put(String.valueOf(k), v));
                    result.add(typed);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    private static List<TodoEntry> parseEntries(List<Map<String, Object>> rawEntries) {
        List<TodoEntry> result = new ArrayList<>(rawEntries.size());
        for (Map<String, Object> raw : rawEntries) {
            String catalogId = str(raw.get("catalog_id"));
            if (catalogId == null || catalogId.isBlank()) {
                throw new IllegalArgumentException("todolist.entries item missing catalog_id: " + raw);
            }
            result.add(new TodoEntry(
                    catalogId,
                    str(raw.get("content")),
                    str(raw.get("description")),
                    strList(raw.get("depends_on")),
                    str(raw.get("skill"))));
        }
        return result;
    }

    private static List<TodoEntry> parseLegacySteps(List<Map<String, Object>> rawSteps) {
        List<TodoEntry> result = new ArrayList<>(rawSteps.size());
        for (Map<String, Object> raw : rawSteps) {
            Object stepId = raw.get("step_id");
            String catalogId = stepId != null ? "step_" + stepId : "step_" + (result.size() + 1);
            result.add(new TodoEntry(
                    catalogId,
                    str(raw.get("content")),
                    null,
                    Collections.emptyList(),
                    str(raw.get("skill"))));
        }
        return result;
    }

    private static List<DynamicPath> parseDynamicPaths(List<Map<String, Object>> rawPaths) {
        List<DynamicPath> result = new ArrayList<>(rawPaths.size());
        for (Map<String, Object> raw : rawPaths) {
            result.add(new DynamicPath(
                    str(raw.get("path_id")),
                    str(raw.get("description")),
                    str(raw.get("trigger")),
                    strList(raw.get("skip_steps")),
                    str(raw.get("redirect"))));
        }
        return result;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    /**
     * 校验 depends_on 和 dynamic_paths.skip_steps 引用的 catalog_id 都存在。
     */
    private void validateReferences() {
        for (TodoEntry entry : entries) {
            for (String dep : entry.getDependsOn()) {
                if (!index.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "todolist.entries[" + entry.getCatalogId() + "].depends_on references unknown catalog_id: " + dep);
                }
            }
        }
        for (DynamicPath path : dynamicPaths) {
            for (String skip : path.getSkipSteps()) {
                if (!index.containsKey(skip)) {
                    throw new IllegalArgumentException(
                            "todolist.dynamic_paths[" + path.getPathId() + "].skip_steps references unknown catalog_id: " + skip);
                }
            }
        }
    }

    /**
     * 校验 depends_on 构成的有向图无环（Kahn's 算法）。
     */
    private void validateAcyclic() {
        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (TodoEntry entry : entries) {
            graph.putIfAbsent(entry.getCatalogId(), new HashSet<>());
            inDegree.putIfAbsent(entry.getCatalogId(), 0);
        }
        for (TodoEntry entry : entries) {
            for (String dep : entry.getDependsOn()) {
                // 边 dep -> entry：dep 完成后 entry 才可执行
                graph.computeIfAbsent(dep, k -> new HashSet<>()).add(entry.getCatalogId());
                inDegree.merge(entry.getCatalogId(), 1, Integer::sum);
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }

        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (String next : graph.getOrDefault(current, Collections.emptySet())) {
                int remain = inDegree.merge(next, -1, Integer::sum);
                if (remain == 0) {
                    queue.add(next);
                }
            }
        }

        if (visited != entries.size()) {
            throw new IllegalArgumentException(
                    "todolist.entries dependency graph has a cycle (visited " + visited + " of " + entries.size() + ")");
        }
    }

    /**
     * 任务定义（catalog_id 为内部主键字段，含完整字段）。
     */
    public static class TodoEntry {
        private final String catalogId;
        private final String content;
        private final String description;
        private final List<String> dependsOn;
        private final String skill;

        public TodoEntry(String catalogId, String content, String description, List<String> dependsOn, String skill) {
            this.catalogId = catalogId;
            this.content = content;
            this.description = description;
            this.dependsOn = dependsOn == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(dependsOn));
            this.skill = skill;
        }

        public String getCatalogId() { return catalogId; }
        public String getContent() { return content; }
        public String getDescription() { return description; }
        public List<String> getDependsOn() { return dependsOn; }
        public String getSkill() { return skill; }
    }

    /**
     * 动态路径规则（LLM 根据业务结果自主判断是否切换）。
     */
    public static class DynamicPath {
        private final String pathId;
        private final String description;
        private final String trigger;
        private final List<String> skipSteps;
        private final String redirect;

        public DynamicPath(String pathId, String description, String trigger, List<String> skipSteps, String redirect) {
            this.pathId = pathId;
            this.description = description;
            this.trigger = trigger;
            this.skipSteps = skipSteps == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(skipSteps));
            this.redirect = redirect;
        }

        public String getPathId() { return pathId; }
        public String getDescription() { return description; }
        public String getTrigger() { return trigger; }
        public List<String> getSkipSteps() { return skipSteps; }
        public String getRedirect() { return redirect; }
    }
}
