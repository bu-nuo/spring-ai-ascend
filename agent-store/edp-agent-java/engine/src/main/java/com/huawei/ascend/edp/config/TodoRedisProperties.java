package com.huawei.ascend.edp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Todo 存储相关配置属性。
 *
 * <p>对应 application.yml 中 {@code edpa.redis.todo} 前缀的配置项。</p>
 */
@ConfigurationProperties(prefix = "edpa.redis.todo")
public class TodoRedisProperties {

    /** Redis key 前缀，默认 edpa。 */
    private String keyPrefix = "edpa";

    /** Todo 数据 TTL（秒），默认 3600。 */
    private int ttlSeconds = 3600;

    /** 读取时是否刷新 TTL，默认 true。 */
    private boolean refreshOnRead = true;

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public boolean isRefreshOnRead() {
        return refreshOnRead;
    }

    public void setRefreshOnRead(boolean refreshOnRead) {
        this.refreshOnRead = refreshOnRead;
    }
}
