package com.huawei.ascend.edp.todo;

import com.huawei.ascend.edp.config.TodoRedisProperties;
import com.openjiuwen.harness.tools.TodoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis Todo 存储实现。
 *
 * <p>提供 Todo 列表的 Redis 读写能力，支持 TTL 和读取时刷新 TTL。
 * 作为 UC-03~UC-11 的主路径数据源，替代文件存储。</p>
 */
public class RedisTodoStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisTodoStore.class);

    private final TodoRedisProperties properties;
    private final RedisTemplate<String, List<TodoItem>> redisTemplate;

    public RedisTodoStore(TodoRedisProperties properties) {
        this.properties = properties;
        this.redisTemplate = createRedisTemplate();
    }

    private RedisTemplate<String, List<TodoItem>> createRedisTemplate() {
        RedisTemplate<String, List<TodoItem>> template = new RedisTemplate<>();
        template.setConnectionFactory(createConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    private RedisConnectionFactory createConnectionFactory() {
        // 使用 Spring Boot 自动配置的连接工厂；此处简化，依赖 application.yml 中 spring.redis 配置
        // 实际由 Spring 容器注入，这里仅在非 Spring 环境兜底
        return new LettuceConnectionFactory();
    }

    /**
     * 保存 Todo 列表到 Redis，设置 TTL。
     */
    public void save(String sessionId, List<TodoItem> todos) {
        String key = buildKey(sessionId);
        redisTemplate.opsForValue().set(key, todos, properties.getTtlSeconds(), TimeUnit.SECONDS);
        LOGGER.info("[REDIS-TODO] SAVE sid={} items={} key={} ttl={}s",
                sessionId, todos != null ? todos.size() : 0, key, properties.getTtlSeconds());
    }

    /**
     * 从 Redis 加载 Todo 列表，读取时刷新 TTL。
     */
    public List<TodoItem> load(String sessionId) {
        String key = buildKey(sessionId);
        List<TodoItem> todos = redisTemplate.opsForValue().get(key);
        if (todos != null && properties.isRefreshOnRead()) {
            redisTemplate.expire(key, properties.getTtlSeconds(), TimeUnit.SECONDS);
        }
        LOGGER.info("[REDIS-TODO] LOAD sid={} items={} key={} hit={}",
                sessionId, todos != null ? todos.size() : 0, key, todos != null);
        return todos;
    }

    /**
     * 检查 Redis 中是否存在该 session 的 Todo 数据。
     */
    public boolean exists(String sessionId) {
        String key = buildKey(sessionId);
        Boolean exists = redisTemplate.hasKey(key);
        boolean result = Boolean.TRUE.equals(exists);
        LOGGER.info("[REDIS-TODO] EXISTS sid={} key={} exists={}", sessionId, key, result);
        return result;
    }

    /**
     * 删除 Redis 中该 session 的 Todo 数据。
     */
    public void delete(String sessionId) {
        String key = buildKey(sessionId);
        Boolean deleted = redisTemplate.delete(key);
        LOGGER.info("[REDIS-TODO] DELETE sid={} key={} deleted={}", sessionId, key, deleted);
    }

    private String buildKey(String sessionId) {
        return properties.getKeyPrefix() + ":todo:" + sessionId;
    }
}
