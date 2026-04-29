package com.project.config.monitor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.project.pojo.bo.TicketListBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationUtils;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Caffeine 缓存内存占用统计工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheMemoryMonitor {

    private final CacheManager trainStopCacheManager;
    private static final String CACHE_NAME = "trainStopCache";

    /**
     * 手动触发内存统计（可通过接口/定时任务调用）
     */
    public void statCacheMemory() {
        // 1. 获取 Caffeine 缓存实例（强转获取底层 Caffeine Cache）
        org.springframework.cache.Cache springCache = trainStopCacheManager.getCache(CACHE_NAME);
        if (springCache == null) {
            log.error("缓存 {} 不存在", CACHE_NAME);
            return;
        }

        // 强转获取 Caffeine 原生 Cache（Spring CaffeineCache 内置 delegate）
        Cache<Object, Object> caffeineCache = (Cache<Object, Object>) springCache.getNativeCache();

        // 2. 获取缓存基础统计（条目数、命中数等）
        CacheStats stats = caffeineCache.stats();
        long entryCount = caffeineCache.estimatedSize(); // 缓存条目数（车次数量）
        log.info("===== Caffeine 缓存基础统计 =====");
        log.info("缓存条目数（车次数量）: {}", entryCount);
        log.info("缓存命中数: {}", stats.hitCount());
        log.info("缓存未命中数: {}", stats.missCount());

        // 3. 计算内存占用（核心）
        if (entryCount == 0) {
            log.warn("缓存无数据，跳过内存统计");
            return;
        }

        // 方式1：用 Apache Commons Lang3 计算（简单，生产可用）
        long totalMemoryBytes = 0;
        long avgMemoryBytesPerEntry = 0;

        // 获取缓存所有条目（ConcurrentMap）
        ConcurrentMap<Object, Object> cacheMap = caffeineCache.asMap();
        for (Map.Entry<Object, Object> entry : cacheMap.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof TicketListBO) {
                // 计算单个 TicketListBO 的深内存（序列化后字节数，近似内存占用）
                byte[] serialize = SerializationUtils.serialize((Serializable) value);
                totalMemoryBytes += serialize.length;
            }
        }

        // 方式2：用 JOL 计算（更精准，适合测试/排查，生产慎用）
        // long totalMemoryBytesJol = 0;
        // for (Map.Entry<Object, Object> entry : cacheMap.entrySet()) {
        //     Object value = entry.getValue();
        //     if (value instanceof TicketListBO) {
        //         // 计算对象的深内存（包含所有引用）
        //         GraphLayout layout = GraphLayout.parseInstance(value);
        //         totalMemoryBytesJol += layout.totalSize();
        //     }
        // }

        // 4. 格式化输出（转换为 KB/MB）
        avgMemoryBytesPerEntry = totalMemoryBytes / entryCount;
        double totalMemoryKB = totalMemoryBytes / 1024.0;
        double totalMemoryMB = totalMemoryKB / 1024.0;
        double avgMemoryKB = avgMemoryBytesPerEntry / 1024.0;

        log.info("===== Caffeine 缓存内存统计（Apache Commons Lang3） =====");
        log.info("缓存总内存占用: {} 字节 | {} KB | {} MB",
                totalMemoryBytes, totalMemoryKB, totalMemoryMB);
        log.info("单条车次数据内存占用: {} 字节 | {} KB",
                avgMemoryBytesPerEntry, avgMemoryKB);
    }
}