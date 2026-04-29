package com.project.data;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.mapper.*;
import com.project.pojo.entity.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
public class TrainData {

    @Autowired
    private LineMapper lineMapper;

    @Autowired
    private LineStationMapper lineStationMapper;

    @Autowired
    private LineTrainMapper lineTrainMapper;

    @Autowired
    private TrainStopoverMapper trainStopoverMapper;

    @Autowired
    private TrainTicketMapper trainTicketMapper;

    @Autowired
    private TrainTicketSectionMapper trainTicketSectionMapper;

    @Autowired
    private TrainCarriageMapper trainCarriageMapper;

    /**
     * 造干线数据：400条干线，X1-X400
     */
    @Test
    public void createLineData() {
        for (int i = 1; i <= 400; i++) {
            // 用mybatis-plus 插入
            String lineCode = "X" + i;
            Line line = new Line();
            line.setLineCode(lineCode);
            lineMapper.insert(line);
        }
    }

    /**
     * 造站点数据：每条干线20个车站，共8000个车站，S1-S8000
     */
    @Test
    public void createStationData() {
        for (int i = 1; i <= 400; i++) {
            String lineCode = "X" + i;
            for (int j = 1; j <= 20; j++) {
                LineStation lineStation = new LineStation();
                lineStation.setLineCode(lineCode);
                String stationCode = "S" + ((i-1) * 20 + j);
                lineStation.setStation(stationCode);
                lineStation.setMileage(j * 30);
                lineStationMapper.insert(lineStation);
            }
        }
    }

    /**
     * 造车次数据：前60条干线占6000个车次，G1-G6000，后340条干线占3400个车次，G6001-G9400
     */
    @Test
    public void createTrainData() {
        for (int i = 1; i <= 60; i++) {
            String lineCode = "X" + i;
            for (int j = 1; j <= 100; j++) {
                LineTrain lineTrain = new LineTrain();
                lineTrain.setLineCode(lineCode);
                String trainCode = "G" + ((i-1) * 100 + j);
                lineTrain.setTrainCode(trainCode);
                lineTrainMapper.insert(lineTrain);
            }
        }
        for (int i = 61; i <= 400; i++) {
            String lineCode = "X" + i;
            for (int j = 1; j <= 10; j++) {
                LineTrain lineTrain = new LineTrain();
                lineTrain.setLineCode(lineCode);
                String trainCode = "G" + (((i-61) * 10) + j + 6000);
                lineTrain.setTrainCode(trainCode);
                lineTrainMapper.insert(lineTrain);
            }
        }
    }

    /**
     * 造车次经停表数据：每个车次20个经停站
     * 先提取干线对应的站点信息，再提取干线对应的车次信息，最后循环插入车次经停信息
     */
    @Test
    public void createTrainStopoverData() {
        Random random = new Random();
        LocalDate date = LocalDate.of(2026, 3, 3);

        for (int i = 1; i <= 400; i++) {
            String lineCode = "X" + i;
            // 查询本干线的所有站点信息
            LambdaQueryWrapper<LineStation> queryWrapper = new LambdaQueryWrapper<LineStation>()
                    .eq(LineStation::getLineCode, lineCode);
            List<LineStation> lineStations = lineStationMapper.selectList(queryWrapper);
            int stationCount = lineStations.size(); // 站点总数（固定20）

            // 查询本干线的所有车次信息
            LambdaQueryWrapper<LineTrain> queryWrapper1 = new LambdaQueryWrapper<LineTrain>()
                    .eq(LineTrain::getLineCode, lineCode);
            List<LineTrain> lineTrains = lineTrainMapper.selectList(queryWrapper1);

            // 一半的车次正向运行，一半的车次反向运行
            for (int j = 0; j < lineTrains.size(); j++) {
                LineTrain lineTrain = lineTrains.get(j);
                String trainCode = lineTrain.getTrainCode();

                // 1. 为每个车次生成随机起始时间（避免所有车次都从固定时间出发）
                // 正向车次：6:00-10:00随机起始；反向车次：18:00-22:00随机起始
                int startHour = j % 2 == 0 ?
                        random.nextInt(4) + 6 : // 6-9点
                        random.nextInt(4) + 18; // 18-21点
                int startMinute = random.nextInt(60); // 0-59分
                LocalTime baseTime = LocalTime.of(startHour, startMinute);

                // 2. 遍历站点生成经停信息
                for (int k = 0; k < stationCount; k++) {
                    LineStation lineStation = lineStations.get(k);
                    String station = lineStation.getStation();

                    TrainStopover trainStopover = new TrainStopover();
                    trainStopover.setDate(date);
                    trainStopover.setCode(trainCode);
                    trainStopover.setStopoverStation(station);

                    if (j % 2 == 0) {
                        // 正向运行：站点索引从1开始，时间逐站递增
                        trainStopover.setStationIndex(k + 1);
                        // 正向里程：从起点开始递增（0 → 30 → 60 ...）
                        trainStopover.setMileage(k * 30);
                        // 进站时间：基准时间 + 每站耗时（8-12分钟随机，更真实）
                        int interval = random.nextInt(4) + 8; // 8-11分钟/站
                        LocalTime inTime = baseTime.plusMinutes(k * interval);
                        // 出站时间：进站后停留2-5分钟（模拟实际停车）
                        LocalTime outTime = inTime.plusMinutes(random.nextInt(4) + 2);
                        trainStopover.setInTime(inTime);
                        trainStopover.setOutTime(outTime);
                    } else {
                        // 反向运行：修复时间+里程逻辑
                        // 反向站点索引：最后一站是1，第一站是stationCount
                        int reverseIndex = stationCount - k;
                        trainStopover.setStationIndex(reverseIndex);
                        // 反向里程：从终点开始递减（(20-0-1)*30=570 → (20-1-1)*30=540 ... → 0）
                        trainStopover.setMileage((stationCount - k - 1) * 30);
                        // 反向时间：同样逐站递增（避免时间倒流）
                        int interval = random.nextInt(4) + 8;
                        LocalTime inTime = baseTime.plusMinutes(k * interval);
                        LocalTime outTime = inTime.plusMinutes(random.nextInt(4) + 2);
                        trainStopover.setInTime(inTime);
                        trainStopover.setOutTime(outTime);
                    }

                    trainStopoverMapper.insert(trainStopover);
                }
            }
        }
    }

