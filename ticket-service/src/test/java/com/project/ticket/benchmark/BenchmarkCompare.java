package com.project.ticket.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.mapper.TrainCarriageMapper;
import com.project.ticket.mapper.TrainStopoverMapper;
import com.project.ticket.pojo.dto.TicketBuyDTO;
import com.project.ticket.service.TicketBuyService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    private static TicketBuyDTO dto;
    private static List<String> codes;

    @BeforeAll
    static void setUp(@Autowired StringRedisTemplate r, @Autowired TrainStopoverMapper sm,
                      @Autowired TrainCarriageMapper cm, @Autowired ObjectMapper m) {
        BenchmarkSetup.setup(r, sm, cm, m);
        codes = BenchmarkSetup.routeTrainCodes;
        String code = codes.get(0);
        String raw = r.opsForValue().get("TrainStop:" + BenchmarkSetup.DATE + ":" + code);
        try {
            var bo = m.readValue(raw, com.project.ticket.pojo.bo.TicketListBO.class);
            dto = TicketBuyDTO.builder()
                    .date(BenchmarkSetup.DATE).code(code)
                    .startStation(bo.getStartStation()).endStation(bo.getEndStation())
                    .seatType(2)
                    .passengerList(List.of(TicketBuyDTO.Passenger.builder()
                            .realName("BenchUser").idCard("110101199001011234").build()))
                    .build();
            System.out.printf("Bench: %d trains, route %s→%s%n",
                    codes.size(), bo.getStartStation(), bo.getEndStation());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void compareAll() throws Exception {
        compareOne("TxMsg (default)", 60);
    }

    private void compareOne(String label, int durationSec) throws Exception {
        System.out.printf("%n=== %s ===%n", label);
        resetData();

        // Warmup
        for (int i = 0; i < Math.min(50, codes.size()); i++) {
            TicketBuyDTO w = buildDto(codes.get(i));
            for (int j = 0; j < 3; j++) ticketBuyService.buy(w);
        }
        var test = ticketBuyService.buy(buildDto(codes.get(0)));
        System.out.printf("Warmup done, buy test: code=%d%n", test.getCode());

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long deadline = System.nanoTime() + durationSec * 1_000_000_000L;
        ConcurrentLinkedQueue<Long> lats = new ConcurrentLinkedQueue<>();
        AtomicInteger total = new AtomicInteger(), success = new AtomicInteger();

        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            int ti = t;
            pool.submit(() -> {
                while (System.nanoTime() < deadline) {
                    TicketBuyDTO req = buildDto(codes.get(ThreadLocalRandom.current().nextInt(codes.size())));
                    long t0 = System.nanoTime();
                    try {
                        var r = ticketBuyService.buy(req);
                        long lat = System.nanoTime() - t0;
                        total.incrementAndGet();
                        if (r.getCode() == 1) { success.incrementAndGet(); lats.add(lat); }
                    } catch (Exception ignored) { total.incrementAndGet(); }
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

        System.out.printf("%s | 60s | %d | %d | %.0f | %.2f | %.2f | %.2f%n",
                label, total.get(), success.get(), qps, avg, p50, p99);
    }

    private void resetData() {
        // Quick reset of bitmap/stock/token for all route trains
        for (String code : codes) {
            int bits = BenchmarkSetup.SECOND_CLASS_SEATS * BenchmarkSetup.SECTION_COUNT;
            byte[] zeros = new byte[(bits + 7) / 8];
            redis.opsForValue().set(BenchmarkSetup.DATE + ":" + code + ":2:bitmap",
                    new String(zeros, java.nio.charset.StandardCharsets.ISO_8859_1));
            Map<String, String> stock = new HashMap<>();
            for (int s = 1; s <= BenchmarkSetup.SECTION_COUNT; s++) stock.put(String.valueOf(s), String.valueOf(BenchmarkSetup.SECOND_CLASS_SEATS));
            String sk = "Stock:" + BenchmarkSetup.DATE + ":" + code + ":2";
            redis.delete(sk);
            redis.opsForHash().putAll(sk, stock);
            redis.opsForValue().set("Token:" + BenchmarkSetup.DATE + ":" + code + ":2",
                    String.valueOf(BenchmarkSetup.SECOND_CLASS_SEATS * BenchmarkSetup.SECTION_COUNT));
        }
        System.out.println("Data reset");
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
                            .realName("B").idCard("1").build()))
                    .build();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
