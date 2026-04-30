package com.project.ticket.cache.warmup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caffeine CacheLoader: on Caffeine miss, load from Redis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainStopCacheLoader implements com.github.benmanes.caffeine.cache.CacheLoader<Object, Object> {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String REDIS_KEY_PREFIX = "TrainStop:";

    @Override
    public Object load(Object cacheKey) throws Exception {
        String key = cacheKey.toString();
        String redisKey = REDIS_KEY_PREFIX + key;
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        if (json == null) {
            log.warn("Redis miss for key={}", redisKey);
            return null;
        }
        log.debug("Caffeine miss, loaded from Redis: {}", key);
        return objectMapper.readValue(json, TicketListBO.class);
    }

    @Override
    public Object reload(Object key, Object oldValue) throws Exception {
        try {
            return load(key);
        } catch (Exception e) {
            log.error("Refresh failed for key={}, keeping old value", key, e);
            return oldValue;
        }
    }
}