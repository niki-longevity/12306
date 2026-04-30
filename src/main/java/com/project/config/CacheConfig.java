package com.project.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.cache.warmup.TicketStockCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

@Configuration
//@EnableCaching // 开启缓存注解（可选，这里直接用CacheManager操作）
public class CacheConfig {

    @Autowired
    @Lazy
    private TicketStockCalculator ticketStockCalculator;

     //配置车次经停站缓存管理器，程序启动时加载，程序关闭时自动销毁
    @Bean("trainStopCacheManager") // 指定bean名称，和预热类注入的名称对应
    public CacheManager trainStopCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("trainStopCache"); // 指定缓存名称
        // 配置缓存参数：
        // 1. 最大容量：10000（足够存储 1天 高铁车次）
        // 2. 过期时间：15天
        // 3. 软引用：内存不足时自动回收（可选）
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .recordStats()  // 记录缓存命中率
                .maximumSize(10000)
//                .refreshAfterWrite(100, TimeUnit.SECONDS)
                .expireAfterWrite(15, TimeUnit.DAYS);
//                .softValues();
        cacheManager.setCaffeine(caffeine);
        return cacheManager;
    }

    // ====================== 余票信息缓存 ======================
    @Bean("ticketCacheManager")
    public CacheManager ticketCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("ticketCache");

        // ✅ 核心：配置自动加载器，15秒刷新时自动执行你的余票计算逻辑
        cacheManager.setCacheLoader(cacheKey -> {
            // 解析你的缓存key：格式是 %s:%s:%s:%s (date:start:end:trainCode)
            String[] keyArr = cacheKey.toString().split(":");
            LocalDate date = LocalDate.parse(keyArr[0]);
            int start = Integer.parseInt(keyArr[1]);
            int end = Integer.parseInt(keyArr[2]);
            String trainCode = keyArr[3];

            // ✅ 直接调用你原有的计算方法！自动刷新最新余票
            return ticketStockCalculator.calculateFromJvm(date, trainCode, start, end);
        });

        // ✅ 保留 refreshAfterWrite，现在不会报错了！
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .recordStats()
                .maximumSize(100000)
                .refreshAfterWrite(15, TimeUnit.SECONDS) // 15秒自动刷新余票
                .expireAfterWrite(1, TimeUnit.DAYS);
        cacheManager.setCaffeine(caffeine);
        return cacheManager;
    }
}