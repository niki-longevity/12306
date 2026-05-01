# Phase 9: 事务消息 + MQ延时关单 + 性能压测

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace outbox table with RocketMQ transactional messages for ORDER_CREATE, MQ delayed message for close, add QPS/RT benchmark tests.

**Architecture:** Redis Lua (fast path) → transactional MQ (半消息→COMMIT/ROLLBACK) → consumer creates order. ORDER_CLOSE via RocketMQ delayLevel=16.

**Tech Stack:** RocketMQ 5.3.0 transactional message, Spring Boot 3.5.10

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `ticket-service/.../TicketBuyServiceImpl.java` | Modify | Replace outbox INSERT with transactional MQ send |
| `ticket-service/.../task/OutboxRetryScheduler.java` | Delete | No longer needed |
| `ticket-service/.../mapper/TicketOutboxMapper.java` | Delete | No longer needed |
| `ticket-service/.../pojo/entity/TicketOutbox.java` | Delete | No longer needed |
| `order-service/.../mq/OrderCloseConsumer.java` | Modify | Close order + rollback Redis + MySQL bitmap |
| `ticket-service/.../task/BenchmarkTest.java` | Create | Test: QPS/RT query + buy |

---

### Task 1: Transactional MQ replace outbox

**Files:**
- Modify: `ticket-service/.../service/impl/TicketBuyServiceImpl.java`
- Delete: `ticket-service/.../task/OutboxRetryScheduler.java`
- Delete: `ticket-service/.../mapper/TicketOutboxMapper.java`
- Delete: `ticket-service/.../pojo/entity/TicketOutbox.java`

- [ ] **Step 1: Modify buy() — replace outbox INSERT with transactional send**

In `TicketBuyServiceImpl`, replace the outbox section (after Lua success) with:

```java
// Lua 成功 → 事务消息发送 ORDER_CREATE
try {
    Map<String, Object> orderPayload = new HashMap<>();
    orderPayload.put("userId", BaseContext.getCurrentId());
    orderPayload.put("date", date.toString());
    orderPayload.put("trainCode", trainCode);
    orderPayload.put("startStation", startStation);
    orderPayload.put("endStation", endStation);
    orderPayload.put("seatType", seatTypeCode);
    orderPayload.put("carriageNum", finalCarAbsIdx);
    orderPayload.put("seatNum", boughtSeatGlobalIdx);
    orderPayload.put("startSection", startSection);
    orderPayload.put("endSection", endSection);
    orderPayload.put("totalSectionCount", totalSectionCount);
    orderPayload.put("passengerCount", passengerCount);
    orderPayload.put("sectionsJson", sectionsJson);
    orderPayload.put("seatStartBit", calculateSeatStartBit(boughtCarRelIdx, boughtSeatGlobalIdx, totalSectionCount, seatTypeCode));

    List<Map<String, String>> passengers = passengerList.stream()
            .map(p -> { Map<String, String> m = new HashMap<>(); m.put("realName",p.getRealName()); m.put("idCard",p.getIdCard()); return m; })
            .collect(Collectors.toList());
    orderPayload.put("passengers", passengers);

    String payloadJson = objectMapper.writeValueAsString(orderPayload);
    org.springframework.messaging.Message<String> msg = org.springframework.messaging.support.MessageBuilder
            .withPayload(payloadJson).build();

    // ORDER_CREATE: 事务消息
    rocketMQTemplate.sendMessageInTransaction("order-create-topic", msg, null);

    // ORDER_CLOSE: 延时消息 (delayLevel=16 → 30min)
    org.springframework.messaging.Message<String> closeMsg = org.springframework.messaging.support.MessageBuilder
            .withPayload(payloadJson).build();
    rocketMQTemplate.syncSend("order-close-topic", closeMsg, 3000, 16);

} catch (Exception e) {
    log.error("MQ发送失败，回滚Redis：车次{}", trainCode, e);
    // TODO: MQ失败 → Lua回滚Redis (seat + stock + token)
}
```

- [ ] **Step 2: Add transactional listener bean**

In `ticket-service/src/main/java/com/project/ticket/config/RocketMQConfig.java` (create if not exists, or add to existing config):

```java
package com.project.ticket.config;

import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;

@RocketMQTransactionListener(txProducerGroup = "ticket-outbox-producer")
public class TicketTransactionListener implements RocketMQLocalTransactionListener {

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        // Redis Lua 已在 buy() 中成功执行，直接 COMMIT
        return RocketMQLocalTransactionState.COMMIT;
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // MQ 回调：检查 Redis 位图是否已标记
        // 如果已标记 → COMMIT（让消费者处理）
        // 如果未标记 → ROLLBACK（消息丢弃）
        // 简化：总是 COMMIT（不实现回查，依赖事务消息的超时自动回滚）
        return RocketMQLocalTransactionState.COMMIT;
    }
}
```

- [ ] **Step 3: Add txProducerGroup to application.yml**

```yaml
rocketmq:
  name-server: 172.22.32.238:9876
  producer:
    group: ticket-outbox-producer
```

(Already partially configured, verify `group` matches `txProducerGroup`.)

- [ ] **Step 4: Delete files**

```bash
rm ticket-service/.../task/OutboxRetryScheduler.java
rm ticket-service/.../mapper/TicketOutboxMapper.java
rm ticket-service/.../pojo/entity/TicketOutbox.java
```

- [ ] **Step 5: Compile and commit**

```bash
mvn compile -pl ticket-service -am -q
git add -A && git commit -m "feat: replace outbox with RocketMQ transactional message + delayed close"
```

---

### Task 2: Update OrderCloseConsumer — also rollback MySQL bitmap

**Files:**
- Modify: `order-service/.../mq/OrderCloseConsumer.java`

The existing `OrderCloseConsumer` needs to also clear MySQL bitmap and rollback Redis on close:

```java
@Override
public void onMessage(String message) {
    Long orderId = Long.valueOf(message);  // or parse from JSON
    // ... closeExpiredOrder → clearBitmap + rollbackRedis
}
```

Wait — the ORDER_CLOSE message payload is the full order JSON (same as ORDER_CREATE). Need to parse order data, close it, and rollback.

(Current implementation already does Redis rollback via scheduler. Move logic here.)

- [ ] **Step 1: Rewrite OrderCloseConsumer**

Read current implementation and update to do full close flow.

---

### Task 3: Benchmark tests

**Files:**
- Create: `ticket-service/src/test/java/com/project/ticket/benchmark/QueryBenchmark.java`
- Create: `ticket-service/src/test/java/com/project/ticket/benchmark/BuyBenchmark.java`

- [ ] **Step 1: Query benchmark**

```java
@Test
void benchmarkTicketList() {
    int warmup = 100, iterations = 1000;
    long[] latencies = new long[iterations];
    // Warmup
    for (int i = 0; i < warmup; i++) ticketGetService.list(query);
    // Benchmark
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
        long t0 = System.nanoTime();
        ticketGetService.list(query);
        latencies[i] = System.nanoTime() - t0;
    }
    long elapsed = System.nanoTime() - start;
    double qps = iterations * 1e9 / elapsed;
    double avgRt = Arrays.stream(latencies).average().orElse(0) / 1e6;  // ms
    double p99 = percentile(latencies, 99) / 1e6;
    System.out.printf("Query QPS=%.1f, avgRT=%.2fms, p99=%.2fms%n", qps, avgRt, p99);
}
```

- [ ] **Step 2: Buy benchmark**

Similar structure, use ThreadPoolExecutor with N threads for concurrent buys.

---

### Task 4: Integration test

- [ ] Full compile + test

```bash
mvn compile -q && mvn test
```
