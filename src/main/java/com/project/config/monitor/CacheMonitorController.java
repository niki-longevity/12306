package com.project.config.monitor;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cache/monitor")
@RequiredArgsConstructor
public class CacheMonitorController {

    private final CacheMemoryMonitor cacheMemoryMonitor;

    /**
     * 手动触发缓存内存统计
     */
    @GetMapping("/stat-memory")
    public String statCacheMemory() {
        cacheMemoryMonitor.statCacheMemory();
        return "缓存内存统计已触发，详见日志！";
    }
}