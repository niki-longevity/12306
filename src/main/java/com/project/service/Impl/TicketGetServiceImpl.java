package com.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.util.concurrent.RateLimiter;
import com.project.mapper.TrainStopoverMapper;
import com.project.mapper.TrainTicketSectionMapper;
import com.project.pojo.bo.TicketListBO;
import com.project.pojo.dto.TicketListDTO;
import com.project.pojo.entity.TrainStopover;
import com.project.pojo.vo.TicketListVO;
import com.project.service.TicketGetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketGetServiceImpl implements TicketGetService {

    // ===== 基础配置 =====
    private static final double BUSINESS_SEAT_PRICE_PER_MILE = 1.5;  // 商务座单价
    private static final double FIRST_SEAT_PRICE_PER_MILE = 1.2;     // 一等座单价
    private static final double SECOND_SEAT_PRICE_PER_MILE = 0.8;    // 二等座单价
    // 座位类型常量（对应JVM缓存Key）
    private static final String SEAT_TYPE_BUSINESS = "business";
    private static final String SEAT_TYPE_FIRST = "firstClass";
    private static final String SEAT_TYPE_SECOND = "secondClass";
    // 库存状态常量
    private static final int STOCK_STATUS_SUFFICIENT = 2; // 高库存
    private static final int STOCK_STATUS_LOW = 1;         // 低库存
    private static final int STOCK_STATUS_NONE = 0;        // 无票
    private static final int SUFFICIENT_STOCK_VALUE = 11;  // 高库存默认返回值
    // 数据库限流：每秒最多20次查询（可根据数据库性能调整）
    private final RateLimiter dbRateLimiter = RateLimiter.create(20);

    // ===== 依赖注入 =====
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheManager trainStopCacheManager; // JVM缓存（Caffeine）
    private final TrainStopoverMapper trainStopoverMapper; // 经停站数据库Mapper
    private final TrainTicketSectionMapper trainTicketSectionMapper; // 库存数据库Mapper

    /**
     * 查询车票
     * @param ticketListDTO
     * @return 车票列表
     */
    @Override
    public List<TicketListVO> list(TicketListDTO ticketListDTO) {
        // 1、提取参数
        LocalDate date = ticketListDTO.getDate();
        String startStation = ticketListDTO.getStart();
        String endStation = ticketListDTO.getEnd();

        // 2、简单校验
        if (date == null || startStation == null || endStation == null
                || startStation.equals(endStation)) {
            log.warn("查票参数无效：date={}, start={}, end={}", date, startStation, endStation);
            return Collections.emptyList();
        }

        // 3、从Redis查询符合条件的车次
        String redisTrainKey = String.format("%s:%s:%s", date, startStation, endStation);
        List<String> trainCodes = stringRedisTemplate.opsForList().range(redisTrainKey, 0, -1);
        if (CollectionUtils.isEmpty(trainCodes)) {
            log.info("Redis中无符合条件的车次：key={}", redisTrainKey);
            return Collections.emptyList();
        }
        log.info("从Redis查询到车次数量：{}", trainCodes.size());

        // 4、从JVM缓存提取车次信息
        // TODO 应该缓存热点车次，避免缓存溢出；然后先降级查Redis；最后兜底查数据库（考虑异步查询策略）
        Map<String, TicketListBO> trainBoMap = getTrainBoFromJvmCache(date, trainCodes);
        List<String> missTrainCodes = trainCodes.stream()
                .filter(code -> !trainBoMap.containsKey(code))
                .toList();
        if (!CollectionUtils.isEmpty(missTrainCodes)) {
            log.warn("JVM缓存未命中车次数量：{}，开始降级查询", missTrainCodes.size());
            Map<String, TicketListBO> missTrainBoMap = getTrainBoFromDbWithLimit(date, missTrainCodes);
            // 降级查询结果放入JVM缓存（预热）
            putMissTrainBoToJvmCache(missTrainBoMap);
            // 合并缓存命中+降级结果
            trainBoMap.putAll(missTrainBoMap);
        }

        // 5、提取所有车次的站序 / 库存信息
        // 临时存储每个车次的核心业务信息
        Map<String, StationBizInfo> stationBizInfoMap = new HashMap<>();
        // 临时存储每个车次的库存值
        Map<String, StockInfo> stockInfoMap = new HashMap<>();

        for (String trainCode : trainCodes) {
            TicketListBO trainBO = trainBoMap.get(trainCode);
            if (trainBO == null) {
                // 无静态信息，库存默认全0
                stockInfoMap.put(trainCode, StockInfo.empty());
                continue;
            }
            // 提取站序信息
            StationBizInfo stationBizInfo = getStationBizInfo(trainBO, startStation, endStation);
            stationBizInfoMap.put(trainCode, stationBizInfo);
            // 计算库存值
            StockInfo stockInfo = calculateStockFromJvmCache(trainBO, stationBizInfo);
            stockInfoMap.put(trainCode, stockInfo);
        }

        // 6、封装VO返回
        List<TicketListVO> ticketListVOS = new ArrayList<>();
        for (String trainCode : trainCodes) {
            TicketListBO trainBO = trainBoMap.get(trainCode);
            StationBizInfo stationBizInfo = stationBizInfoMap.get(trainCode);
            StockInfo stockInfo = stockInfoMap.get(trainCode);

            // 基础字段赋值（无静态信息则用默认值）
            LocalTime startTime = null;
            LocalTime endTime = null;
            double businessPrice = 0.0;
            double firstClassPrice = 0.0;
            double secondClassPrice = 0.0;

            if (trainBO != null && stationBizInfo != null) {
                // 动态计算票价（里程差×单价，保留2位小数）
                double mileageDiff = stationBizInfo.getEndMileage() - stationBizInfo.getStartMileage();
                businessPrice = roundPrice(mileageDiff * BUSINESS_SEAT_PRICE_PER_MILE);
                firstClassPrice = roundPrice(mileageDiff * FIRST_SEAT_PRICE_PER_MILE);
                secondClassPrice = roundPrice(mileageDiff * SECOND_SEAT_PRICE_PER_MILE);
                startTime = stationBizInfo.getStartOutTime();
                endTime = stationBizInfo.getEndInTime();
            }

            // 封装VO（即使库存为0，也返回）
            TicketListVO vo = TicketListVO.builder()
                    .date(date)
                    .code(trainCode)
                    .start(startStation)
                    .startTime(startTime)
                    .end(endStation)
                    .endTime(endTime)
                    .businessNum(stockInfo.getBusinessNum())
                    .businessPrice(businessPrice)
                    .firstClassNum(stockInfo.getFirstClassNum())
                    .firstClassPrice(firstClassPrice)
                    .secondClassNum(stockInfo.getSecondClassNum())
                    .secondClassPrice(secondClassPrice)
                    .build();
            ticketListVOS.add(vo);
        }

        log.info("查票完成，返回车次数量：{}（含无库存车次）", ticketListVOS.size());
        return ticketListVOS;
    }

    // ===================== 核心方法 =====================

    /**
     * 从JVM缓存计算库存值
     * @param trainBO JVM缓存的车次信息
     * @param stationBizInfo 站序信息（可为null）
     * @return 库存信息（无有效站序则全0）
     */
    private StockInfo calculateStockFromJvmCache(TicketListBO trainBO, StationBizInfo stationBizInfo) {
        if (stationBizInfo == null) {
            // 无有效站序，库存全0
            return StockInfo.empty();
        }

        // 计算区间范围：站序start→end → 区间start→end-1
        int startSection = stationBizInfo.getStartIndex();
        int endSection = stationBizInfo.getEndIndex() - 1;
        if (startSection > endSection) {
            log.warn("车次{}区间无效：startSection={}, endSection={}", trainBO.getCode(), startSection, endSection);
            return StockInfo.empty();
        }

        // 计算3种座位类型的库存
        int businessNum = calculateSeatStockFromJvm(trainBO, SEAT_TYPE_BUSINESS, startSection, endSection);
        int firstClassNum = calculateSeatStockFromJvm(trainBO, SEAT_TYPE_FIRST, startSection, endSection);
        int secondClassNum = calculateSeatStockFromJvm(trainBO, SEAT_TYPE_SECOND, startSection, endSection);

        return StockInfo.builder()
                .businessNum(businessNum)
                .firstClassNum(firstClassNum)
                .secondClassNum(secondClassNum)
                .build();
    }

    /**
     * 计算单个座位类型的库存值
     * @param trainBO JVM缓存的车次信息
     * @param seatType 座位类型
     * @param startSection 起始区间
     * @param endSection 结束区间
     * @return 库存值（高库存=11，低库存=realStock，无票=0）
     */
    private int calculateSeatStockFromJvm(TicketListBO trainBO, String seatType, int startSection, int endSection) {
        Map<String, TicketListBO.IntervalStockStatus> intervalStockMap = trainBO.getIntervalStockMap();
        if (CollectionUtils.isEmpty(intervalStockMap)) {
            // 无JVM库存状态，默认0
            return 0;
        }

        // 遍历区间，判断状态并计算库存
        boolean hasNoneStock = false;
        List<Integer> lowStockValues = new ArrayList<>();

        for (int section = startSection; section <= endSection; section++) {
            String mapKey = seatType + "_" + section;
            TicketListBO.IntervalStockStatus status = intervalStockMap.get(mapKey);
            if (status == null) {
                // 无状态，视为无票
                hasNoneStock = true;
                break;
            }

            int statusCode = status.getStatusCode();
            if (statusCode == STOCK_STATUS_NONE) {
                // 有任意区间无票，整体无票
                hasNoneStock = true;
                break;
            } else if (statusCode == STOCK_STATUS_LOW) {
                // 低库存，收集realStock
                lowStockValues.add(status.getRealStock() == null ? 0 : status.getRealStock());
            }
        }

        // 状态判断
        if (hasNoneStock) {
            // 无票，返回0
            return 0;
        } else if (!lowStockValues.isEmpty()) {
            // 有低库存区间，取最小值
            return lowStockValues.stream().min(Integer::compareTo).orElse(0);
        } else {
            // 全高库存，返回11
            return SUFFICIENT_STOCK_VALUE;
        }
    }

    /**
     * @param date 日期
     * @param trainCode 车次
     * @param seatType 座位类型
     * @param section 区间号
     * @param statusCode 状态码（0/1/2）
     * @param realStock 真实库存（低库存时必填）
     */
    public void updateJvmStockStatus(LocalDate date, String trainCode, String seatType,
                                     int section, int statusCode, Integer realStock) {
        String cacheKey = String.format("%s:%s", date, trainCode);
        Cache cache = trainStopCacheManager.getCache("trainStopCache");
        if (cache == null) {
            log.error("JVM缓存实例获取失败，无法更新库存状态");
            return;
        }

        TicketListBO trainBO = cache.get(cacheKey, TicketListBO.class);
        if (trainBO == null) {
            log.warn("车次{}的JVM缓存不存在，无法更新库存状态", trainCode);
            return;
        }

        Map<String, TicketListBO.IntervalStockStatus> intervalStockMap = trainBO.getIntervalStockMap();
        if (intervalStockMap == null) {
            intervalStockMap = new HashMap<>();
            trainBO.setIntervalStockMap(intervalStockMap);
        }

        // 构建新的库存状态
        String mapKey = seatType + "_" + section;
        TicketListBO.IntervalStockStatus status = TicketListBO.IntervalStockStatus.builder()
                .statusCode(statusCode)
                .realStock(statusCode == STOCK_STATUS_LOW ? realStock : -1) // 高库存存-1，无票存0
                .build();
        intervalStockMap.put(mapKey, status);

        // 回写JVM缓存
        cache.put(cacheKey, trainBO);
        log.info("更新JVM缓存库存状态：{} {} {} 区间{} → 状态{}，库存{}",
                date, trainCode, seatType, section, statusCode, realStock);
    }

    /**
     * 从JVM缓存批量获取车次静态信息
     */
    private Map<String, TicketListBO> getTrainBoFromJvmCache(LocalDate date, List<String> trainCodes) {
        Map<String, TicketListBO> resultMap = new HashMap<>();
        Cache cache = trainStopCacheManager.getCache("trainStopCache");
        if (cache == null) {
            log.error("JVM缓存实例获取失败");
            return resultMap;
        }
        trainCodes.forEach(code -> {
            String cacheKey = String.format("%s:%s", date, code);
            TicketListBO bo = cache.get(cacheKey, TicketListBO.class);
            if (bo != null) {
                resultMap.put(code, bo);
            }
        });
        return resultMap;
    }

    /**
     * 降级查询数据库（带限流保护），获取车次静态信息
     */
    private Map<String, TicketListBO> getTrainBoFromDbWithLimit(LocalDate date, List<String> trainCodes) {
        Map<String, TicketListBO> resultMap = new HashMap<>();
        if (!dbRateLimiter.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            log.error("数据库限流触发，拒绝查询车次：{}", trainCodes);
            return resultMap;
        }

        try {
            LambdaQueryWrapper<TrainStopover> queryWrapper = new LambdaQueryWrapper<TrainStopover>()
                    .eq(TrainStopover::getDate, date)
                    .in(TrainStopover::getCode, trainCodes)
                    .select(
                            TrainStopover::getDate,
                            TrainStopover::getCode,
                            TrainStopover::getStopoverStation,
                            TrainStopover::getStationIndex,
                            TrainStopover::getInTime,
                            TrainStopover::getOutTime,
                            TrainStopover::getMileage
                    );
            List<TrainStopover> stopovers = trainStopoverMapper.selectList(queryWrapper);
            if (CollectionUtils.isEmpty(stopovers)) {
                return resultMap;
            }

            Map<String, List<TrainStopover>> groupByCode = stopovers.stream()
                    .collect(Collectors.groupingBy(TrainStopover::getCode));

            groupByCode.entrySet().parallelStream().forEach(entry -> {
                String code = entry.getKey();
                List<TrainStopover> stopoverList = entry.getValue();
                List<TrainStopover> sortedList = stopoverList.stream()
                        .filter(s -> s.getStationIndex() != null)
                        .sorted(Comparator.comparing(TrainStopover::getStationIndex))
                        .collect(Collectors.toList());
                if (CollectionUtils.isEmpty(sortedList)) {
                    return;
                }

                List<TicketListBO.StopoverStation> boStations = sortedList.stream()
                        .map(s -> TicketListBO.StopoverStation.builder()
                                .stopoverStation(s.getStopoverStation())
                                .stationIndex(s.getStationIndex())
                                .inTime(s.getInTime())
                                .outTime(s.getOutTime())
                                .mileage(s.getMileage())
                                .build())
                        .collect(Collectors.toList());

                // 初始化区间库存Map（默认高库存）
                Map<String, TicketListBO.IntervalStockStatus> intervalStockMap = initIntervalStockMap(sortedList.size() - 1);

                TicketListBO bo = TicketListBO.builder()
                        .date(date)
                        .code(code)
                        .startStation(sortedList.get(0).getStopoverStation())
                        .endStation(sortedList.get(sortedList.size() - 1).getStopoverStation())
                        .stopoverStations(boStations)
                        .intervalStockMap(intervalStockMap)
                        .build();
                resultMap.put(code, bo);
            });
        } catch (Exception e) {
            log.error("降级查询数据库失败", e);
        }
        return resultMap;
    }

    /**
     * 初始化区间库存Map（默认高库存）
     * @param sectionCount 区间数
     * @return 初始化后的库存Map
     */
    private Map<String, TicketListBO.IntervalStockStatus> initIntervalStockMap(int sectionCount) {
        Map<String, TicketListBO.IntervalStockStatus> stockMap = new HashMap<>();
        List<String> seatTypes = Arrays.asList(SEAT_TYPE_BUSINESS, SEAT_TYPE_FIRST, SEAT_TYPE_SECOND);

        for (String seatType : seatTypes) {
            for (int section = 1; section <= sectionCount; section++) {
                String mapKey = seatType + "_" + section;
                TicketListBO.IntervalStockStatus status = TicketListBO.IntervalStockStatus.builder()
                        .statusCode(STOCK_STATUS_SUFFICIENT)
                        .realStock(-1)
                        .build();
                stockMap.put(mapKey, status);
            }
        }
        return stockMap;
    }

    /**
     * 将降级查询的结果放入JVM缓存（预热）
     */
    private void putMissTrainBoToJvmCache(Map<String, TicketListBO> missTrainBoMap) {
        if (CollectionUtils.isEmpty(missTrainBoMap)) {
            return;
        }
        Cache cache = trainStopCacheManager.getCache("trainStopCache");
        if (cache == null) {
            return;
        }
        missTrainBoMap.forEach((code, bo) -> {
            String cacheKey = String.format("%s:%s", bo.getDate(), code);
            cache.put(cacheKey, bo);
        });
        log.info("JVM缓存补充车次数量：{}", missTrainBoMap.size());
    }

    /**
     * 提取用户起止站的关键业务信息（站序、时间、里程）
     */
    private StationBizInfo getStationBizInfo(TicketListBO trainBO, String start, String end) {
        List<TicketListBO.StopoverStation> stations = trainBO.getStopoverStations();
        if (CollectionUtils.isEmpty(stations)) {
            return null;
        }

        Optional<TicketListBO.StopoverStation> startStationOpt = stations.stream()
                .filter(s -> start.equals(s.getStopoverStation()))
                .findFirst();
        Optional<TicketListBO.StopoverStation> endStationOpt = stations.stream()
                .filter(s -> end.equals(s.getStopoverStation()))
                .findFirst();

        if (startStationOpt.isPresent() && endStationOpt.isPresent()) {
            TicketListBO.StopoverStation startStation = startStationOpt.get();
            TicketListBO.StopoverStation endStation = endStationOpt.get();
            if (startStation.getStationIndex() < endStation.getStationIndex()) {
                return StationBizInfo.builder()
                        .startIndex(startStation.getStationIndex())
                        .startOutTime(startStation.getOutTime())
                        .startMileage(startStation.getMileage())
                        .endIndex(endStation.getStationIndex())
                        .endInTime(endStation.getInTime())
                        .endMileage(endStation.getMileage())
                        .build();
            }
        }
        log.warn("车次{}不包含有效起止站：{}→{}", trainBO.getCode(), start, end);
        return null;
    }

    /**
     * 价格保留2位小数（金额格式化）
     */
    private double roundPrice(double price) {
        return Math.round(price * 100) / 100.0;
    }

    // ===================== 内部辅助类 =====================
    @lombok.Data
    @lombok.Builder
    private static class StationBizInfo {
        private Integer startIndex;      // 出发站序
        private LocalTime startOutTime;  // 出发站出站时间
        private Integer startMileage;    // 出发站里程
        private Integer endIndex;        // 目的站序
        private LocalTime endInTime;     // 目的站进站时间
        private Integer endMileage;      // 目的站里程
    }

    @lombok.Data
    @lombok.Builder
    private static class StockInfo {
        private Integer businessNum;     // 商务座余票
        private Integer firstClassNum;   // 一等座余票
        private Integer secondClassNum;  // 二等座余票

        public static StockInfo empty() {
            return StockInfo.builder()
                    .businessNum(0)
                    .firstClassNum(0)
                    .secondClassNum(0)
                    .build();
        }
    }
}
