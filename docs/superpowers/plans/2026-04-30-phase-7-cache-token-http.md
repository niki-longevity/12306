# Phase 7: Cache Refactor, Token Preload, Preload Scripts, HTTP Call

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace eager full-table warmup with Caffeine CacheLoader (Redis fallback), read actual DB carriage data, preload Redis token/bitmap/stock via test scripts, and wire ticket→order HTTP call.

**Architecture:** Caffeine load from Redis on miss → Redis preloaded by test scripts → Caffeine refreshAfterWrite for lazy stock refresh. Token = actual seatCount × sectionCount per train.

**Tech Stack:** Spring Boot 3.5.10, Caffeine, Redis, RestTemplate

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `ticket-service/.../cache/warmup/TrainStopCacheWarmup.java` | Delete | Replaced by CacheLoader |
| `ticket-service/.../cache/warmup/TrainStopCacheLoader.java` | Create | CacheLoader: load from Redis |
| `ticket-service/.../config/CacheConfig.java` | Modify | Add CacheLoader, fix refresh strategy |
| `ticket-service/.../pojo/entity/TrainCarriage.java` | Read only | Understand DB fields for carriage config |
| `ticket-service/.../cache/warmup/TrainStopCacheWarmup.java` fix | — | Already deleted |
| `ticket-service/src/test/.../preload/RedisDataPreloader.java` | Create | Test: load static train data to Redis |
| `ticket-service/src/test/.../preload/TokenPreloader.java` | Create | Test: calculate & set Redis tokens |
| `ticket-service/.../service/impl/TicketBuyServiceImpl.java` | Modify | Read token from pre-set Redis key, not lazy-compute |
| `ticket-service/.../config/RestTemplateConfig.java` | Create | RestTemplate bean for HTTP calls |
| `ticket-service/.../service/impl/TicketBuyServiceImpl.java` | Modify | Call order-service HTTP instead of direct DB write |
| `order-service/.../controller/OrderController.java` | Check | POST /order/create endpoint exists |

---

### Task 1: Fix carriage info — read actual DB fields

**Files:**
- Modify: `ticket-service/src/main/java/com/project/ticket/cache/warmup/TrainStopCacheWarmup.java`

- [ ] **Step 1: Read TrainCarriage entity to understand DB fields**

Read `ticket-service/src/main/java/com/project/ticket/pojo/entity/TrainCarriage.java`. The fields likely include `businessCarriage`, `firstClassCarriage`, `secondClassCarriage` (counts per type) and `trainCode`.

- [ ] **Step 2: Replace hardcoded carriage numbers with DB values**

Replace lines 161-167 of TrainStopCacheWarmup.java — the hardcoded `buildCarriageInfo("商务座车厢", 1, 1)` etc. — with actual DB values:

```java
if (carriage != null) {
    int businessCount = Optional.ofNullable(carriage.getBusinessCarriage()).orElse(0);
    int firstCount = Optional.ofNullable(carriage.getFirstClassCarriage()).orElse(0);
    int secondCount = Optional.ofNullable(carriage.getSecondClassCarriage()).orElse(0);

    if (businessCount > 0) {
        businessCarriageInfo = buildCarriageInfo("商务座车厢", 1, businessCount);
    }
    if (firstCount > 0) {
        firstClassCarriageInfo = buildCarriageInfo("一等座车厢", businessCount + 1, businessCount + firstCount);
    }
    if (secondCount > 0) {
        secondClassCarriageInfo = buildCarriageInfo("二等座车厢", businessCount + firstCount + 1,
                businessCount + firstCount + secondCount);
    }
}
```

- [ ] **Step 3: Compile and commit**

```bash
mvn compile -pl ticket-service -am -q
git add -A && git commit -m "fix: read actual carriage counts from DB instead of hardcoding"
```

---

### Task 2: Delete TrainStopCacheWarmup, add CacheLoader + Redis preloader

