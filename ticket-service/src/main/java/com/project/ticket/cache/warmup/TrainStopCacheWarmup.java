package com.project.ticket.cache.warmup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.ticket.mapper.TrainCarriageMapper;
import com.project.ticket.mapper.TrainStopoverMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.entity.TrainCarriage;
import com.project.ticket.pojo.entity.TrainStopover;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 车次经停+查票区间库存信息缓存预热器：程序启动时加载到Caffeine（JVM内存）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainStopCacheWarmup implements CommandLineRunner {

    private final CacheManager trainStopCacheManager;
    private final TrainStopoverMapper trainStopoverMapper;
    private final TrainCarriageMapper trainCarriageMapper;

    private static final String CACHE_NAME = "trainStopCache";
    // 区间数：默认19个（生产时需要计算实际值）
    private static final int DEFAULT_SECTION_COUNT = 19;
    // 座位类型常量（用于构建intervalStockMap的Key）
    private static final List<String> SEAT_TYPES = Arrays.asList("business", "firstClass", "secondClass");

    @Override
    public void run(String... args) throws Exception {
        long startTime = System.currentTimeMillis();
        log.info("开始预热车次经停+查票区间库存信息到Caffeine缓存...");

        // 1. 批量查询所有经停站数据
        List<TrainStopover> allStopovers = trainStopoverMapper.selectList(
                new LambdaQueryWrapper<TrainStopover>()
                        .select(
                                TrainStopover::getDate,
                                TrainStopover::getCode,
                                TrainStopover::getStopoverStation,
                                TrainStopover::getStationIndex,
                                TrainStopover::getInTime,
                                TrainStopover::getOutTime,
                                TrainStopover::getMileage
                        )
        );

        if (allStopovers.isEmpty()) {
            log.warn("train_stopover表无数据，缓存预热跳过");
            return;
        }

        // 2. 按「date:code」分组
        Map<String, List<TrainStopover>> groupByDateCode = allStopovers.stream()
                .collect(Collectors.groupingBy(
                        stopover -> buildCacheKey(stopover.getDate(), stopover.getCode()),
                        Collectors.toList()
                ));

        // 3. 获取缓存实例
        Cache cache = trainStopCacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            log.error("获取Caffeine缓存实例失败，缓存名称：{}", CACHE_NAME);
            return;
        }

        // 4. 遍历分组数据，构建BO并放入缓存
        int successCount = 0;
        for (Map.Entry<String, List<TrainStopover>> entry : groupByDateCode.entrySet()) {
            String cacheKey = entry.getKey();
            List<TrainStopover> stopovers = entry.getValue();

            TicketListBO ticketListBO = buildTicketListBO(stopovers);
            if (ticketListBO != null) {
                cache.put(cacheKey, ticketListBO);
                successCount++;
            }
        }

        // 5. 打印结果
        long costTime = System.currentTimeMillis() - startTime;
        log.info("车次经停+查票区间库存信息缓存预热完成！共加载{}个车次，耗时{}ms", successCount, costTime);
    }

    /**
     * 构建缓存Key：date:code
     */
    private String buildCacheKey(LocalDate date, String code) {
        if (date == null || code == null) {
            return "";
        }
        return date.toString() + ":" + code;
    }

    /**
     * 构建TicketListBO（适配查票场景：新增区间库存Map，修正车厢信息）
     */
    private TicketListBO buildTicketListBO(List<TrainStopover> stopovers) {
        if (stopovers.isEmpty()) {
            return null;
        }

        // 基础信息（原有逻辑）
        TrainStopover first = stopovers.get(0);
        LocalDate date = first.getDate();
        String code = first.getCode();

        List<TrainStopover> validStopovers = stopovers.stream()
                .filter(s -> s.getStationIndex() != null)
                .sorted(Comparator.comparing(TrainStopover::getStationIndex))
                .toList();

        if (validStopovers.isEmpty()) {
            log.warn("车次{}({})无有效经停站数据，跳过缓存", code, date);
            return null;
        }

        // 构建经停站列表（原有逻辑）
        List<TicketListBO.StopoverStation> stopoverStationList = validStopovers.stream()
                .map(s -> TicketListBO.StopoverStation.builder()
                        .stopoverStation(s.getStopoverStation())
                        .stationIndex(s.getStationIndex())
                        .inTime(s.getInTime())
                        .outTime(s.getOutTime())
                        .mileage(s.getMileage())
                        .build())
                .collect(Collectors.toList());

        String startStation = validStopovers.get(0).getStopoverStation();
        String endStation = validStopovers.get(validStopovers.size() - 1).getStopoverStation();

        // ========== 核心修改1：初始化按区间的库存状态Map（查票核心） ==========
        // 推导区间数：经停站数-1（如20个站=19个区间）
        int sectionCount = validStopovers.size() - 1;
        if (sectionCount <= 0) {
            sectionCount = DEFAULT_SECTION_COUNT; // 兜底默认19个区间
        }
        // 构建intervalStockMap：3种座位×N个区间
        Map<String, TicketListBO.IntervalStockStatus> intervalStockMap = initIntervalStockMap(sectionCount);

        // ========== 核心修改2：初始化静态车厢信息（无库存） ==========
        TicketListBO.CarriageInfo businessCarriageInfo = null;
        TicketListBO.CarriageInfo firstClassCarriageInfo = null;
        TicketListBO.CarriageInfo secondClassCarriageInfo = null;

        // 查询该车次的车厢编组
        TrainCarriage carriage = trainCarriageMapper.selectOne(
                new LambdaQueryWrapper<TrainCarriage>()
                        .eq(TrainCarriage::getTrainCode, code)
        );
        if (carriage != null) {
            // 商务座车厢：数量=1，序号=1
            businessCarriageInfo = buildCarriageInfo("商务座车厢", 1, 1);
            // 一等座车厢：数量=1，序号=2
            firstClassCarriageInfo = buildCarriageInfo("一等座车厢", 2, 2);
            // 二等座车厢：数量=6，序号=3-8
            secondClassCarriageInfo = buildCarriageInfo("二等座车厢", 3, 8);
        } else {
            log.warn("车次{}({})无车厢编组数据，车厢信息设为null", code, date);
        }

        // 构建最终BO（移除原扁平化座位状态，新增intervalStockMap）
        return TicketListBO.builder()
                // 基础字段
                .date(date)
                .code(code)
                .startStation(startStation)
                .endStation(endStation)
                .stopoverStations(stopoverStationList)
                // 核心新增：区间库存状态Map
                .intervalStockMap(intervalStockMap)
                // 修正：静态车厢信息（无库存）
                .businessCarriageInfo(businessCarriageInfo)
                .firstClassCarriageInfo(firstClassCarriageInfo)
                .secondClassCarriageInfo(secondClassCarriageInfo)
                .build();
    }

    /**
     * 初始化区间库存状态Map
     * @param sectionCount 区间数（如19）
     * @return 3种座位×N个区间的库存状态Map
     */
    private Map<String, TicketListBO.IntervalStockStatus> initIntervalStockMap(int sectionCount) {
        Map<String, TicketListBO.IntervalStockStatus> stockMap = new HashMap<>(SEAT_TYPES.size() * sectionCount);

        for (String seatType : SEAT_TYPES) {
            // 遍历每个区间（1~sectionCount）
            for (int section = 1; section <= sectionCount; section++) {
                String mapKey = seatType + "_" + section; // 如business_1、firstClass_19
                // 默认状态：充足（2），realStock=-1（充足时不存具体值）
                TicketListBO.IntervalStockStatus status = TicketListBO.IntervalStockStatus.builder()
                        .statusCode(TicketListBO.SeatStatus.SUFFICIENT_STOCK.getCode())
                        .realStock(-1)
                        .build();
                stockMap.put(mapKey, status);
            }
        }
        return stockMap;
    }

    /**
     * 辅助方法：构建车厢信息（删除stock字段，仅保留静态列表）
     * @param carriageType 车厢类型（商务座车厢/一等座车厢/二等座车厢）
     * @param startIndex 起始车厢序号
     * @param endIndex 结束车厢序号
     * @return 车厢信息
     */
    private TicketListBO.CarriageInfo buildCarriageInfo(String carriageType, int startIndex, int endIndex) {
        // 生成车厢序号列表（如3-8 → [3,4,5,6,7,8]）
        List<Integer> carriageIndexes = IntStream.rangeClosed(startIndex, endIndex)
                .boxed()
                .collect(Collectors.toList());

        // 修正：新BO的CarriageInfo无stock字段，移除stock设置
        return TicketListBO.CarriageInfo.builder()
                .carriageType(carriageType)
                .carriageIndexes(carriageIndexes)
                .build();
    }
}