package com.project.ticket.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import com.project.ticket.pojo.dto.TicketBuyDTO;
import com.project.ticket.service.TicketBuyService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

@SpringBootTest
public class BuyBenchmark {

    @Autowired private TicketBuyService ticketBuyService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper mapper;

    private static final LocalDate DATE = LocalDate.of(2026, 5, 1);
    private static final String CODE = "G1";
    private static final int SEAT_TYPE = 2; // second class
    private static final int CARS = 6;
    private static final int SEATS_PER_CAR = 90;
    private static final int TOTAL_SECTIONS = 19;
    private static final int TOTAL_SEATS = CARS * SEATS_PER_CAR; // 540

    @BeforeAll
    static void resetData(@Autowired StringRedisTemplate redis, @Autowired ObjectMapper mapper) throws Exception {
        // 1. Reset route key
        redis.delete("2026-05-01:Beijing:Shanghai");
        redis.opsForList().leftPush("2026-05-01:Beijing:Shanghai", CODE);

        // 2. Ensure TrainStop data exists (cached from E2EPreloadTest)
        if (redis.opsForValue().get("TrainStop:2026-05-01:G1") == null) {
            // Quick inline preload
            String[] stations = {"Beijing","Tianjin","Jinan","Xuzhou","Bengbu","Nanjing","Zhenjiang",
                    "Changzhou","Wuxi","Suzhou","Shanghai","Kunshan","Hangzhou","Ningbo",
                    "Wenzhou","Fuzhou","Xiamen","Shenzhen","Guangzhou","Changsha"};
            List<TicketListBO.StopoverStation> stList = new ArrayList<>();
            for (int i = 0; i < stations.length; i++) {
                stList.add(TicketListBO.StopoverStation.builder()
                        .stopoverStation(stations[i]).stationIndex(i+1).mileage(i*100).build());
            }
            TicketListBO bo = TicketListBO.builder().date(DATE).code(CODE)
                    .startStation(stations[0]).endStation(stations[stations.length-1])
                    .stopoverStations(stList)
                    .secondClassCarriageInfo(TicketListBO.CarriageInfo.builder()
                            .carriageType("二等座车厢")
                            .carriageIndexes(IntStream.rangeClosed(3,8).boxed().toList()).build())
                    .build();
            redis.opsForValue().set("TrainStop:2026-05-01:G1", mapper.writeValueAsString(bo));
        }

        // 3. Reset bitmap (all zeros)
        int totalBits = TOTAL_SEATS * TOTAL_SECTIONS;
        byte[] zeros = new byte[(totalBits + 7) / 8];
        redis.opsForValue().set(DATE + ":" + CODE + ":" + SEAT_TYPE + ":bitmap",
                new String(zeros, java.nio.charset.StandardCharsets.ISO_8859_1));

        // 4. Reset stock (full capacity per section)
        Map<String, String> stockMap = new HashMap<>();
        for (int s = 1; s <= TOTAL_SECTIONS; s++) stockMap.put(String.valueOf(s), String.valueOf(TOTAL_SEATS));
        redis.delete("Stock:" + DATE + ":" + CODE + ":" + SEAT_TYPE);
        redis.opsForHash().putAll("Stock:" + DATE + ":" + CODE + ":" + SEAT_TYPE, stockMap);

        // 5. Reset token
        redis.opsForValue().set("Token:" + DATE + ":" + CODE + ":" + SEAT_TYPE,
                String.valueOf(TOTAL_SEATS * TOTAL_SECTIONS));

        System.out.println("[Setup] Data reset: " + TOTAL_SEATS + " seats ready");
    }

    @Test
    void concurrent() throws Exception {
        int threads = 16;
        int totalRequests = 1000;
        TicketBuyDTO dto = TicketBuyDTO.builder()
                .date(DATE).code(CODE).startStation("Beijing").endStation("Shanghai")
                .seatType(SEAT_TYPE)
                .passengerList(List.of(TicketBuyDTO.Passenger.builder()
                        .realName("BenchUser").idCard("110101199001011234").build()))
                .build();

        // Warmup + verify
        var test = ticketBuyService.buy(dto);
        System.out.printf("[Setup] Buy test: code=%d, msg=%s%n", test.getCode(), test.getMsg());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger success = new AtomicInteger(0);

        long start = System.nanoTime();
        for (int i = 0; i < totalRequests; i++) {
            pool.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    var r = ticketBuyService.buy(dto);
                    if (r.getCode() == 1) success.incrementAndGet();
                } catch (Exception ignored) {}
                latencies.add(System.nanoTime() - t0);
                latch.countDown();
            });
        }
        latch.await(120, TimeUnit.SECONDS);
        pool.shutdown();
        long elapsed = System.nanoTime() - start;

        Long[] latsArr = latencies.toArray(new Long[0]);
        Arrays.sort(latsArr);
        double qps = totalRequests * 1e9 / elapsed;
        double avgMs = latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p50Ms = latsArr[latsArr.length / 2] / 1e6;
        double p99Ms = latsArr[(int)(latsArr.length * 0.99)] / 1e6;

        System.out.printf("=== Buy Concurrent (%d threads) ===%n", threads);
        System.out.printf("Total: %d, Success: %d/%d%n", totalRequests, success.get(), TOTAL_SEATS);
        System.out.printf("QPS:    %.0f req/s%n", qps);
        System.out.printf("avg RT: %.2f ms%n", avgMs);
        System.out.printf("p50 RT: %.2f ms%n", p50Ms);
        System.out.printf("p99 RT: %.2f ms%n", p99Ms);
    }
}
