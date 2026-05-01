package com.project.ticket.benchmark;

import com.project.ticket.pojo.dto.TicketBuyDTO;
import com.project.ticket.service.TicketBuyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class BuyBenchmark {

    @Autowired
    private TicketBuyService ticketBuyService;

    @Test
    void buyQpsRt() throws Exception {
        int threads = 8;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(iterations);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger success = new AtomicInteger(0);

        TicketBuyDTO dto = TicketBuyDTO.builder()
                .date(LocalDate.of(2026, 5, 1))
                .code("G1")
                .startStation("Beijing")
                .endStation("Shanghai")
                .seatType(2)
                .passengerList(List.of(TicketBuyDTO.Passenger.builder()
                        .realName("BenchUser").idCard("110101199001011234").build()))
                .build();

        // Warmup: 10 iterations
        for (int i = 0; i < 10; i++) ticketBuyService.buy(dto);

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            pool.submit(() -> {
                try {
                    long t0 = System.nanoTime();
                    var r = ticketBuyService.buy(dto);
                    long t1 = System.nanoTime();
                    if (r.getCode() == 1) success.incrementAndGet();
                    latencies.add(t1 - t0);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        long elapsed = System.nanoTime() - start;
        double qps = iterations * 1e9 / elapsed;
        Long[] latsArr = latencies.toArray(new Long[0]);
        Arrays.sort(latsArr);

        double avgMs = latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p50Ms = latsArr[latsArr.length / 2] / 1e6;
        double p99Ms = latsArr[(int)(latsArr.length * 0.99)] / 1e6;
        double maxMs = latsArr[latsArr.length - 1] / 1e6;

        System.out.printf("=== Buy Benchmark ===%n");
        System.out.printf("Threads: %d, Iterations: %d%n", threads, iterations);
        System.out.printf("Success: %d/%d%n", success.get(), iterations);
        System.out.printf("QPS:    %.1f req/s%n", qps);
        System.out.printf("avg RT: %.2f ms%n", avgMs);
        System.out.printf("p50 RT: %.2f ms%n", p50Ms);
        System.out.printf("p99 RT: %.2f ms%n", p99Ms);
        System.out.printf("max RT: %.2f ms%n", maxMs);
    }
}
