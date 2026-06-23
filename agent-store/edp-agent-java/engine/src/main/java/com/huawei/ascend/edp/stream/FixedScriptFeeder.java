package com.huawei.ascend.edp.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 固定思考脚本输出器。
 *
 * 根据用户 query 关键词匹配或默认脚本，生成思考块帧。
 */
public class FixedScriptFeeder {

    private static final Logger LOGGER = LoggerFactory.getLogger(FixedScriptFeeder.class);

    /**
     * 默认思考脚本列表。
     */
    private List<String> defaultScripts;

    /**
     * 按用户 query 关键词匹配的脚本配置。
     */
    private List<Map<String, Object>> queryPatterns;

    public FixedScriptFeeder(List<String> defaultScripts, List<Map<String, Object>> queryPatterns) {
        this.defaultScripts = defaultScripts;
        this.queryPatterns = queryPatterns;
    }

    /**
     * 根据用户 query 选择思考脚本。
     *
     * @param userQuery 用户输入
     * @return 匹配的脚本列表，无匹配时返回默认脚本
     */
    public List<String> selectScripts(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return defaultScripts;
        }
        if (queryPatterns != null) {
            for (Map<String, Object> pattern : queryPatterns) {
                List<String> keywords = (List<String>) pattern.get("keywords");
                if (keywords != null) {
                    for (String keyword : keywords) {
                        if (userQuery.contains(keyword)) {
                            return (List<String>) pattern.get("scripts");
                        }
                    }
                }
            }
        }
        return defaultScripts;
    }

    public List<String> getDefaultScripts() { return defaultScripts; }
    public void setDefaultScripts(List<String> defaultScripts) { this.defaultScripts = defaultScripts; }

    public List<Map<String, Object>> getQueryPatterns() { return queryPatterns; }
    public void setQueryPatterns(List<Map<String, Object>> queryPatterns) { this.queryPatterns = queryPatterns; }
}