    /**
     * 造余票数据：分车次级别的表和区间级别的表
     * 商务座：5个座位，一等座：28个，二等座：90 × 6 = 540个
     */
    @Test
    public void createTicketData() {
        LocalDate date = LocalDate.of(2026, 3, 3);

        // 把 lineTrain 表的 trainCode 全部提取出来
        LambdaQueryWrapper<LineTrain> queryWrapper = new LambdaQueryWrapper<LineTrain>();
        List<LineTrain> lineTrains = lineTrainMapper.selectList(queryWrapper);

        // 循环每个车次，插入余票表（一个车次级别的宽表，一个区间级别的表）
        for (LineTrain lineTrain : lineTrains) {
            String trainCode = lineTrain.getTrainCode();
            // 车次级别的表
            TrainTicket trainTicket = TrainTicket.builder()
                    .date(date)
                    .code(trainCode)
                    .businessSeat(5)
                    .firstSeat(28)
                    .secondSeat(540)
                    .build();
            trainTicketMapper.insert(trainTicket);
            // 区间级别的表
            // 根据 trainCode 去查询 trainStopover 表，获取车次对应的区间数量
            LambdaQueryWrapper<TrainStopover> queryWrapper1 = new LambdaQueryWrapper<TrainStopover>()
                    .eq(TrainStopover::getCode, trainCode);
            List<TrainStopover> trainStopovers = trainStopoverMapper.selectList(queryWrapper1);
            int sectionCount = trainStopovers.size() - 1;
            for (int i = 1; i <= sectionCount; i++) {
                TrainTicketSection trainTicketSection = TrainTicketSection.builder()
                        .date(date)
                        .code(trainCode)
                        .sectionIndex(i)
                        .businessSeat(5)
                        .firstSeat(28)
                        .secondSeat(540)
                        .build();
                trainTicketSectionMapper.insert(trainTicketSection);
            }
        }
    }

    /**
     * 核心造数方法：从line_train读取所有车次，插入train_carriage（商务1、一等1、二等6）
     */
    @Test
    public void generateTrainCarriageData() {
        // ========== 步骤1：读取line_train所有train_code ==========
        List<LineTrain> lineTrainList = lineTrainMapper.selectList(null); // 查所有干线车次
        if (CollectionUtils.isEmpty(lineTrainList)) {
            log.warn("line_train表无数据，无需造数");
            return;
        }
        // 提取所有车次编号（去重，避免同一车次多次处理）
        List<String> allTrainCodes = lineTrainList.stream()
                .map(LineTrain::getTrainCode)
                .distinct() // 去重
                .filter(trainCode -> trainCode != null && !trainCode.isEmpty()) // 过滤空车次
                .collect(Collectors.toList());
        log.info("从line_train表读取到有效车次数量：{}", allTrainCodes.size());

        // ========== 步骤2：查询train_carriage已存在的车次，避免重复插入 ==========
        List<TrainCarriage> existCarriageList = trainCarriageMapper.selectList(null);
        List<String> existTrainCodes = existCarriageList.stream()
                .map(TrainCarriage::getTrainCode)
                .collect(Collectors.toList());
        // 筛选出需要插入的车次（不存在于train_carriage的车次）
        List<String> needInsertTrainCodes = allTrainCodes.stream()
                .filter(trainCode -> !existTrainCodes.contains(trainCode))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(needInsertTrainCodes)) {
            log.info("所有车次已存在于train_carriage表，无需插入");
            return;
        }
        log.info("需要插入的新车次数量：{}", needInsertTrainCodes.size());

        // ========== 步骤3：构建插入数据，批量插入 ==========
        List<TrainCarriage> insertList = new ArrayList<>();
        for (String trainCode : needInsertTrainCodes) {
            TrainCarriage carriage = new TrainCarriage();
            carriage.setTrainCode(trainCode);
            carriage.setBusinessCarriage(1); // 商务车厢数量=1
            carriage.setFirstClassCarriage(1); // 一等车厢数量=1
            carriage.setSecondClassCarriage(6); // 二等车厢数量=6
            insertList.add(carriage);

            // 每1000条批量插入一次（避免单次插入数据量过大）
            if (insertList.size() >= 1000) {
                trainCarriageMapper.batchInsert(insertList); // 批量插入
                log.info("批量插入{}条车次车厢数据", insertList.size());
                insertList.clear();
            }
        }

        // 插入剩余的不足1000条的数据
        if (!CollectionUtils.isEmpty(insertList)) {
            trainCarriageMapper.batchInsert(insertList);
            log.info("批量插入剩余{}条车次车厢数据", insertList.size());
        }

        // ========== 步骤4：验证结果 ==========
//        int totalCount = trainCarriageMapper.selectCount(null);
//        log.info("造数完成！train_carriage表总数据量：{}", totalCount);
    }


}