**Files:**
- Delete: `ticket-service/src/main/java/com/project/ticket/cache/warmup/TrainStopCacheWarmup.java`
- Create: `ticket-service/src/main/java/com/project/ticket/cache/warmup/TrainStopCacheLoader.java`
- Modify: `ticket-service/src/main/java/com/project/ticket/config/CacheConfig.java`
- Create: `ticket-service/src/test/java/com/project/ticket/preload/TrainDataPreloader.java`

- [ ] **Step 1: Delete TrainStopCacheWarmup**

```bash
rm ticket-service/src/main/java/com/project/ticket/cache/warmup/TrainStopCacheWarmup.java
```

- [ ] **Step 2: Update CacheConfig — add CacheLoader for trainStopCache**

Modify `trainStopCacheManager()` bean in CacheConfig.java:

```java
@Bean("trainStopCacheManager")
public CacheManager trainStopCacheManager(TrainStopCacheLoader cacheLoader) {
    CaffeineCacheManager cacheManager = new CaffeineCacheManager("trainStopCache");
    cacheManager.setCacheLoader(cacheLoader);
    Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
            .recordStats()
            .maximumSize(10000)
            .refreshAfterWrite(10, TimeUnit.MINUTES)
            .expireAfterAccess(1, TimeUnit.DAYS);
    cacheManager.setCaffeine(caffeine);
    return cacheManager;
}
```

Key changes: `CacheLoader` from new class (not inline lambda), `refreshAfterWrite=10min` (惰性刷新), `expireAfterAccess=1day` (空闲自动清退), remove standalone `ticketCacheManager()` since TicketStockCalculator handles its own cache.

- [ ] **Step 3: Create TrainStopCacheLoader**

```java
package com.project.ticket.cache.warmup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.pojo.bo.TicketListBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrainStopCacheLoader implements com.github.benmanes.caffeine.cache.CacheLoader<String, TicketListBO> {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String REDIS_KEY_PREFIX = "TrainStop:";

    @Override
    public TicketListBO load(String cacheKey) throws Exception {
        // cacheKey format: "2026-05-01:G1"
        String redisKey = REDIS_KEY_PREFIX + cacheKey;
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        if (json == null) {
            log.warn("Redis miss for key={}, returning null", redisKey);
            return null;
        }
        log.debug("Caffeine miss, loaded from Redis: {}", cacheKey);
        return objectMapper.readValue(json, TicketListBO.class);
    }

    @Override
    public TicketListBO load(String key, TicketListBO oldValue) {
        // refresh: reload from Redis
        try {
            return load(key);
        } catch (Exception e) {
            log.error("Refresh failed for key={}, keeping old value", key, e);
            return oldValue;
        }
    }
}
```

- [ ] **Step 4: Create Redis preloader test class**

```java
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
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

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
        log.info("开始预加载{}车次数据到Redis...", date);

        List<TrainStopover> stopovers = trainStopoverMapper.selectList(
                new LambdaQueryWrapper<TrainStopover>()
                        .eq(TrainStopover::getDate, date)
        );
        if (stopovers.isEmpty()) {
            log.warn("{}无车次数据", date);
            return;
        }

        Map<String, List<TrainStopover>> grouped = stopovers.stream()
                .collect(Collectors.groupingBy(TrainStopover::getCode));

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
                // TTL = 发车后1天
                long ttlSeconds = Duration.between(LocalDate.now().atStartOfDay(),
                        date.atStartOfDay().plusDays(2)).getSeconds();
                stringRedisTemplate.opsForValue().set(redisKey, json, Duration.ofSeconds(ttlSeconds));
            } catch (Exception e) {
                log.error("预加载车次{}失败", cacheKey, e);
            }
        }
        log.info("{} 预加载完成", date);
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
```

- [ ] **Step 5: Compile and commit**

```bash
mvn compile -pl ticket-service -am -q
git add -A && git commit -m "refactor: replace eager warmup with Redis-backed Caffeine CacheLoader + preloader"
```

---

### Task 3: Token preloader — set Redis token based on actual seat×section count

