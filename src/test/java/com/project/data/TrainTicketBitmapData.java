package com.project.data;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.mapper.LineTrainMapper;
import com.project.mapper.TrainCarriageMapper;
import com.project.mapper.TrainTicketBitmapMapper;
import com.project.pojo.entity.LineTrain;
import com.project.pojo.entity.TrainCarriage;
import com.project.pojo.entity.TrainTicketBitmap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.util.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 座位位图表（train_ticket_bitmap）并行造数测试类
 * 解决QueryWrapper报错 + 600万条数据并行生成优化
 */
@Slf4j
@SpringBootTest
public class TrainTicketBitmapData {

    // 固定配置
    private static final LocalDate FIX_DATE = LocalDate.of(2026, 3, 3);
    private static final String STOCK_KEY_PREFIX = "Stock:2026-03-03:%s:0";
    private static final int BATCH_SIZE = 5000; // 批量插入批次（600万数据建议5000/批）
    private static final int PARALLELISM = 8; // 并行度（根据CPU核心数和数据库性能调整）

    // 座位规则（静态常量，避免重复创建）
    private static final List<int[]> BUSINESS_SEAT_RULE = Arrays.asList(
            new int[]{1, 1}, new int[]{1, 5}, new int[]{2, 1}, new int[]{2, 3}, new int[]{2, 5}
    );
    private static final List<int[]> FIRST_CLASS_SEAT_RULE = new ArrayList<>();
    private static final List<int[]> SECOND_CLASS_SEAT_RULE = new ArrayList<>();

    // 初始化座位规则
    static {
        // 一等座：7排，每排1、2、4、5列（28座）
        for (int row = 1; row <= 7; row++) {
            FIRST_CLASS_SEAT_RULE.add(new int[]{row, 1});
            FIRST_CLASS_SEAT_RULE.add(new int[]{row, 2});
            FIRST_CLASS_SEAT_RULE.add(new int[]{row, 4});
            FIRST_CLASS_SEAT_RULE.add(new int[]{row, 5});
        }
        // 二等座：18排，每排1-5列（90座）
        for (int row = 1; row <= 18; row++) {
            for (int col = 1; col <= 5; col++) {
                SECOND_CLASS_SEAT_RULE.add(new int[]{row, col});
            }
        }
    }

    // 依赖注入
    @Autowired
    private LineTrainMapper lineTrainMapper;
    @Autowired
    private TrainCarriageMapper trainCarriageMapper;
    @Autowired
    private TrainTicketBitmapMapper trainTicketBitmapMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private DataSourceTransactionManager transactionManager; // 事务管理器

    // 自定义并行线程池（核心：控制资源占用）
    private final ExecutorService executor = new ThreadPoolExecutor(
            PARALLELISM,
            PARALLELISM * 2,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadFactory() {
                private int count = 1;
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "bitmap-generate-thread-" + count++);
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 任务过多时由主线程兜底，避免丢任务
    );

