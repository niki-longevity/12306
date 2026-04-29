package com.project.cache.warmup;

import com.project.pojo.bo.TicketListBO;
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

/**
 * 车票区间余票 计算与缓存类
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketStockCalculator {

    private final CacheManager ticketCacheManager;     // 余票15秒小缓存
    private final CacheManager trainStopCacheManager;  // 车站大缓存

    // 暴露为 public 常量，供原 Service 初始化数据使用
    public static final String SEAT_TYPE_BUSINESS = "business";
    public static final String SEAT_TYPE_FIRST = "firstClass";
    public static final String SEAT_TYPE_SECOND = "secondClass";

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

    /**
     * 获取区间余票（核心：懒加载缓存模式）
     */
    public StockInfo getSectionStock(LocalDate date, String start, String end, String trainCode, int startIndex, int endIndex) {
        // Key 加上 trainCode，因为同一路线有多个车次
        String cacheKey = String.format("%s:%s:%s:%s", date, start, end, trainCode);
        Cache ticketCache = ticketCacheManager.getCache("ticketCache");

        if (ticketCache == null) {
            return calculateFromJvm(date, trainCode, startIndex, endIndex);
        }

        // 懒加载：如果 ticketCache 中存在且没过期，直接返回 value
        // 如果不存在或已过期(15秒)，则执行 Callable 中的逻辑去 trainStopCache 计算并放入 ticketCache
        return ticketCache.get(cacheKey, () -> {
            log.debug("15秒余票缓存未命中或已过期，触发计算，CacheKey: {}", cacheKey);
            return calculateFromJvm(date, trainCode, startIndex, endIndex);
        });
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
                .businessNum(calculateSeatStock(trainBO, SEAT_TYPE_BUSINESS, startSection, endSection))
                .firstClassNum(calculateSeatStock(trainBO, SEAT_TYPE_FIRST, startSection, endSection))
                .secondClassNum(calculateSeatStock(trainBO, SEAT_TYPE_SECOND, startSection, endSection))
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