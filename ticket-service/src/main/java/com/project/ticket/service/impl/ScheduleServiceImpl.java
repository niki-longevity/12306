package com.project.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.mapper.*;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.entity.*;
import com.project.ticket.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {

    private final ScheduleWindowMapper windowMapper;
    private final TrainTemplateMapper templateMapper;
    private final TrainTemplateStopoverMapper stopoverMapper;
    private final TrainStopoverMapper trainStopoverMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void advanceWindow() {
        ScheduleWindow window = windowMapper.selectById(1);
        if (window == null) {
            log.warn("schedule_window not initialized, skipping advance");
            return;
        }
        LocalDate newDate = window.getWindowEnd().plusDays(1);
        generateDayData(newDate);
        window.setWindowStart(window.getWindowStart().plusDays(1));
        window.setWindowEnd(newDate);
        windowMapper.updateById(window);
        log.info("Advanced schedule window: {} -> {}", window.getWindowStart(), window.getWindowEnd());
    }

    @Override
    @Transactional
    public void initialize15Days(LocalDate fromDate) {
        ScheduleWindow existing = windowMapper.selectById(1);
        if (existing != null && existing.getWindowEnd() != null) {
            log.info("Schedule window already initialized: {} -> {}",
                    existing.getWindowStart(), existing.getWindowEnd());
            return;
        }
        for (int i = 0; i < 15; i++) {
            generateDayData(fromDate.plusDays(i));
        }
        ScheduleWindow window = ScheduleWindow.builder()
                .id(1).windowStart(fromDate).windowEnd(fromDate.plusDays(14)).build();
        if (existing != null) {
            windowMapper.updateById(window);
        } else {
            windowMapper.insert(window);
        }
        log.info("Initialized 15-day schedule window: {} -> {}", fromDate, fromDate.plusDays(14));
    }

    @Override
    public boolean isDateInWindow(LocalDate date) {
        ScheduleWindow window = windowMapper.selectById(1);
        if (window == null) return true;
        return !date.isBefore(window.getWindowStart()) && !date.isAfter(window.getWindowEnd());
    }

    @Override
    public LocalDate getWindowStart() {
        ScheduleWindow w = windowMapper.selectById(1);
        return w != null ? w.getWindowStart() : LocalDate.now();
    }

    @Override
    public LocalDate getWindowEnd() {
        ScheduleWindow w = windowMapper.selectById(1);
        return w != null ? w.getWindowEnd() : LocalDate.now().plusDays(14);
    }

    private void generateDayData(LocalDate date) {
        List<TrainTemplate> templates = templateMapper.selectList(new LambdaQueryWrapper<>());
        if (CollectionUtils.isEmpty(templates)) {
            log.warn("No train templates found for date {}", date);
            return;
        }
        for (TrainTemplate tmpl : templates) {
            List<TrainTemplateStopover> stops = stopoverMapper.selectList(
                    new LambdaQueryWrapper<TrainTemplateStopover>()
                            .eq(TrainTemplateStopover::getTrainCode, tmpl.getTrainCode())
                            .orderByAsc(TrainTemplateStopover::getStationIndex));
            if (stops.size() < 2) continue;

            for (TrainTemplateStopover s : stops) {
                TrainStopover entity = TrainStopover.builder()
                        .date(date).code(tmpl.getTrainCode())
                        .stopoverStation(s.getStationName()).stationIndex(s.getStationIndex())
                        .inTime(s.getInTime()).outTime(s.getOutTime()).mileage(s.getMileage()).build();
                trainStopoverMapper.insert(entity);
            }

            int secCount = stops.size() - 1;
            int secondCars = tmpl.getSecondClassCarriage() != null ? tmpl.getSecondClassCarriage() : 6;
            int firstCars = tmpl.getFirstClassCarriage() != null ? tmpl.getFirstClassCarriage() : 1;
            int businessCars = tmpl.getBusinessCarriage() != null ? tmpl.getBusinessCarriage() : 1;

            buildAndCacheTrainBO(date, tmpl, stops, businessCars, firstCars, secondCars);

            initSeatStock(date, tmpl.getTrainCode(), "2", secondCars * 90, secCount);
            initSeatStock(date, tmpl.getTrainCode(), "1", firstCars * 28, secCount);
            initSeatStock(date, tmpl.getTrainCode(), "0", businessCars * 5, secCount);

            for (int i = 0; i < stops.size(); i++) {
                for (int j = i + 1; j < stops.size(); j++) {
                    String routeKey = date + ":" + stops.get(i).getStationName() + ":" + stops.get(j).getStationName();
                    redis.opsForList().rightPush(routeKey, tmpl.getTrainCode());
                }
            }
        }
    }

    private void buildAndCacheTrainBO(LocalDate date, TrainTemplate tmpl, List<TrainTemplateStopover> stops,
                                       int businessCars, int firstCars, int secondCars) {
        List<TicketListBO.StopoverStation> stList = stops.stream().map(s ->
                TicketListBO.StopoverStation.builder()
                        .stopoverStation(s.getStationName()).stationIndex(s.getStationIndex())
                        .inTime(s.getInTime()).outTime(s.getOutTime()).mileage(s.getMileage()).build()
        ).toList();

        Map<String, TicketListBO.IntervalStockStatus> stockMap = new HashMap<>();
        for (String seat : List.of("business", "firstClass", "secondClass")) {
            for (int s = 1; s <= stops.size() - 1; s++) {
                stockMap.put(seat + "_" + s,
                        TicketListBO.IntervalStockStatus.builder().statusCode(2).realStock(-1).build());
            }
        }

        TicketListBO.CarriageInfo businessInfo = businessCars > 0
                ? TicketListBO.CarriageInfo.builder().carriageType("商务座车厢")
                    .carriageIndexes(IntStream.rangeClosed(1, businessCars).boxed().toList()).build()
                : null;
        TicketListBO.CarriageInfo firstInfo = firstCars > 0
                ? TicketListBO.CarriageInfo.builder().carriageType("一等座车厢")
                    .carriageIndexes(IntStream.rangeClosed(businessCars + 1, businessCars + firstCars).boxed().toList()).build()
                : null;
        TicketListBO.CarriageInfo secondInfo = secondCars > 0
                ? TicketListBO.CarriageInfo.builder().carriageType("二等座车厢")
                    .carriageIndexes(IntStream.rangeClosed(businessCars + firstCars + 1, businessCars + firstCars + secondCars).boxed().toList()).build()
                : null;

        TicketListBO bo = TicketListBO.builder().date(date).code(tmpl.getTrainCode())
                .startStation(stops.get(0).getStationName())
                .endStation(stops.get(stops.size() - 1).getStationName())
                .stopoverStations(stList)
                .intervalStockMap(stockMap)
                .businessCarriageInfo(businessInfo)
                .firstClassCarriageInfo(firstInfo)
                .secondClassCarriageInfo(secondInfo)
                .build();

        try {
            redis.opsForValue().set("TrainStop:" + date + ":" + tmpl.getTrainCode(),
                    objectMapper.writeValueAsString(bo));
        } catch (Exception e) {
            log.error("Failed to cache TrainBO for {} {}", date, tmpl.getTrainCode(), e);
        }
    }

    private void initSeatStock(LocalDate date, String code, String seatType, int seatsPerCar, int secCount) {
        int totalSeats = seatsPerCar;
        Map<String, String> stock = new HashMap<>();
        for (int s = 1; s <= secCount; s++) stock.put(String.valueOf(s), String.valueOf(totalSeats));
        String sk = "Stock:" + date + ":" + code + ":" + seatType;
        redis.delete(sk);
        redis.opsForHash().putAll(sk, stock);

        int totalBits = totalSeats * secCount;
        redis.opsForValue().set(date + ":" + code + ":" + seatType + ":bitmap",
                new String(new byte[(totalBits + 7) / 8], StandardCharsets.ISO_8859_1));
        redis.opsForValue().set("Token:" + date + ":" + code + ":" + seatType,
                String.valueOf(totalSeats * secCount));
    }
}
