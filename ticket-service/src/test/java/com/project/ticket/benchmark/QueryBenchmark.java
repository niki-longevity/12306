package com.project.ticket.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.mapper.TrainCarriageMapper;
import com.project.ticket.mapper.TrainStopoverMapper;
import com.project.ticket.pojo.dto.TicketListDTO;
import com.project.ticket.pojo.vo.TicketListVO;
import com.project.ticket.service.TicketGetService;
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
public class QueryBenchmark {

    @Autowired private TicketGetService ticketGetService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TrainStopoverMapper stopoverMapper;
    @Autowired private TrainCarriageMapper carriageMapper;
    @Autowired private ObjectMapper mapper;

    private static TicketListDTO dto;
    private static int queryReturns;

    @BeforeAll
    static void setUp(@Autowired StringRedisTemplate redis, @Autowired TrainStopoverMapper sm,
                      @Autowired TrainCarriageMapper cm, @Autowired ObjectMapper m) {
        BenchmarkSetup.setup(redis, sm, cm, m);
        dto = new TicketListDTO();
        dto.setDate(BenchmarkSetup.DATE);
        dto.setStart(BenchmarkSetup.routeStart);
        dto.setEnd(BenchmarkSetup.routeEnd);
        System.out.printf("[Setup] Query route: %s→%s%n", dto.getStart(), dto.getEnd());
    }

    @Test
    void singleThread() {
        int durationSec = 60;
        long deadline = System.nanoTime() + durationSec * 1_000_000_000L;
        AtomicInteger count = new AtomicInteger();
        long[] samples = new long[200_000];

        // Warmup + verify
        for (int i = 0; i < 1000; i++) ticketGetService.list(dto);
        List<TicketListVO> check = ticketGetService.list(dto);
        queryReturns = check.size();
        System.out.printf("[Setup] Query returns %d trains. Running %ds...%n", queryReturns, durationSec);

        // Benchmark
        long start = System.nanoTime();
        int idx = 0;
        while (System.nanoTime() < deadline && idx < samples.length) {
            long t0 = System.nanoTime();
            ticketGetService.list(dto);
            samples[idx++] = System.nanoTime() - t0;
            count.incrementAndGet();
        }
        long elapsed = System.nanoTime() - start;
        long[] lats = Arrays.copyOf(samples, idx);
        Arrays.sort(lats);

        double qps = count.get() * 1e9 / elapsed;
        double avg = Arrays.stream(lats).average().orElse(0) / 1e6;
        double p50 = lats[lats.length / 2] / 1e6;
        double p99 = lats[(int)(lats.length * 0.99)] / 1e6;

        System.out.printf("=== Query ST %ds, %d trains ===%n", durationSec, queryReturns);
        System.out.printf("Requests: %d, QPS: %.0f%n", count.get(), qps);
        System.out.printf("avg: %.2f ms, p50: %.2f ms, p99: %.2f ms%n", avg, p50, p99);
    }

    @Test
    void concurrent() throws Exception {
        int threads = 16, durationSec = 60;

        // Warmup
        for (int i = 0; i < 1000; i++) ticketGetService.list(dto);
        List<TicketListVO> check = ticketGetService.list(dto);
        queryReturns = check.size();
        System.out.printf("[Setup] Query returns %d trains. Running %ds with %d threads...%n",
                queryReturns, durationSec, threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long deadline = System.nanoTime() + durationSec * 1_000_000_000L;
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger count = new AtomicInteger();

        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                while (System.nanoTime() < deadline) {
                    long t0 = System.nanoTime();
                    ticketGetService.list(dto);
                    latencies.add(System.nanoTime() - t0);
                    count.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(durationSec + 5, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        Long[] latsArr = latencies.toArray(new Long[0]);
        Arrays.sort(latsArr);
        double qps = count.get() * 1e9 / elapsed;
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p50 = latsArr[latsArr.length / 2] / 1e6;
        double p99 = latsArr[(int)(latsArr.length * 0.99)] / 1e6;

        System.out.printf("=== Query %dT %ds, %d trains ===%n", threads, durationSec, queryReturns);
        System.out.printf("Requests: %d, QPS: %.0f%n", count.get(), qps);
        System.out.printf("avg: %.2f ms, p50: %.2f ms, p99: %.2f ms%n", avg, p50, p99);
    }
}
