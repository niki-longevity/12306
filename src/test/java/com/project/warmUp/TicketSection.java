package com.project.warmUp;

import com.project.mapper.LineTrainMapper;
import com.project.mapper.TrainTicketSectionMapper;
import com.project.pojo.entity.LineTrain;
import com.project.pojo.entity.TrainTicketSection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 车票区间库存数据预热到Redis测试类
 * Redis结构：
 * - Key: Stock:date:code:seatType（seatType：0=商务座，1=一等座，2=二等座）
 * - Value: Hash结构（Field=区间序号，Value=余票数量）
 */
@Slf4j
@SpringBootTest
public class TicketSection {

    // 注入StringRedisTemplate（优先用String类型，节省内存）
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // 注入车次表Mapper（获取所有train_code）
    @Autowired
    private LineTrainMapper lineTrainMapper;
    // 注入车票区间库存表Mapper
    @Autowired
    private TrainTicketSectionMapper trainTicketSectionMapper;
    // 固定预热日期：2026-03-03
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 3, 3);
    // 座位类型映射：0=商务座，1=一等座，2=二等座
    private static final int SEAT_TYPE_BUSINESS = 0; // 商务座
    private static final int SEAT_TYPE_FIRST = 1;    // 一等座
    private static final int SEAT_TYPE_SECOND = 2;   // 二等座

    @Test
    public void warmupTicketStockToRedis() {
        long startTime = System.currentTimeMillis();
        log.info("开始预热车票区间库存数据到Redis，目标日期：{}", TARGET_DATE);

        // 1. 获取所有车次编号（从line_train表）
        List<LineTrain> allTrains = lineTrainMapper.selectList(
                new LambdaQueryWrapper<LineTrain>().select(LineTrain::getTrainCode)
        );
        if (allTrains.isEmpty()) {
            log.warn("干线车次表(line_train)无数据，库存预热终止");
            return;
        }
        List<String> allTrainCodes = allTrains.stream()
                .map(LineTrain::getTrainCode)
                .distinct() // 去重（确保车次编号唯一）
                .collect(Collectors.toList());
        log.info("共获取到{}个车次编号", allTrainCodes.size());

        // 2. 批量查询目标日期的所有库存数据（避免循环查库）
        LambdaQueryWrapper<TrainTicketSection> queryWrapper = new LambdaQueryWrapper<TrainTicketSection>()
                .eq(TrainTicketSection::getDate, TARGET_DATE)
                .in(TrainTicketSection::getCode, allTrainCodes) // 只查存在的车次
                .select(
                        TrainTicketSection::getCode,
                        TrainTicketSection::getSectionIndex,
                        TrainTicketSection::getBusinessSeat,
                        TrainTicketSection::getFirstSeat,
                        TrainTicketSection::getSecondSeat
                ); // 只查需要的字段，提升效率
        List<TrainTicketSection> allStockData = trainTicketSectionMapper.selectList(queryWrapper);
        if (allStockData.isEmpty()) {
            log.warn("目标日期{}无车票库存数据，预热终止", TARGET_DATE);
            return;
        }
        log.info("共查询到{}条库存数据", allStockData.size());

        // 3. 按「车次+座位类型」分组，构建Redis Hash数据
        // 临时存储：Key=Stock:date:code:seatType，Value=Map<区间序号, 票数>
        Map<String, Map<String, String>> redisHashData = new HashMap<>();

        for (TrainTicketSection section : allStockData) {
            String trainCode = section.getCode();
            Integer sectionIndex = section.getSectionIndex();
            // 过滤无效数据（区间序号为空则跳过）
            if (trainCode == null || sectionIndex == null) {
                log.warn("无效库存数据：车次={}，区间序号=null，跳过", trainCode);
                continue;
            }
            String sectionIndexStr = sectionIndex.toString();

            // ========== 处理商务座（0） ==========
            Integer businessSeat = section.getBusinessSeat() == null ? 0 : section.getBusinessSeat();
            String businessKey = buildRedisKey(trainCode, SEAT_TYPE_BUSINESS);
            redisHashData.computeIfAbsent(businessKey, k -> new HashMap<>())
                    .put(sectionIndexStr, businessSeat.toString());

            // ========== 处理一等座（1） ==========
            Integer firstSeat = section.getFirstSeat() == null ? 0 : section.getFirstSeat();
            String firstKey = buildRedisKey(trainCode, SEAT_TYPE_FIRST);
            redisHashData.computeIfAbsent(firstKey, k -> new HashMap<>())
                    .put(sectionIndexStr, firstSeat.toString());

            // ========== 处理二等座（2） ==========
            Integer secondSeat = section.getSecondSeat() == null ? 0 : section.getSecondSeat();
            String secondKey = buildRedisKey(trainCode, SEAT_TYPE_SECOND);
            redisHashData.computeIfAbsent(secondKey, k -> new HashMap<>())
                    .put(sectionIndexStr, secondSeat.toString());
        }

        // 4. 批量写入Redis（减少网络交互，提升效率）
        int successCount = 0;
        for (Map.Entry<String, Map<String, String>> entry : redisHashData.entrySet()) {
            String redisKey = entry.getKey();
            Map<String, String> hashData = entry.getValue();
            try {
                // 写入Redis Hash（覆盖原有数据，保证数据最新）
                stringRedisTemplate.opsForHash().putAll(redisKey, hashData);
                successCount++;
            } catch (Exception e) {
                log.error("写入Redis失败，Key={}，异常：{}", redisKey, e.getMessage());
            }
        }

        // 5. 打印预热结果
        long costTime = System.currentTimeMillis() - startTime;
        log.info("车票库存数据预热完成！");
        log.info("- 成功写入Redis Key数量：{}", successCount);
        log.info("- 总耗时：{}ms", costTime);
        log.info("- Redis Key示例：Stock:2026-03-03:G1234:0（商务座）、Stock:2026-03-03:G1234:1（一等座）");
    }

    /**
     * 构建Redis Key：Stock:date:code:seatType
     * 示例：Stock:2026-03-03:X101:0
     */
    private String buildRedisKey(String trainCode, int seatType) {
        return String.format("Stock:%s:%s:%d", TARGET_DATE.toString(), trainCode, seatType);
    }
}