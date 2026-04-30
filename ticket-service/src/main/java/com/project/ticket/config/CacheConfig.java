package com.project.ticket.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.ticket.cache.warmup.TrainStopCacheLoader;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean("trainStopCacheManager")
    public CacheManager trainStopCacheManager(TrainStopCacheLoader cacheLoader) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("trainStopCache");
        cacheManager.setCacheLoader(cacheLoader);
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .recordStats()
                .maximumSize(10000)
                .refreshAfterWrite(10, TimeUnit.MINUTES)
                .expireAfterAccess(1, TimeUnit.DAYS);
        cacheManager.setCaffeine(caffeine);
        return cacheManager;
    }
}