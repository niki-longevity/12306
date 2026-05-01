package com.project.ticket.benchmark;

import com.project.ticket.pojo.dto.TicketListDTO;
import com.project.ticket.pojo.vo.TicketListVO;
import com.project.ticket.service.TicketGetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@SpringBootTest
public class QueryBenchmark {

    @Autowired
    private TicketGetService ticketGetService;

    @Test
    void queryQpsRt() {
        int warmup = 50, iterations = 500;
        TicketListDTO query = new TicketListDTO();
        query.setDate(LocalDate.of(2026, 5, 1));
        query.setStart("Beijing");
        query.setEnd("Shanghai");

        // Warmup
        for (int i = 0; i < warmup; i++) ticketGetService.list(query);

        // Benchmark
        long[] lats = new long[iterations];
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            List<TicketListVO> result = ticketGetService.list(query);
            lats[i] = System.nanoTime() - t0;
        }
        long elapsed = System.nanoTime() - start;

        Arrays.sort(lats);
        double qps = iterations * 1e9 / elapsed;
        double avgMs = Arrays.stream(lats).average().orElse(0) / 1e6;
        double p50Ms = lats[iterations / 2] / 1e6;
        double p99Ms = lats[(int)(iterations * 0.99)] / 1e6;
        double maxMs = lats[iterations - 1] / 1e6;

        System.out.printf("=== Query Benchmark ===%n");
        System.out.printf("Iterations: %d%n", iterations);
        System.out.printf("QPS:    %.1f req/s%n", qps);
        System.out.printf("avg RT: %.2f ms%n", avgMs);
        System.out.printf("p50 RT: %.2f ms%n", p50Ms);
        System.out.printf("p99 RT: %.2f ms%n", p99Ms);
        System.out.printf("max RT: %.2f ms%n", maxMs);
    }
}
