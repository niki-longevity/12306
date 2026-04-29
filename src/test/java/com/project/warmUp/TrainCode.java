package com.project.warmUp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.mapper.LineStationMapper;
import com.project.mapper.TrainStopoverMapper;
import com.project.pojo.entity.LineStation;
import com.project.pojo.entity.TrainStopover;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

@SpringBootTest
public class TrainCode {

    @Autowired
    private LineStationMapper lineStationMapper;

    @Autowired
    private TrainStopoverMapper trainStopoverMapper;

    // 注入RedisTemplate（如果用StringRedisTemplate更轻量，二选一即可）
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 线程池核心参数（根据服务器配置调整）
    // CPU核心数*2 是数据库操作的最优并发数（避免数据库连接池打满）
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    private static final int MAX_POOL_SIZE = CORE_POOL_SIZE * 2;
    private static final int KEEP_ALIVE_TIME = 60; // 空闲线程存活时间（秒）

    /**
     * 清空 Redis 所有Key（通用版，兼容单机Redis）
     * 优点：简单直接；缺点：keys("*")在key极多时会阻塞Redis（测试环境无影响）
     */
    @Test
    public void clearAllRedisKeys() {
        // 1. 获取所有key（通配符*匹配所有）
        Set<String> allKeys = redisTemplate.keys("*");

        if (allKeys != null && !allKeys.isEmpty()) {
            // 2. 批量删除所有key（效率高于逐个删除）
            Long deleteCount = redisTemplate.delete(allKeys);
            System.out.println("成功清空Redis，共删除 " + deleteCount + " 个key");
        } else {
            System.out.println("Redis中无任何key，无需清空");
        }
    }

    /**
     * 多线程预热车次 code 数据到Redis
     */
    @Test
    public void multiThreadWarmUp() {
        Instant start = Instant.now(); // 记录开始时间
        LocalDate date = LocalDate.of(2026, 3, 3);
        String dateStr = date.toString();
        int totalLine = 400; // 总干线数

        // 1. 创建自定义线程池（推荐用ThreadPoolExecutor，可控性更强）
        ExecutorService executorService = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(100), // 任务队列容量
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时，由提交任务的线程执行（避免任务丢失）
        );

        // 2. 倒计时锁：等待所有线程完成
        CountDownLatch countDownLatch = new CountDownLatch(totalLine);

        // 3. 提交400条干线的预热任务
        for (int i = 1; i <= totalLine; i++) {
            int lineNum = i; // 内部类必须用final变量，所以重新赋值
            executorService.submit(() -> {
                try {
                    // 单个线程处理1条干线的逻辑
                    processSingleLine(lineNum, dateStr, date);
                    // 每处理完10条打印进度（避免日志刷屏）
                    if (lineNum % 10 == 0) {
                        System.out.println(Thread.currentThread().getName() + " 已完成干线X" + lineNum + "的预热");
                    }
                } catch (Exception e) {
                    // 单个任务异常不影响其他任务，打印异常日志
                    System.err.println("处理干线X" + lineNum + "时发生异常：" + e.getMessage());
                    e.printStackTrace();
                } finally {
                    // 无论成功失败，计数器减1
                    countDownLatch.countDown();
                }
            });
        }

