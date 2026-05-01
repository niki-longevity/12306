package com.project.ticket.benchmark;

import com.project.ticket.pojo.dto.TicketListDTO;
import com.project.ticket.pojo.vo.TicketListVO;
import com.project.ticket.service.TicketGetService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest
public class QueryBenchmark {

    @Autowired private TicketGetService ticketGetService;
    @Autowired private StringRedisTemplate redis;

    @BeforeAll
    static void ensureRouteData(@Autowired StringRedisTemplate redis) {
        // Ensure G1 is registered for Beijing→Shanghai
        String routeKey = "2026-05-01:Beijing:Shanghai";
        if (redis.opsForList().size(routeKey) == 0) {
            redis.opsForList().leftPush(routeKey, "G1");
            System.out.println("[Setup] Route key populated: " + routeKey);
        }
    }

    @Test
    void singleThread() {
        int warmup = 1000, iterations = 10000;
        TicketListDTO dto = new TicketListDTO();
        dto.setDate(LocalDate.of(2026, 5, 1));
        dto.setStart("Beijing");
        dto.setEnd("Shanghai");

        // Warmup + verify data is returned
        for (int i = 0; i < warmup; i++) ticketGetService.list(dto);
        List<TicketListVO> check = ticketGetService.list(dto);
        System.out.printf("[Setup] Query returns %d trains, ready%n", check.size());
        if (check.isEmpty()) {
            System.out.println("[WARN] No train data found! Run E2EPreloadTest first.");
            return;
        }

        long[] lats = new long[iterations];
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            ticketGetService.list(dto);
            lats[i] = System.nanoTime() - t0;
        }
        long elapsed = System.nanoTime() - start;

        Arrays.sort(lats);
        double qps = iterations * 1e9 / elapsed;
        double avgMs = Arrays.stream(lats).average().orElse(0) / 1e6;
        double p50Ms = lats[iterations / 2] / 1e6;
        double p99Ms = lats[(int)(iterations * 0.99)] / 1e6;

        System.out.printf("=== Query Single-Thread ===%n");
        System.out.printf("Iterations: %d%n", iterations);
        System.out.printf("QPS:    %.0f req/s%n", qps);
        System.out.printf("avg RT: %.2f ms%n", avgMs);
        System.out.printf("p50 RT: %.2f ms%n", p50Ms);
        System.out.printf("p99 RT: %.2f ms%n", p99Ms);
    }

    @Test
    void concurrent() throws Exception {
        int threads = 16;
        int totalRequests = 100000;
        TicketListDTO dto = new TicketListDTO();
        dto.setDate(LocalDate.of(2026, 5, 1));
        dto.setStart("Beijing");
        dto.setEnd("Shanghai");

        // Warmup
        for (int i = 0; i < 1000; i++) ticketGetService.list(dto);
        if (ticketGetService.list(dto).isEmpty()) {
            System.out.println("[WARN] No train data! Run E2EPreloadTest first.");
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        long start = System.nanoTime();
        for (int i = 0; i < totalRequests; i++) {
            pool.submit(() -> {
                long t0 = System.nanoTime();
                ticketGetService.list(dto);
                latencies.add(System.nanoTime() - t0);
                latch.countDown();
            });
        }
        latch.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        long elapsed = System.nanoTime() - start;

        Long[] latsArr = latencies.toArray(new Long[0]);
        Arrays.sort(latsArr);
        double qps = totalRequests * 1e9 / elapsed;
        double avgMs = latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p50Ms = latsArr[latsArr.length / 2] / 1e6;
        double p99Ms = latsArr[(int)(latsArr.length * 0.99)] / 1e6;

        System.out.printf("=== Query Concurrent (%d threads) ===%n", threads);
        System.out.printf("Total: %d requests%n", totalRequests);
        System.out.printf("QPS:    %.0f req/s%n", qps);
        System.out.printf("avg RT: %.2f ms%n", avgMs);
        System.out.printf("p50 RT: %.2f ms%n", p50Ms);
        System.out.printf("p99 RT: %.2f ms%n", p99Ms);
    }
}