**Files:**
- Create: `ticket-service/src/test/java/com/project/ticket/preload/TokenPreloader.java`
- Modify: `ticket-service/.../service/impl/TicketBuyServiceImpl.java` — use pre-set token key

- [ ] **Step 1: Create TokenPreloader**

```java
package com.project.ticket.preload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ticket.cache.warmup.TrainStopCacheLoader;
import com.project.ticket.pojo.bo.TicketListBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * TEST-ONLY: Preload token bucket values into Redis.
 * Token = (seatCount per carriage × carriageCount) × sectionCount
 * Run after TrainDataPreloader.
 */
@Slf4j
@Component("tokenPreloader")
@RequiredArgsConstructor
public class TokenPreloader {

    private final StringRedisTemplate stringRedisTemplate;
    private final TrainStopCacheLoader cacheLoader;
    private final ObjectMapper objectMapper;

    public void preload(LocalDate date, String trainCode) {
        try {
            String cacheKey = date + ":" + trainCode;
            TicketListBO bo = cacheLoader.load(cacheKey);
            if (bo == null) {
                log.warn("车次{}无数据，跳过令牌预热", cacheKey);
                return;
            }
            int sectionCount = bo.getStopoverStations().size() - 1;
            if (sectionCount <= 0) return;

            int businessSeats = countSeats(bo.getBusinessCarriageInfo());
            int firstSeats = countSeats(bo.getFirstClassCarriageInfo());
            int secondSeats = countSeats(bo.getSecondClassCarriageInfo());

            // Token = 座位数 × 区间数
            setToken(date, trainCode, 0, businessSeats * sectionCount);
            setToken(date, trainCode, 1, firstSeats * sectionCount);
            setToken(date, trainCode, 2, secondSeats * sectionCount);

            log.info("令牌预热完成：{} business={} first={} second={}",
                    cacheKey, businessSeats * sectionCount, firstSeats * sectionCount, secondSeats * sectionCount);
        } catch (Exception e) {
            log.error("令牌预热失败：{}/{}", date, trainCode, e);
        }
    }

    private void setToken(LocalDate date, String trainCode, int seatType, int tokenCount) {
        String tokenKey = String.format("Token:%s:%s:%d", date, trainCode, seatType);
        stringRedisTemplate.opsForValue().set(tokenKey, String.valueOf(tokenCount));
    }

    private int countSeats(TicketListBO.CarriageInfo info) {
        if (info == null || info.getCarriageIndexes() == null) return 0;
        // Cannot derive seat-per-carriage from carriage indexes alone —
        // use SeatType enum: 5/28/90 per car for business/first/second
        return 0; // caller knows the type
    }

    private int countSeats(TicketListBO.CarriageInfo info, int seatsPerCarriage) {
        if (info == null || info.getCarriageIndexes() == null) return 0;
        return info.getCarriageIndexes().size() * seatsPerCarriage;
    }
}
```

- [ ] **Step 2: Update TokenPreloader.setToken to use actual SeatType values**

Fix the `setToken` calls to use `countSeats(info, type.getSeatsPerCarriage())`:

```java
setToken(date, trainCode, 0, countSeats(bo.getBusinessCarriageInfo(), SeatType.BUSINESS.getSeatsPerCarriage()) * sectionCount);
setToken(date, trainCode, 1, countSeats(bo.getFirstClassCarriageInfo(), SeatType.FIRST.getSeatsPerCarriage()) * sectionCount);
setToken(date, trainCode, 2, countSeats(bo.getSecondClassCarriageInfo(), SeatType.SECOND.getSeatsPerCarriage()) * sectionCount);
```

- [ ] **Step 3: Compile and commit**

```bash
mvn compile -pl ticket-service -am -q
git add -A && git commit -m "feat: token preloader based on actual seat×section count"
```

---

### Task 4: Update TicketBuyServiceImpl token key to include seatType

**Files:**
- Modify: `ticket-service/.../service/impl/TicketBuyServiceImpl.java`