        try {
            // 4. 等待所有线程执行完毕（超时时间设为10分钟，避免无限等待）
            countDownLatch.await(10, java.util.concurrent.TimeUnit.MINUTES);
            // 5. 关闭线程池
            executorService.shutdown();
            // 打印总耗时
            Duration duration = Duration.between(start, Instant.now());
            System.out.println("========================");
            System.out.println("所有干线预热完成！总耗时：" + duration.toSeconds() + "秒");
            System.out.println("========================");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("主线程等待被中断：" + e.getMessage());
        }
    }

    /**
     * 单个线程处理单条干线的预热逻辑
     * @param lineNum 干线编号（1-400）
     * @param dateStr 日期字符串（2026-03-03）
     * @param date 日期对象
     */
    private void processSingleLine(int lineNum, String dateStr, LocalDate date) {
        String lineCode = "X" + lineNum;
        // 查询当前干线的所有站点
        LambdaQueryWrapper<LineStation> stationQueryWrapper = new LambdaQueryWrapper<LineStation>()
                .select(LineStation::getStation)
                .eq(LineStation::getLineCode, lineCode);
        List<LineStation> lineStations = lineStationMapper.selectList(stationQueryWrapper);
        int stationSize = lineStations.size();

        // 穷举所有站点对（正向+反向）
        for (int j = 0; j < stationSize; j++) {
            String station1 = lineStations.get(j).getStation();
            for (int k = j + 1; k < stationSize; k++) {
                String station2 = lineStations.get(k).getStation();

                // 正向：station1 → station2
                List<String> forwardTrainCodes = getValidTrainCodes(date, station1, station2);
                String forwardRedisKey = String.format("%s:%s:%s", dateStr, station1, station2);
                redisTemplate.opsForList().rightPushAll(forwardRedisKey, forwardTrainCodes);

                // 反向：station2 → station1
                List<String> reverseTrainCodes = getValidTrainCodes(date, station2, station1);
                String reverseRedisKey = String.format("%s:%s:%s", dateStr, station2, station1);
                redisTemplate.opsForList().rightPushAll(reverseRedisKey, reverseTrainCodes);
            }
        }
    }

    /**
     * 核心工具方法：获取指定日期下，经过出发站且经过目的站、且目的站站序>出发站站序的有效车次列表
     */
    private List<String> getValidTrainCodes(LocalDate date, String startStation, String endStation) {
        // ========== 第一步：查询出发站的「车次+站序」，存入Map（code -> 出发站站序） ==========
        LambdaQueryWrapper<TrainStopover> startQueryWrapper = new LambdaQueryWrapper<TrainStopover>()
                .select(TrainStopover::getCode, TrainStopover::getStationIndex) // 新增查询站序字段
                .eq(TrainStopover::getDate, date)
                .eq(TrainStopover::getStopoverStation, startStation);
        List<TrainStopover> startStopovers = trainStopoverMapper.selectList(startQueryWrapper);
        if (startStopovers.isEmpty()) {
            return new ArrayList<>(); // 无出发站车次，直接返回空
        }

        // 构建「车次→出发站站序」的Map（去重，同一车次可能有多条记录，取任意一个站序即可）
        Map<String, Integer> startStationIndexMap = new HashMap<>();
        for (TrainStopover stopover : startStopovers) {
            String code = stopover.getCode();
            Integer startIndex = stopover.getStationIndex();
            // 过滤站序为空的无效数据，且只存一次（去重）
            if (startIndex != null && !startStationIndexMap.containsKey(code)) {
                startStationIndexMap.put(code, startIndex);
            }
        }
        if (startStationIndexMap.isEmpty()) {
            return new ArrayList<>(); // 出发站无有效站序的车次，返回空
        }

        // 提取出发站的有效车次列表（用于后续IN查询）
        List<String> validStartTrainCodes = new ArrayList<>(startStationIndexMap.keySet());

        // ========== 第二步：查询目的站的「车次+站序」，且车次在出发站列表中 ==========
        LambdaQueryWrapper<TrainStopover> endQueryWrapper = new LambdaQueryWrapper<TrainStopover>()
                .select(TrainStopover::getCode, TrainStopover::getStationIndex) // 新增查询站序字段
                .eq(TrainStopover::getDate, date)
                .eq(TrainStopover::getStopoverStation, endStation)
                .in(TrainStopover::getCode, validStartTrainCodes); // 只查出发站存在的车次
        List<TrainStopover> endStopovers = trainStopoverMapper.selectList(endQueryWrapper);

        // ========== 第三步：筛选「目的站站序 > 出发站站序」的车次 ==========
        List<String> finalValidTrainCodes = new ArrayList<>();
        for (TrainStopover endStopover : endStopovers) {
            String code = endStopover.getCode();
            Integer endIndex = endStopover.getStationIndex();
            // 过滤目的站站序为空的情况，且对比站序
            if (endIndex != null) {
                Integer startIndex = startStationIndexMap.get(code);
                // 核心逻辑：目的站站序 > 出发站站序
                if (endIndex > startIndex) {
                    finalValidTrainCodes.add(code);
                }
            }
        }

        // 去重后返回最终有效车次列表
        return finalValidTrainCodes.stream().distinct().collect(Collectors.toList());
    }
}
