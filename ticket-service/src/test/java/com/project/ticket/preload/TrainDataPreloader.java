package com.project.ticket.preload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.mapper.TrainCarriageMapper;
import com.project.ticket.mapper.TrainStopoverMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.entity.TrainCarriage;
import com.project.ticket.pojo.entity.TrainStopover;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * TEST-ONLY: Preload train static data (stops + carriage) into Redis.
 * Run manually when schedule data changes. Not auto-executed on startup.
 */
@Slf4j
@Component("trainDataPreloader")
@RequiredArgsConstructor
public class TrainDataPreloader {

    private final TrainStopoverMapper trainStopoverMapper;
    private final TrainCarriageMapper trainCarriageMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String REDIS_KEY_PREFIX = "TrainStop:";

    public void preloadAll(LocalDate startDate, int days) {
        for (int d = 0; d < days; d++) {
            LocalDate date = startDate.plusDays(d);
            preloadDate(date);
        }
    }

    public void preloadDate(LocalDate date) {
        log.info("Preloading train data for {} to Redis...", date);

        List<TrainStopover> stopovers = trainStopoverMapper.selectList(
                new LambdaQueryWrapper<TrainStopover>()
                        .eq(TrainStopover::getDate, date)
        );
        if (stopovers.isEmpty()) {
            log.warn("No trains for {}", date);
            return;
        }

        Map<String, List<TrainStopover>> grouped = stopovers.stream()
                .collect(Collectors.groupingBy(TrainStopover::getCode));

        int count = 0;
        for (Map.Entry<String, List<TrainStopover>> entry : grouped.entrySet()) {
            String trainCode = entry.getKey();
            List<TrainStopover> trainStops = entry.getValue().stream()
                    .filter(s -> s.getStationIndex() != null)
                    .sorted(Comparator.comparing(TrainStopover::getStationIndex))
                    .toList();

            if (trainStops.isEmpty()) continue;

            String cacheKey = date + ":" + trainCode;
            TicketListBO bo = buildTicketListBO(date, trainCode, trainStops);

            try {
                String json = objectMapper.writeValueAsString(bo);
                String redisKey = REDIS_KEY_PREFIX + cacheKey;
                long ttlSeconds = Duration.between(LocalDate.now().atStartOfDay(),
                        date.atStartOfDay().plusDays(2)).getSeconds();
                stringRedisTemplate.opsForValue().set(redisKey, json, Duration.ofSeconds(ttlSeconds));
                count++;
            } catch (Exception e) {
                log.error("Failed to preload {}", cacheKey, e);
            }
        }
        log.info("Preloaded {} trains for {}", count, date);
    }

    private TicketListBO buildTicketListBO(LocalDate date, String code, List<TrainStopover> stops) {
        List<TicketListBO.StopoverStation> stationList = stops.stream()
                .map(s -> TicketListBO.StopoverStation.builder()
                        .stopoverStation(s.getStopoverStation())
                        .stationIndex(s.getStationIndex())
                        .inTime(s.getInTime()).outTime(s.getOutTime())
                        .mileage(s.getMileage()).build())
                .toList();

        // Read actual carriage config from DB
        TrainCarriage carriage = trainCarriageMapper.selectOne(
                new LambdaQueryWrapper<TrainCarriage>().eq(TrainCarriage::getTrainCode, code));
        TicketListBO.CarriageInfo business = null, first = null, second = null;
        if (carriage != null) {
            int bc = Optional.ofNullable(carriage.getBusinessCarriage()).orElse(0);
            int fc = Optional.ofNullable(carriage.getFirstClassCarriage()).orElse(0);
            int sc = Optional.ofNullable(carriage.getSecondClassCarriage()).orElse(0);
            if (bc > 0) business = buildCarriageInfo("商务座车厢", 1, bc);
            if (fc > 0) first = buildCarriageInfo("一等座车厢", bc + 1, bc + fc);
            if (sc > 0) second = buildCarriageInfo("二等座车厢", bc + fc + 1, bc + fc + sc);
        }

        return TicketListBO.builder()
                .date(date).code(code)
                .startStation(stops.get(0).getStopoverStation())
                .endStation(stops.get(stops.size() - 1).getStopoverStation())
                .stopoverStations(stationList)
                .businessCarriageInfo(business)
                .firstClassCarriageInfo(first)
                .secondClassCarriageInfo(second)
                .build();
    }

    private TicketListBO.CarriageInfo buildCarriageInfo(String type, int start, int end) {
        return TicketListBO.CarriageInfo.builder()
                .carriageType(type)
                .carriageIndexes(IntStream.rangeClosed(start, end).boxed().toList())
                .build();
    }
}