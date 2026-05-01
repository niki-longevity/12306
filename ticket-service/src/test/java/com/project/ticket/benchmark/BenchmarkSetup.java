package com.project.ticket.benchmark;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.mapper.TrainCarriageMapper;
import com.project.ticket.mapper.TrainStopoverMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.entity.TrainCarriage;
import com.project.ticket.pojo.entity.TrainStopover;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Preloads test data: 100 trains for 2026-05-01 into Redis (route index, train data, bitmap, stock, token).
 * Call BenchmarkSetup.setup(redis, stopoverMapper, carriageMapper, mapper) before benchmarks.
 */
@Slf4j
public class BenchmarkSetup {
    public static final LocalDate DATE = LocalDate.of(2026, 5, 1);
    public static String ROUTE_KEY;
    public static final int SECTION_COUNT = 19;
    public static final int SECOND_CLASS_SEATS = 6 * 90;
    public static List<String> trainCodes;       // all 100 trains
    public static List<String> routeTrainCodes;  // trains matching the best route
    public static String routeStart;
    public static String routeEnd;

    public static void setup(StringRedisTemplate redis, TrainStopoverMapper stopoverMapper,
                              TrainCarriageMapper carriageMapper, ObjectMapper mapper) {
        long t0 = System.currentTimeMillis();

        // 1. Load train codes + stopover data from DB
        List<TrainStopover> allStops = stopoverMapper.selectList(
                new LambdaQueryWrapper<TrainStopover>()
                        .eq(TrainStopover::getDate, DATE)
                        .orderByAsc(TrainStopover::getCode, TrainStopover::getStationIndex));
        Map<String, List<TrainStopover>> grouped = allStops.stream().collect(Collectors.groupingBy(TrainStopover::getCode));
        trainCodes = new ArrayList<>(grouped.keySet());
        log.info("Loaded {} trains from DB", trainCodes.size());

        // 2. Populate route index: all trains under one common route for max spread
        routeStart = trainCodes.stream().map(c -> grouped.get(c).get(0).getStopoverStation())
                .reduce((a, b) -> a.length() <= b.length() ? a : b).orElse("S201");
        routeEnd = trainCodes.stream().map(c -> grouped.get(c).get(grouped.get(c).size()-1).getStopoverStation())
                .reduce((a, b) -> a.length() <= b.length() ? a : b).orElse("S220");
        ROUTE_KEY = DATE + ":" + routeStart + ":" + routeEnd;
        routeTrainCodes = new ArrayList<>(trainCodes);

        redis.delete(ROUTE_KEY);
        routeTrainCodes.forEach(c -> redis.opsForList().rightPush(ROUTE_KEY, c));
        log.info("Route: {}→{} with {} trains", routeStart, routeEnd, routeTrainCodes.size());

        // 3. Load carriage config from DB
        Map<String, TrainCarriage> carriageMap = carriageMapper.selectList(
                new LambdaQueryWrapper<TrainCarriage>().in(TrainCarriage::getTrainCode, trainCodes))
                .stream().collect(Collectors.toMap(TrainCarriage::getTrainCode, c -> c));

        // 4. Write TrainStop data + init bitmap/stock/token for each train
        for (String code : trainCodes) {
            List<TrainStopover> stops = grouped.get(code);
            TrainCarriage carriage = carriageMap.get(code);

            // Build TicketListBO
            List<TicketListBO.StopoverStation> stList = stops.stream()
                    .map(s -> TicketListBO.StopoverStation.builder()
                            .stopoverStation(s.getStopoverStation()).stationIndex(s.getStationIndex())
                            .inTime(s.getInTime()).outTime(s.getOutTime()).mileage(s.getMileage()).build())
                    .toList();

            TicketListBO.CarriageInfo secondInfo = null;
            if (carriage != null) {
                int sc = Optional.ofNullable(carriage.getSecondClassCarriage()).orElse(6);
                secondInfo = TicketListBO.CarriageInfo.builder()
                        .carriageType("二等座车厢")
                        .carriageIndexes(IntStream.rangeClosed(3, 2 + sc).boxed().toList()).build();
            }

            TicketListBO bo = TicketListBO.builder().date(DATE).code(code)
                    .startStation(stops.get(0).getStopoverStation()).endStation(stops.get(stops.size()-1).getStopoverStation())
                    .stopoverStations(stList).secondClassCarriageInfo(secondInfo).build();

            try {
                redis.opsForValue().set("TrainStop:" + DATE + ":" + code, mapper.writeValueAsString(bo));
            } catch (Exception e) { log.error("Failed to write TrainStop for {}", code, e); }

            // Init bitmap (all zeros)
            int totalBits = SECOND_CLASS_SEATS * SECTION_COUNT;
            byte[] zeros = new byte[(totalBits + 7) / 8];
            redis.opsForValue().set(DATE + ":" + code + ":2:bitmap", new String(zeros, StandardCharsets.ISO_8859_1));

            // Init stock
            Map<String, String> stock = new HashMap<>();
            for (int s = 1; s <= SECTION_COUNT; s++) stock.put(String.valueOf(s), String.valueOf(SECOND_CLASS_SEATS));
            String stockKey = "Stock:" + DATE + ":" + code + ":2";
            redis.delete(stockKey);
            redis.opsForHash().putAll(stockKey, stock);

            // Init token
            redis.opsForValue().set("Token:" + DATE + ":" + code + ":2", String.valueOf(SECOND_CLASS_SEATS * SECTION_COUNT));
        }
        long elapsed = System.currentTimeMillis() - t0;
        log.info("Benchmark setup done: {} trains, {}ms", trainCodes.size(), elapsed);
    }
}
