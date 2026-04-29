package com.project.warmUp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.pojo.bo.TicketListBO;
import com.project.pojo.entity.LineTrain;
import com.project.pojo.entity.TrainCarriage;
import com.project.mapper.LineTrainMapper;
import com.project.mapper.TrainCarriageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Redis预热测试类（拆分令牌桶/位图，并行处理1万车次）
 * 预热日期：2026-03-03
 */
@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class TokenAndBitmap {

    // ===== 基础配置 =====
    private static final LocalDate WARMUP_DATE = LocalDate.of(2026, 3, 3); // 预热日期
    private static final int TOKEN_INIT_NUM = 1000; // 令牌桶初始数量
    // 每车厢座位数（可配置到配置文件）
    private static final int BUSINESS_SEAT_PER_CAR = 30;  // 商务座每车厢座位数
    private static final int FIRST_SEAT_PER_CAR = 60;     // 一等座每车厢座位数
    private static final int SECOND_SEAT_PER_CAR = 90;    // 二等座每车厢座位数
    // 座位类型编码（0=商务，1=一等，2=二等）
    private static final int SEAT_TYPE_BUSINESS = 0;
    private static final int SEAT_TYPE_FIRST = 1;
    private static final int SEAT_TYPE_SECOND = 2;
    // 并行处理批次大小（避免一次并行过多）
    private static final int BATCH_SIZE = 500;

    // ===== 依赖注入 =====
    private final StringRedisTemplate stringRedisTemplate;
    private final LineTrainMapper lineTrainMapper;
    private final TrainCarriageMapper trainCarriageMapper;
    private final CacheManager trainStopCacheManager;

    // ===== 公共方法：获取全量车次列表 =====
    private List<String> getAllTrainCodes() {
        // 全量查询line_train表并去重
        List<LineTrain> lineTrainList = lineTrainMapper.selectList(null);
        if (CollectionUtils.isEmpty(lineTrainList)) {
            log.warn("line_train表无数据！");
            return Collections.emptyList();
        }
        List<String> trainCodes = lineTrainList.stream()
                .map(LineTrain::getTrainCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        log.info("获取到待预热车次总数：{}", trainCodes.size());
        return trainCodes;
    }

    // ===================== 测试方法1：并行预热令牌桶 =====================
    @Test
    public void parallelWarmupTokenBucket() {
        long startTime = System.currentTimeMillis();
        List<String> trainCodes = getAllTrainCodes();
        if (CollectionUtils.isEmpty(trainCodes)) {
            return;
        }

        // 原子计数器：记录成功/失败数（线程安全）
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 1. 分批次并行处理（避免单次并行1万条导致CPU过载）
        for (int i = 0; i < trainCodes.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, trainCodes.size());
            List<String> batchTrainCodes = trainCodes.subList(i, end);

            // 2. 并行处理当前批次 + Redis Pipeline批量设置令牌桶
            batchTrainCodes.parallelStream().forEach(trainCode -> {
                try {
                    // Pipeline批量执行SET命令
                    stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                        String tokenKey = String.format("Token:%s:%s", WARMUP_DATE, trainCode);
                        connection.set(tokenKey.getBytes(), String.valueOf(TOKEN_INIT_NUM).getBytes());
                        return null;
                    });
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("车次{}令牌桶预热失败", trainCode, e);
                    failCount.incrementAndGet();
                }
            });
            log.info("令牌桶预热批次[{}-{}]完成，成功{}，失败{}", i, end-1, successCount.get(), failCount.get());
        }

        // 3. 打印最终结果
        long costTime = System.currentTimeMillis() - startTime;
        log.info("===== 令牌桶预热完成 =====");
        log.info("总耗时：{}ms，总车次：{}，成功：{}，失败：{}",
                costTime, trainCodes.size(), successCount.get(), failCount.get());
    }

    // ===================== 测试方法2：并行预热位图 =====================
    @Test
    public void parallelWarmupBitmap() {
        long startTime = System.currentTimeMillis();
        List<String> trainCodes = getAllTrainCodes();
        if (CollectionUtils.isEmpty(trainCodes)) {
            return;
        }

        // 原子计数器
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 1. 批量查询所有车次的车厢数（避免1万次单条DB查询）
        Map<String, TrainCarriage> carriageMap = batchGetTrainCarriage(trainCodes);
        // 2. 批量获取所有车次的区间数（JVM缓存）
        Map<String, Integer> sectionCountMap = batchGetSectionCount(trainCodes);

        int n = trainCodes.size();

        // 3. 分批次并行处理位图
        for (int i = 0; i < trainCodes.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, trainCodes.size());
            List<String> batchTrainCodes = trainCodes.subList(i, end);

            // 4. 并行处理当前批次 + Redis Pipeline批量设置位图
            batchTrainCodes.parallelStream().forEach(trainCode -> {
                try {
                    // 获取该车次的车厢数和区间数
                    TrainCarriage carriage = carriageMap.get(trainCode);
                    Integer sectionCount = sectionCountMap.get(trainCode);
                    if (carriage == null || sectionCount == null || sectionCount <= 0) {
                        log.warn("车次{}无车厢/区间数据，跳过位图预热", trainCode);
                        failCount.incrementAndGet();
                        return;
                    }

                    // 构建3种座位类型的位图数据
                    List<BitmapParam> bitmapParams = buildBitmapParams(trainCode, carriage, sectionCount);
                    if (CollectionUtils.isEmpty(bitmapParams)) {
                        failCount.incrementAndGet();
                        return;
                    }

                    // Pipeline批量设置位图
                    stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                        for (BitmapParam param : bitmapParams) {
                            connection.set(param.getRedisKey().getBytes(), param.getBitmapBytes());
                        }
                        return null;
                    });
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("车次{}位图预热失败", trainCode, e);
                    failCount.incrementAndGet();
                }
            });
            log.info("位图预热批次[{}-{}]完成，成功{}，失败{}", i, end-1, successCount.get(), failCount.get());
        }

        // 5. 打印最终结果
        long costTime = System.currentTimeMillis() - startTime;
        log.info("===== 位图预热完成 =====");
        log.info("总耗时：{}ms，总车次：{}，成功：{}，失败：{}",
                costTime, trainCodes.size(), successCount.get(), failCount.get());
    }

    // ===================== 位图预热辅助方法 =====================
    /**
     * 批量查询车次车厢数（一次DB查询）
     */
    private Map<String, TrainCarriage> batchGetTrainCarriage(List<String> trainCodes) {
        LambdaQueryWrapper<TrainCarriage> queryWrapper = new LambdaQueryWrapper<TrainCarriage>()
                .in(TrainCarriage::getTrainCode, trainCodes);
        List<TrainCarriage> carriageList = trainCarriageMapper.selectList(queryWrapper);
        return carriageList.stream()
                .collect(Collectors.toMap(TrainCarriage::getTrainCode, c -> c, (k1, k2) -> k1)); // 去重
    }

    /**
     * 批量获取车次区间数（JVM缓存）
     */
    private Map<String, Integer> batchGetSectionCount(List<String> trainCodes) {
        Map<String, Integer> sectionCountMap = new HashMap<>();
        Cache cache = trainStopCacheManager.getCache("trainStopCache");
        if (cache == null) {
            log.error("JVM缓存实例获取失败！");
            return sectionCountMap;
        }

        trainCodes.forEach(trainCode -> {
            String cacheKey = String.format("%s:%s", WARMUP_DATE, trainCode);
            TicketListBO trainBO = cache.get(cacheKey, TicketListBO.class);
            if (trainBO != null && !CollectionUtils.isEmpty(trainBO.getStopoverStations())) {
                int sectionCount = trainBO.getStopoverStations().size() - 1;
                sectionCountMap.put(trainCode, sectionCount);
            }
        });
        return sectionCountMap;
    }

    /**
     * 构建单个车次的位图参数（3种座位类型）
     */
    private List<BitmapParam> buildBitmapParams(String trainCode, TrainCarriage carriage, int sectionCount) {
        List<BitmapParam> params = new ArrayList<>();

        // 1. 商务座位图
        int businessCarNum = carriage.getBusinessCarriage() == null ? 0 : carriage.getBusinessCarriage();
        if (businessCarNum > 0) {
            BitmapParam businessParam = buildSingleBitmapParam(trainCode, SEAT_TYPE_BUSINESS, businessCarNum, sectionCount);
            if (businessParam != null) {
                params.add(businessParam);
            }
        }

        // 2. 一等座位图
        int firstCarNum = carriage.getFirstClassCarriage() == null ? 0 : carriage.getFirstClassCarriage();
        if (firstCarNum > 0) {
            BitmapParam firstParam = buildSingleBitmapParam(trainCode, SEAT_TYPE_FIRST, firstCarNum, sectionCount);
            if (firstParam != null) {
                params.add(firstParam);
            }
        }

        // 3. 二等座位图
        int secondCarNum = carriage.getSecondClassCarriage() == null ? 0 : carriage.getSecondClassCarriage();
        if (secondCarNum > 0) {
            BitmapParam secondParam = buildSingleBitmapParam(trainCode, SEAT_TYPE_SECOND, secondCarNum, sectionCount);
            if (secondParam != null) {
                params.add(secondParam);
            }
        }

        return params;
    }

    /**
     * 构建单个座位类型的位图参数
     */
    private BitmapParam buildSingleBitmapParam(String trainCode, int seatType, int carNum, int sectionCount) {
        // 计算总bit数
        int seatPerCar = getSeatPerCar(seatType);
        long totalBitCount = (long) carNum * seatPerCar * sectionCount;
        if (totalBitCount <= 0) {
            log.warn("车次{}座位类型{}位图bit数无效：{}", trainCode, seatType, totalBitCount);
            return null;
        }

        // 计算字节数（向上取整）
        long byteCount = (totalBitCount + 7) / 8;
        // 生成全0字节数组
        byte[] bitmapBytes = new byte[(int) byteCount];

        // 构建Redis Key
        String redisKey = String.format("%s:%s:%d:bitmap", WARMUP_DATE, trainCode, seatType);
        return new BitmapParam(redisKey, bitmapBytes);
    }

    /**
     * 根据座位类型获取每车厢座位数
     */
    private int getSeatPerCar(int seatType) {
        return switch (seatType) {
            case SEAT_TYPE_BUSINESS -> BUSINESS_SEAT_PER_CAR;
            case SEAT_TYPE_FIRST -> FIRST_SEAT_PER_CAR;
            case SEAT_TYPE_SECOND -> SECOND_SEAT_PER_CAR;
            default -> 0;
        };
    }

    /**
     * 位图参数封装类
     */
    @lombok.Data
    private static class BitmapParam {
        private String redisKey; // Redis位图Key
        private byte[] bitmapBytes; // 位图字节数组（全0）

        public BitmapParam(String redisKey, byte[] bitmapBytes) {
            this.redisKey = redisKey;
            this.bitmapBytes = bitmapBytes;
        }
    }
}