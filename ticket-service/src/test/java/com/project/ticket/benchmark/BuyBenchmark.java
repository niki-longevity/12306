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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class BuyBenchmark {

    @Autowired private TicketBuyService ticketBuyService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TrainStopoverMapper stopoverMapper;
    @Autowired private TrainCarriageMapper carriageMapper;
    @Autowired private ObjectMapper mapper;

    private static TicketBuyDTO dto;

    @BeforeAll
    static void setUp(@Autowired StringRedisTemplate r, @Autowired TrainStopoverMapper sm,
                      @Autowired TrainCarriageMapper cm, @Autowired ObjectMapper m) {
        BenchmarkSetup.setup(r, sm, cm, m);
        System.out.printf("[Setup] %d route-matching trains, route %s→%s, %d seats each%n",
                BenchmarkSetup.routeTrainCodes.size(), BenchmarkSetup.routeStart,
                BenchmarkSetup.routeEnd, BenchmarkSetup.SECOND_CLASS_SEATS);
    }

    @Test
    void concurrent() throws Exception {
        int threads = 16, durationSec = 60;
        List<String> codes = BenchmarkSetup.routeTrainCodes;
        int trainCount = codes.size();

        // Warmup with the first few trains
        for (int i = 0; i < Math.min(10, trainCount); i++) {
            String code = codes.get(i);
            TicketBuyDTO warmup = buildDto(code);
            for (int j = 0; j < 5; j++) ticketBuyService.buy(warmup);
        }
        var test = ticketBuyService.buy(buildDto(codes.get(0)));
        System.out.printf("[Setup] Buy test: code=%d msg=%s, running %ds, %d threads, %d trains%n",
                test.getCode(), test.getMsg(), durationSec, threads, trainCount);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long deadline = System.nanoTime() + durationSec * 1_000_000_000L;
        ConcurrentLinkedQueue<Long> validLats = new ConcurrentLinkedQueue<>();
        AtomicInteger totalCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();
        AtomicInteger trainIdx = new AtomicInteger();

        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                while (System.nanoTime() < deadline) {
                    // Cycle through trains to spread purchases
                    String code = codes.get(trainIdx.getAndIncrement() % trainCount);
                    TicketBuyDTO req = buildDto(code);

                    long t0 = System.nanoTime();
                    try {
                        var r = ticketBuyService.buy(req);
                        long lat = System.nanoTime() - t0;
                        totalCount.incrementAndGet();
                        if (r.getCode() == 1) {
                            successCount.incrementAndGet();
                            validLats.add(lat); // Only successful buys go into latency stats
                        }
                    } catch (Exception e) {
                        totalCount.incrementAndGet();
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(durationSec + 10, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        Long[] latsArr = validLats.toArray(new Long[0]);
        Arrays.sort(latsArr);
        double qps = totalCount.get() * 1e9 / elapsed;
        double avg = validLats.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p50 = latsArr.length > 0 ? latsArr[latsArr.length / 2] / 1e6 : 0;
        double p99 = latsArr.length > 0 ? latsArr[(int)(latsArr.length * 0.99)] / 1e6 : 0;

        System.out.printf("=== Buy %dT %ds, %d trains ===%n", threads, durationSec, trainCount);
        System.out.printf("Total: %d, Success: %d, SoldOut: %d (excluded from latency)%n",
                totalCount.get(), successCount.get(), soldOutCount.get());
        System.out.printf("QPS: %.0f, avg: %.2f ms, p50: %.2f ms, p99: %.2f ms%n", qps, avg, p50, p99);
    }

    private TicketBuyDTO buildDto(String code) {
        String raw = redis.opsForValue().get("TrainStop:" + BenchmarkSetup.DATE + ":" + code);
        try {
            var bo = mapper.readValue(raw, com.project.ticket.pojo.bo.TicketListBO.class);
            return TicketBuyDTO.builder()
                    .date(BenchmarkSetup.DATE).code(code)
                    .startStation(bo.getStartStation()).endStation(bo.getEndStation())
                    .seatType(2)
                    .passengerList(List.of(TicketBuyDTO.Passenger.builder()
                            .realName("BenchUser").idCard("110101199001011234").build()))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