- [ ] **Step 1: Change TOKEN_KEY_PREFIX to include seatType**

```java
// Before:
private static final String TOKEN_KEY_PREFIX = "Token:%s:%s";
// After:
private static final String TOKEN_KEY_PREFIX = "Token:%s:%s:%d";
```

- [ ] **Step 2: Update token key construction in buy()**

Change line ~163 from:
```java
String tokenKey = String.format(TOKEN_KEY_PREFIX, date, trainCode);
```
To:
```java
String tokenKey = String.format(TOKEN_KEY_PREFIX, date, trainCode, seatTypeCode);
```

- [ ] **Step 3: Simplify token Lua — just DECR without reset logic**

The preloader already sets the correct token count. The Lua script no longer needs to calculate a reset value. Update `token_bucket.lua`:

```lua
local tokenKey = KEYS[1]
local needToken = tonumber(ARGV[1])

local currentToken = tonumber(redis.call('GET', tokenKey) or 0)
if currentToken < needToken then
    return -1
end
return redis.call('DECRBY', tokenKey, needToken)
```

- [ ] **Step 4: Update Java token call — remove resetToken argument**

```java
Long tokenResult = stringRedisTemplate.execute(
        TOKEN_BUCKET_LUA_SCRIPT,
        Collections.singletonList(tokenKey),
        String.valueOf(passengerCount)  // only needToken, no resetToken
);
```

- [ ] **Step 5: Compile and commit**

```bash
mvn compile -pl ticket-service -am -q
git add -A && git commit -m "fix: token key includes seatType, remove lazy reset logic"
```

---

### Task 5: Wire HTTP call from ticket-service to order-service

**Files:**
- Create: `ticket-service/.../config/RestTemplateConfig.java`
- Modify: `ticket-service/.../service/impl/TicketBuyServiceImpl.java` — replace OrderMapper with RestTemplate
- Delete: `ticket-service/.../mapper/OrderMapper.java` and `OrderPassengerMapper.java` from ticket-service

- [ ] **Step 1: Create RestTemplateConfig**

```java
package com.project.ticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```

- [ ] **Step 2: Add RestTemplate field and remove OrderMapper/OrderPassengerMapper from TicketBuyServiceImpl**

Replace:
```java
private final OrderMapper orderMapper;
private final OrderPassengerMapper orderPassengerMapper;
```
With:
```java
private final RestTemplate restTemplate;
```

And remove the `createOrder()` method entirely.

- [ ] **Step 3: Call order-service HTTP after Lua success**

Replace the order creation block (after `if (boughtCarRelIdx >= 0)`) with:

```java
// HTTP call order-service to create order
try {
    Map<String, Object> orderRequest = new HashMap<>();
    orderRequest.put("userId", BaseContext.getCurrentId());
    orderRequest.put("date", date.toString());
    orderRequest.put("trainCode", trainCode);
    orderRequest.put("startStation", startStation);
    orderRequest.put("endStation", endStation);
    orderRequest.put("seatType", seatTypeCode);
    orderRequest.put("carriageNum", finalCarAbsIdx);
    orderRequest.put("seatNum", boughtSeatGlobalIdx);
    orderRequest.put("startSection", startSection);
    orderRequest.put("endSection", endSection);
    orderRequest.put("totalSectionCount", totalSectionCount);
    orderRequest.put("passengerCount", passengerCount);
    orderRequest.put("sectionsJson", sectionsJson);
    orderRequest.put("seatStartBit", calculateSeatStartBit(boughtCarRelIdx, boughtSeatGlobalIdx, totalSectionCount, seatTypeCode));

    List<Map<String, String>> passengers = passengerList.stream()
            .map(p -> Map.of("realName", p.getRealName(), "idCard", p.getIdCard()))
            .toList();
    orderRequest.put("passengers", passengers);

    restTemplate.postForObject("http://localhost:8083/order/create", orderRequest, String.class);
    log.info("HTTP创建订单成功：车次{}，车厢{}，座位{}", trainCode, finalCarAbsIdx, boughtSeatGlobalIdx);
} catch (Exception e) {
    log.error("HTTP创建订单失败：车次{}", trainCode, e);
}
```

