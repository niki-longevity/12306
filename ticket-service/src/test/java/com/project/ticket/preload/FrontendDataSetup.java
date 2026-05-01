package com.project.ticket.preload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.mapper.TrainCarriageMapper;
import com.project.ticket.mapper.TrainStopoverMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.entity.TrainCarriage;
import com.project.ticket.pojo.entity.TrainStopover;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Preload proper frontend demo data: G1 Beijing→Shanghai with times, prices, stock.
 */
@SpringBootTest
public class FrontendDataSetup {

    @Autowired private StringRedisTemplate redis;
    @Autowired private TrainStopoverMapper stopoverMapper;
    @Autowired private TrainCarriageMapper carriageMapper;
    @Autowired private ObjectMapper mapper;

    @Test
    void setup() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 1);
        String code = "G1";
        String routeKey = date + ":Beijing:Shanghai";

        // 1. Clear old data
        redis.delete(routeKey);
        redis.delete("TrainStop:" + date + ":" + code);

        // 2. Route key
        redis.opsForList().rightPush(routeKey, code);

        // 3. Build TicketListBO from DB
        List<TrainStopover> stops = stopoverMapper.selectList(
                new LambdaQueryWrapper<TrainStopover>()
                        .eq(TrainStopover::getDate, date)
                        .eq(TrainStopover::getCode, code)
                        .orderByAsc(TrainStopover::getStationIndex));
        if (stops.isEmpty()) { System.out.println("No DB data for G1!"); return; }

        List<TicketListBO.StopoverStation> stList = stops.stream().map(s ->
                TicketListBO.StopoverStation.builder()
                        .stopoverStation(s.getStopoverStation())
                        .stationIndex(s.getStationIndex())
                        .inTime(s.getInTime()).outTime(s.getOutTime())
                        .mileage(s.getMileage()).build()
        ).toList();

        TrainCarriage carriage = carriageMapper.selectOne(
                new LambdaQueryWrapper<TrainCarriage>().eq(TrainCarriage::getTrainCode, code));
        int secCount = stops.size() - 1;
        int secondCars = carriage != null ? Optional.ofNullable(carriage.getSecondClassCarriage()).orElse(6) : 6;
        int firstCars = carriage != null ? Optional.ofNullable(carriage.getFirstClassCarriage()).orElse(1) : 1;
        int businessCars = carriage != null ? Optional.ofNullable(carriage.getBusinessCarriage()).orElse(1) : 1;

        TicketListBO bo = TicketListBO.builder().date(date).code(code)
                .startStation(stops.get(0).getStopoverStation())
                .endStation(stops.get(stops.size()-1).getStopoverStation())
                .stopoverStations(stList)
                .businessCarriageInfo(TicketListBO.CarriageInfo.builder().carriageType("商务座车厢")
                        .carriageIndexes(IntStream.rangeClosed(1, businessCars).boxed().toList()).build())
                .firstClassCarriageInfo(TicketListBO.CarriageInfo.builder().carriageType("一等座车厢")
                        .carriageIndexes(IntStream.rangeClosed(businessCars+1, businessCars+firstCars).boxed().toList()).build())
                .secondClassCarriageInfo(TicketListBO.CarriageInfo.builder().carriageType("二等座车厢")
                        .carriageIndexes(IntStream.rangeClosed(businessCars+firstCars+1, businessCars+firstCars+secondCars).boxed().toList()).build())
                .intervalStockMap(buildIntervalStockMap(secCount))
                .build();

        redis.opsForValue().set("TrainStop:" + date + ":" + code, mapper.writeValueAsString(bo));

        // 4. Stock, bitmap, token for second class
        int secSeats = secondCars * 90;
        Map<String, String> stock = new HashMap<>();
        for (int s = 1; s <= secCount; s++) stock.put(String.valueOf(s), String.valueOf(secSeats));
        String sk = "Stock:" + date + ":" + code + ":2";
        redis.delete(sk);
        redis.opsForHash().putAll(sk, stock);

        int totalBits = secSeats * secCount;
        redis.opsForValue().set(date + ":" + code + ":2:bitmap",
                new String(new byte[(totalBits+7)/8], StandardCharsets.ISO_8859_1));
        redis.opsForValue().set("Token:" + date + ":" + code + ":2", String.valueOf(secSeats * secCount));

        System.out.printf("Setup done: G1, %d sections, %d second-class seats, token=%d%n",
                secCount, secSeats, secSeats * secCount);
    }

    private Map<String, TicketListBO.IntervalStockStatus> buildIntervalStockMap(int sections) {
        Map<String, TicketListBO.IntervalStockStatus> map = new HashMap<>();
        for (String seat : List.of("business", "firstClass", "secondClass")) {
            for (int s = 1; s <= sections; s++) {
                map.put(seat + "_" + s, TicketListBO.IntervalStockStatus.builder()
                        .statusCode(2).realStock(-1).build());
            }
        }
        return map;
    }
}