    /**
     * 并行造数核心方法（600万条数据优化版）
     */
    @Test
    public void parallelGenerateTrainTicketBitmap() throws Exception {
        long startTime = System.currentTimeMillis();
        log.info("开始并行生成座位位图数据，并行度：{}，批次大小：{}", PARALLELISM, BATCH_SIZE);

        // ========== 步骤1：读取并预处理车次（一次性读取，避免并行重复查库） ==========
        List<LineTrain> lineTrainList = lineTrainMapper.selectList(null);
        if (CollectionUtils.isEmpty(lineTrainList)) {
            log.warn("line_train表无数据，终止造数");
            return;
        }
        // 去重+过滤空车次
        List<String> allTrainCodes = lineTrainList.stream()
                .map(LineTrain::getTrainCode)
                .distinct()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        log.info("共读取到有效车次：{}个", allTrainCodes.size());

        // ========== 步骤2：预加载所有车次的车厢编组（避免并行时重复查库） ==========
        Map<String, TrainCarriage> trainCarriageMap = preLoadTrainCarriage(allTrainCodes);
        if (CollectionUtils.isEmpty(trainCarriageMap)) {
            log.warn("无有效车厢编组数据，终止造数");
            return;
        }

        // ========== 步骤3：并行生成数据（线程安全容器） ==========
        ConcurrentLinkedQueue<TrainTicketBitmap> insertQueue = new ConcurrentLinkedQueue<>();

        // 提交并行任务
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            allTrainCodes.parallelStream()
                    .filter(trainCarriageMap::containsKey) // 过滤无车厢编组的车次
                    .forEach(trainCode -> {
                        try {
                            TrainCarriage carriage = trainCarriageMap.get(trainCode);
                            // 分配车厢序号（商务→一等→二等）
                            Map<Integer, String> carriageTypeMap = assignCarriageIndex(carriage);
                            // 查询区间数（Redis）
                            int sectionCount = getSectionCountFromRedis(trainCode);

                            // 按车厢生成座位数据
                            for (Map.Entry<Integer, String> entry : carriageTypeMap.entrySet()) {
                                Integer carriageIndex = entry.getKey();
                                String seatType = entry.getValue();
                                List<int[]> seatRule = getSeatRuleByType(seatType);

                                for (int[] seat : seatRule) {
                                    int rowIndex = seat[0];
                                    int colIndex = seat[1];
                                    // 生成初始位图（全0）
                                    byte[] bitmap = generateInitBitmap(sectionCount);

                                    // 构建实体（线程安全，实体是局部变量）
                                    TrainTicketBitmap entity = new TrainTicketBitmap();
                                    entity.setDate(FIX_DATE);
                                    entity.setCode(trainCode);
                                    entity.setCarriageIndex(carriageIndex);
                                    entity.setRowIndex(rowIndex);
                                    entity.setColIndex(colIndex);
                                    entity.setBitmap(bitmap);

                                    insertQueue.offer(entity);

                                    // 达到批次大小，触发批量插入（并行下可能多线程同时触发，需加锁）
                                    if (insertQueue.size() >= BATCH_SIZE) {
                                        synchronized (this) {
                                            if (insertQueue.size() >= BATCH_SIZE) {
                                                batchInsertWithTransaction(insertQueue);
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("处理车次{}失败", trainCode, e);
                        }
                    });
        }, executor);

        // 等待所有并行任务完成
        future.get();

        // ========== 步骤4：插入剩余数据 ==========
        if (!insertQueue.isEmpty()) {
            batchInsertWithTransaction(insertQueue);
        }

        // ========== 步骤5：收尾与统计 ==========
        executor.shutdown();
        long totalCount = trainTicketBitmapMapper.selectCount(null);
        long costTime = (System.currentTimeMillis() - startTime) / 1000;
        log.info("并行造数完成！总数据量：{}条，耗时：{}秒", totalCount, costTime);
    }

    // ===================== 核心工具方法 =====================

    /**
     * 修正后的：查询车次车厢编组（解决原QueryWrapper报错）
     * 预加载所有车次的车厢编组，避免并行时重复查库
     */
    private Map<String, TrainCarriage> preLoadTrainCarriage(List<String> trainCodes) {
        // 批量查询，一次IO搞定，避免并行时多次查库
        List<TrainCarriage> carriageList = trainCarriageMapper.selectList(
                new LambdaQueryWrapper<TrainCarriage>() // 核心修正：直接用LambdaQueryWrapper
                        .in(TrainCarriage::getTrainCode, trainCodes)
        );
        // 转Map，方便后续快速获取
        return carriageList.stream()
                .collect(Collectors.toMap(
                        TrainCarriage::getTrainCode,
                        carriage -> carriage,
                        (k1, k2) -> k1 // 去重，保留第一个
                ));
    }

    /**
     * 分配车厢序号（商务→一等→二等）
     */
    private Map<Integer, String> assignCarriageIndex(TrainCarriage carriage) {
        Map<Integer, String> map = new LinkedHashMap<>();
        int current = 1;
        // 商务车厢
        int business = Optional.ofNullable(carriage.getBusinessCarriage()).orElse(0);
        for (int i = 0; i < business; i++) map.put(current++, "BUSINESS");
        // 一等车厢
        int first = Optional.ofNullable(carriage.getFirstClassCarriage()).orElse(0);
        for (int i = 0; i < first; i++) map.put(current++, "FIRST");
        // 二等车厢
        int second = Optional.ofNullable(carriage.getSecondClassCarriage()).orElse(0);
        for (int i = 0; i < second; i++) map.put(current++, "SECOND");
        return map;
    }

    /**
     * 从Redis获取区间数（hlen命令，高效）
     */
    private int getSectionCountFromRedis(String trainCode) {
        try {
            String key = String.format(STOCK_KEY_PREFIX, trainCode);
            Long fieldCount = stringRedisTemplate.opsForHash().size(key);
            return fieldCount == null || fieldCount <= 0 ? 8 : Math.min(fieldCount.intValue(), 32);
        } catch (Exception e) {
            log.warn("车次{}Redis查询失败，默认8个区间", trainCode);
            return 8;
        }
    }

    /**
     * 生成初始位图（全0，适配varbinary(4)，最多32位）
     */
    private byte[] generateInitBitmap(int sectionCount) {
        int realCount = Math.min(sectionCount, 32);
        int byteLen = (realCount + 7) / 8;
        return new byte[byteLen]; // 字节数组默认全0，无需额外填充
    }

    /**
     * 获取座位规则
     */
    private List<int[]> getSeatRuleByType(String seatType) {
        switch (seatType) {
            case "BUSINESS":
                return BUSINESS_SEAT_RULE;
            case "FIRST":
                return FIRST_CLASS_SEAT_RULE;
            case "SECOND":
                return SECOND_CLASS_SEAT_RULE;
            default:
                return Collections.emptyList();
        }
    }

    /**
     * 带事务的批量插入（核心优化：手动控制事务，提升600万数据插入效率）
     */
    private void batchInsertWithTransaction(ConcurrentLinkedQueue<TrainTicketBitmap> queue) {
        if (queue.isEmpty()) return;

        // 提取批次数据（避免队列在插入时被修改）
        List<TrainTicketBitmap> batchList = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE && !queue.isEmpty(); i++) {
            batchList.add(queue.poll());
        }
        if (batchList.isEmpty()) return;

        // 手动开启事务
        TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try {
            trainTicketBitmapMapper.batchInsert(batchList);
            transactionManager.commit(status); // 手动提交
            log.info("批量插入{}条数据成功（线程：{}）", batchList.size(), Thread.currentThread().getName());
        } catch (Exception e) {
            transactionManager.rollback(status); // 失败回滚
            log.error("批量插入失败，回滚事务", e);
            throw new RuntimeException(e);
        }
    }
}