package com.project.ticket.cache.warmup;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.enums.SeatType;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 车票区间余票 计算与缓存类
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketStockCalculator {

    private final CacheManager trainStopCacheManager;  // 车站大缓存

    private LoadingCache<String, StockInfo> ticketCache;

    public static final int STOCK_STATUS_SUFFICIENT = 2; // 高库存
    public static final int STOCK_STATUS_LOW = 1;         // 低库存
    public static final int STOCK_STATUS_NONE = 0;        // 无票
    public static final int SUFFICIENT_STOCK_VALUE = 11;  // 高库存默认返回值

    @Data
    @Builder
    public static class StockInfo {
        private Integer businessNum;
        private Integer firstClassNum;
        private Integer secondClassNum;

        public static StockInfo empty() {
            return StockInfo.builder()
                    .businessNum(0)
                    .firstClassNum(0)
                    .secondClassNum(0)
                    .build();
        }
    }

    @PostConstruct
    public void init() {
        this.ticketCache = Caffeine.newBuilder()
                .recordStats()
                .maximumSize(100000)
                .refreshAfterWrite(15, TimeUnit.SECONDS)
                .expireAfterWrite(1, TimeUnit.DAYS)
                .build(key -> {
                    // key format: date:startIndex:endIndex:trainCode
                    String[] parts = key.split(":");
                    LocalDate date = LocalDate.parse(parts[0]);
                    int startIndex = Integer.parseInt(parts[1]);
                    int endIndex = Integer.parseInt(parts[2]);
                    String trainCode = parts[3];
                    log.debug("15秒余票缓存未命中或已过期，触发计算，CacheKey: {}", key);
                    return calculateFromJvm(date, trainCode, startIndex, endIndex);
                });
    }

    /**
     * 获取区间余票（核心：懒加载缓存模式）
     */
    public StockInfo getSectionStock(LocalDate date, String start, String end, String trainCode, int startIndex, int endIndex) {
        String cacheKey = String.format("%s:%s:%s:%s", date, startIndex, endIndex, trainCode);
        try {
            return ticketCache.get(cacheKey);
        } catch (Exception e) {
            log.error("Failed to get ticket stock from cache for key={}", cacheKey, e);
            return calculateFromJvm(date, trainCode, startIndex, endIndex);
        }
    }

    /**
     * 实际去大缓存提取并计算最小值的逻辑
     */
    public StockInfo calculateFromJvm(LocalDate date, String trainCode, int startIndex, int endIndex) {
        Cache trainStopCache = trainStopCacheManager.getCache("trainStopCache");
        if (trainStopCache == null) {
            return StockInfo.empty();
        }

        String trainKey = String.format("%s:%s", date, trainCode);
        TicketListBO trainBO = trainStopCache.get(trainKey, TicketListBO.class);
        if (trainBO == null) {
            return StockInfo.empty();
        }

        int startSection = startIndex;
        int endSection = endIndex - 1;

        if (startSection > endSection) {
            return StockInfo.empty();
        }

        return StockInfo.builder()
                .businessNum(calculateSeatStock(trainBO, SeatType.BUSINESS.getCacheKey(), startSection, endSection))
                .firstClassNum(calculateSeatStock(trainBO, SeatType.FIRST.getCacheKey(), startSection, endSection))
                .secondClassNum(calculateSeatStock(trainBO, SeatType.SECOND.getCacheKey(), startSection, endSection))
                .build();
    }

    private int calculateSeatStock(TicketListBO trainBO, String seatType, int startSection, int endSection) {
        Map<String, TicketListBO.IntervalStockStatus> intervalStockMap = trainBO.getIntervalStockMap();
        if (CollectionUtils.isEmpty(intervalStockMap)) {
            return 0;
        }

        boolean hasNoneStock = false;
        List<Integer> lowStockValues = new ArrayList<>();

        for (int section = startSection; section <= endSection; section++) {
            String mapKey = seatType + "_" + section;
            TicketListBO.IntervalStockStatus status = intervalStockMap.get(mapKey);
            if (status == null) {
                hasNoneStock = true;
                break;
            }

            int statusCode = status.getStatusCode();
            if (statusCode == STOCK_STATUS_NONE) {
                hasNoneStock = true;
                break;
            } else if (statusCode == STOCK_STATUS_LOW) {
                lowStockValues.add(status.getRealStock() == null ? 0 : status.getRealStock());
            }
        }

        if (hasNoneStock) return 0;
        if (!lowStockValues.isEmpty()) return lowStockValues.stream().min(Integer::compareTo).orElse(0);
        return SUFFICIENT_STOCK_VALUE;
    }
}