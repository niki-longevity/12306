package com.project.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.entity.TrainCarriage;
import com.project.ticket.pojo.entity.TrainStopover;
import com.project.ticket.pojo.enums.SeatType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * One-shot: preload G1 train data + token + bitmap + stock to Redis for 2026-05-01.
 */
@SpringBootTest
public class E2EPreloadTest {

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper mapper;

    private static final LocalDate DATE = LocalDate.of(2026, 5, 1);
    private static final String CODE = "G1";
    private static final int TOTAL_STATIONS = 20;    // Beijing → Shanghai, 20 stops
    private static final int TOTAL_SECTIONS = 19;

    // Fixed: 20 stations, station names hardcoded for G1
    private static final String[] G1_STATIONS = {
        "Beijing", "Tianjin", "Jinan", "Xuzhou", "Bengbu",
        "Nanjing", "Zhenjiang", "Changzhou", "Wuxi", "Suzhou",
        "Shanghai", "Kunshan", "Hangzhou", "Ningbo", "Wenzhou",
        "Fuzhou", "Xiamen", "Shenzhen", "Guangzhou", "Changsha"
    };

    @Test
    void preloadAll() throws Exception {
        // === 1. TrainStop data to Redis ===
        List<TicketListBO.StopoverStation> stations = new ArrayList<>();
        for (int i = 0; i < TOTAL_STATIONS; i++) {
            stations.add(TicketListBO.StopoverStation.builder()
                    .stopoverStation(G1_STATIONS[i])
                    .stationIndex(i + 1)
                    .mileage(i * 100)
                    .build());
        }

        TicketListBO.CarriageInfo business = TicketListBO.CarriageInfo.builder()
                .carriageType("商务座车厢")
                .carriageIndexes(List.of(1))
                .build();
        TicketListBO.CarriageInfo first = TicketListBO.CarriageInfo.builder()
                .carriageType("一等座车厢")
                .carriageIndexes(List.of(2))
                .build();
        TicketListBO.CarriageInfo second = TicketListBO.CarriageInfo.builder()
                .carriageType("二等座车厢")
                .carriageIndexes(IntStream.rangeClosed(3, 8).boxed().toList())
                .build();

        TicketListBO bo = TicketListBO.builder()
                .date(DATE).code(CODE)
                .startStation(G1_STATIONS[0]).endStation(G1_STATIONS[TOTAL_STATIONS - 1])
                .stopoverStations(stations)
                .businessCarriageInfo(business).firstClassCarriageInfo(first).secondClassCarriageInfo(second)
                .build();

        String trainKey = "TrainStop:" + DATE + ":" + CODE;
        redis.opsForValue().set(trainKey, mapper.writeValueAsString(bo));
        System.out.println("[OK] TrainStop data: " + trainKey);

        // === 2. Token ===
        int businessSeats = 1 * 5;   // 1 car × 5 seats
        int firstSeats    = 1 * 28;  // 1 car × 28 seats
        int secondSeats   = 6 * 90;  // 6 cars × 90 seats

        redis.opsForValue().set("Token:" + DATE + ":" + CODE + ":0", String.valueOf(businessSeats * TOTAL_SECTIONS));
        redis.opsForValue().set("Token:" + DATE + ":" + CODE + ":1", String.valueOf(firstSeats * TOTAL_SECTIONS));
        redis.opsForValue().set("Token:" + DATE + ":" + CODE + ":2", String.valueOf(secondSeats * TOTAL_SECTIONS));
        System.out.println("[OK] Tokens: business=" + businessSeats * TOTAL_SECTIONS +
                " first=" + firstSeats * TOTAL_SECTIONS + " second=" + secondSeats * TOTAL_SECTIONS);

        // === 3. Stock (HMGET-able) ===
        for (int seatType : new int[]{0, 1, 2}) {
            int seatsPerType = switch (seatType) { case 0 -> businessSeats; case 1 -> firstSeats; default -> secondSeats; };
            String stockKey = "Stock:" + DATE + ":" + CODE + ":" + seatType;
            Map<String, String> stockMap = new HashMap<>();
            for (int s = 1; s <= TOTAL_SECTIONS; s++) {
                stockMap.put(String.valueOf(s), String.valueOf(seatsPerType));
            }
            redis.opsForHash().putAll(stockKey, stockMap);
            System.out.println("[OK] Stock: " + stockKey + " (" + seatsPerType + " per section)");
        }

        // === 4. Bitmap (all zeros) ===
        for (int seatType : new int[]{0, 1, 2}) {
            int seatsPerType = switch (seatType) { case 0 -> businessSeats; case 1 -> firstSeats; default -> secondSeats; };
            int totalBits = seatsPerType * TOTAL_SECTIONS;
            int totalBytes = (totalBits + 7) / 8;
            byte[] zeros = new byte[totalBytes];
            String bitmapKey = DATE + ":" + CODE + ":" + seatType + ":bitmap";
            redis.opsForValue().set(bitmapKey, new String(zeros, java.nio.charset.StandardCharsets.ISO_8859_1));
            System.out.println("[OK] Bitmap: " + bitmapKey + " (" + totalBits + " bits, " + totalBytes + " bytes)");
        }

        // === 5. Verify ===
        System.out.println("\n=== Preload Complete ===");
        System.out.println("Redis train key: " + redis.opsForValue().get(trainKey) != null ? "OK" : "MISS");
        System.out.println("Token: " + redis.opsForValue().get("Token:" + DATE + ":" + CODE + ":2"));
        System.out.println("Stock section 1: " +
                redis.opsForHash().get("Stock:" + DATE + ":" + CODE + ":2", "1"));
    }
}
