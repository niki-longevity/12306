package com.project.ticket.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.mapper.TrainCarriageMapper;
import com.project.ticket.mapper.TrainStopoverMapper;
import com.project.ticket.pojo.dto.TicketBuyDTO;
import com.project.ticket.service.TicketBuyService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class BenchmarkCompare {

    @Autowired private TicketBuyService ticketBuyService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TrainStopoverMapper stopoverMapper;
    @Autowired private TrainCarriageMapper carriageMapper;
    @Autowired private ObjectMapper mapper;

    private static List<TicketBuyDTO> dtos;
    private static List<String> codes;

    @BeforeAll
    static void setUp(@Autowired StringRedisTemplate r, @Autowired TrainStopoverMapper sm,
                      @Autowired TrainCarriageMapper cm, @Autowired ObjectMapper m) {
        BenchmarkSetup.setup(r, sm, cm, m);
        // Only first 1000 trains
        codes = BenchmarkSetup.routeTrainCodes.subList(0, Math.min(1000, BenchmarkSetup.routeTrainCodes.size()));

        // Pre-build all DTOs — no Redis/JSON in hot path
        dtos = new ArrayList<>();
        for (String code : codes) {
            String raw = r.opsForValue().get("TrainStop:" + BenchmarkSetup.DATE + ":" + code);
            try {
                var bo = m.readValue(raw, com.project.ticket.pojo.bo.TicketListBO.class);
                dtos.add(TicketBuyDTO.builder()
                        .date(BenchmarkSetup.DATE).code(code)
                        .startStation(bo.getStartStation()).endStation(bo.getEndStation())
                        .seatType(2)
                        .passengerList(List.of(TicketBuyDTO.Passenger.builder()
                                .realName("B").idCard("1").build()))
                        .build());
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        System.out.printf("Bench: %d trains, %d dtos pre-built%n", codes.size(), dtos.size());
    }

    @Test
    void compareAll() throws Exception {
        String activeProfile = System.getProperty("spring.profiles.active", "default");
        String label = switch (activeProfile) {
            case "bench-outbox" -> "Outbox";
            case "bench-http" -> "HTTP";
            default -> "PlainMQ";
        };
        compareOne(label, 60);
    }

    private void compareOne(String label, int durationSec) throws Exception {
        System.out.printf("%n=== %s ===%n", label);

        resetData();

        // Warmup ALL trains to Caffeine before timing
        System.out.printf("Warming up %d trains...%n", dtos.size());
        for (int i = 0; i < dtos.size(); i++) {
            ticketBuyService.buy(dtos.get(i));
        }
        var test = ticketBuyService.buy(dtos.get(0));
        System.out.printf("Warmup DONE, test: code=%d. Starting %ds timer NOW.%n", test.getCode(), durationSec);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long deadline = System.nanoTime() + durationSec * 1_000_000_000L;
        ConcurrentLinkedQueue<Long> lats = new ConcurrentLinkedQueue<>();
        AtomicInteger total = new AtomicInteger(), success = new AtomicInteger();
        AtomicInteger idx = new AtomicInteger();

        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                while (System.nanoTime() < deadline) {
                    TicketBuyDTO req = dtos.get(idx.getAndIncrement() % dtos.size());
                    long t0 = System.nanoTime();
                    try {
                        var r = ticketBuyService.buy(req);
                        long lat = System.nanoTime() - t0;
                        total.incrementAndGet();
                        if (r.getCode() == 1) { success.incrementAndGet(); lats.add(lat); }
                    } catch (Exception e) { total.incrementAndGet(); }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(durationSec + 10, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        Long[] arr = lats.toArray(new Long[0]);
        Arrays.sort(arr);
        double qps = total.get() * 1e9 / elapsed;
        double avg = lats.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p50 = arr.length > 0 ? arr[arr.length / 2] / 1e6 : 0;
        double p99 = arr.length > 0 ? arr[(int)(arr.length * 0.99)] / 1e6 : 0;

        System.out.printf("Result: %s | QPS=%.0f | avg=%.2fms | p50=%.2fms | p99=%.2fms | total=%d success=%d%n",
                label, qps, avg, p50, p99, total.get(), success.get());
    }

    private void resetData() {
        for (String code : codes) {
            int bits = BenchmarkSetup.SECOND_CLASS_SEATS * BenchmarkSetup.SECTION_COUNT;
            byte[] zeros = new byte[(bits + 7) / 8];
            redis.opsForValue().set(BenchmarkSetup.DATE + ":" + code + ":2:bitmap",
                    new String(zeros, StandardCharsets.ISO_8859_1));
            Map<String, String> stock = new HashMap<>();
            for (int s = 1; s <= BenchmarkSetup.SECTION_COUNT; s++)
                stock.put(String.valueOf(s), String.valueOf(BenchmarkSetup.SECOND_CLASS_SEATS));
            String sk = "Stock:" + BenchmarkSetup.DATE + ":" + code + ":2";
            redis.delete(sk);
            redis.opsForHash().putAll(sk, stock);
            redis.opsForValue().set("Token:" + BenchmarkSetup.DATE + ":" + code + ":2",
                    String.valueOf(BenchmarkSetup.SECOND_CLASS_SEATS * BenchmarkSetup.SECTION_COUNT));
        }
        System.out.println("Data reset done");
    }
}