- [ ] **Step 4: Add POST /order/create endpoint to order-service**

Create a DTO for order creation request in order-service that matches the JSON structure above. Or accept a Map and parse manually. The simplest approach: add to `OrderService` and `OrderController`:

```java
// OrderController.java — add:
@PostMapping("/create")
public Result<Order> create(@RequestBody Map<String, Object> request) {
    Order order = buildOrderFromMap(request);
    @SuppressWarnings("unchecked")
    List<Map<String, String>> passengerMaps = (List<Map<String, String>>) request.get("passengers");
    List<OrderPassenger> passengers = passengerMaps.stream()
            .map(m -> OrderPassenger.builder().realName(m.get("realName")).idCard(m.get("idCard")).build())
            .toList();
    return Result.success(orderService.create(order, passengers));
}

private Order buildOrderFromMap(Map<String, Object> map) {
    return Order.builder()
            .userId(Long.valueOf(map.get("userId").toString()))
            .date(LocalDate.parse(map.get("date").toString()))
            .trainCode(map.get("trainCode").toString())
            .startStation(map.get("startStation").toString())
            .endStation(map.get("endStation").toString())
            .seatType(Integer.valueOf(map.get("seatType").toString()))
            .carriageNum(Integer.valueOf(map.get("carriageNum").toString()))
            .seatNum(Integer.valueOf(map.get("seatNum").toString()))
            .startSection(Integer.valueOf(map.get("startSection").toString()))
            .endSection(Integer.valueOf(map.get("endSection").toString()))
            .totalSectionCount(Integer.valueOf(map.get("totalSectionCount").toString()))
            .passengerCount(Integer.valueOf(map.get("passengerCount").toString()))
            .sectionsJson(map.get("sectionsJson").toString())
            .seatStartBit(Long.valueOf(map.get("seatStartBit").toString()))
            .build();
}
```

Note: Since order-service is a separate module and doesn't share the same DB context as ticket-service in the new architecture, the existing `OrderServiceImpl` should work as-is with its own Mapper injection.

- [ ] **Step 5: Delete OrderMapper/OrderPassengerMapper from ticket-service**

```bash
rm ticket-service/src/main/java/com/project/ticket/mapper/OrderMapper.java
rm ticket-service/src/main/java/com/project/ticket/mapper/OrderPassengerMapper.java
```

Also remove `orderMapper` and `orderPassengerMapper` references from TicketBuyServiceImpl.

- [ ] **Step 6: Update ticket-service pom.xml — remove mybatis-plus dependency on orders**

No change needed — MyBatis-Plus is still used for ticket-related mappers (TrainStopoverMapper, TrainCarriageMapper, etc.).

- [ ] **Step 7: Compile and commit**

```bash
mvn compile -pl ticket-service,order-service -am -q
git add -A && git commit -m "feat: wire ticket→order via HTTP, remove direct DB access from ticket-service"
```

---

### Task 6: Integration test

- [ ] **Step 1: Full compile**

```bash
mvn compile -q
```

- [ ] **Step 2: Run all tests**

```bash
mvn test
```

- [ ] **Step 3: Commit any fixes**

---

## Completion Checklist

- [ ] Carriage info reads actual DB fields
- [ ] TrainStopCacheWarmup deleted, CacheLoader with Redis fallback active
- [ ] CacheConfig: refreshAfterWrite=10min, expireAfterAccess=1day
- [ ] Redis preloader test class complete
- [ ] Token preloader test class complete
- [ ] Token key includes seatType, token value = seats × sections
- [ ] Token Lua simplified (no lazy reset)
- [ ] ticket→order HTTP call working
- [ ] OrderMapper/PassengerMapper removed from ticket-service
- [ ] POST /order/create endpoint in order-service
- [ ] All modules compile, all tests pass
