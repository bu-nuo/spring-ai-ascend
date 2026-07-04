package com.huawei.ascend.edp.config;

import com.huawei.ascend.edp.todo.RedisTodoStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 配置类。
 *
 * <p>创建 {@link RedisTodoStore} Bean 并通过静态字段持有，
 * 供非 Spring 管理的类（如 {@code EdpaAgentEnhancer}）通过 {@link #getRedisTodoStore()} 访问。</p>
 */
@Configuration
@EnableConfigurationProperties(TodoRedisProperties.class)
public class RedisConfig {

    private static RedisTodoStore redisTodoStore;

    @Bean
    public RedisTodoStore redisTodoStore(TodoRedisProperties properties) {
        redisTodoStore = new RedisTodoStore(properties);
        return redisTodoStore;
    }

    public static RedisTodoStore getRedisTodoStore() {
        return redisTodoStore;
    }
}
