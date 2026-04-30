# Phase 8: Outbox + MySQL Bitmap + Conflict Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple ticket purchase from order creation via outbox pattern, add MySQL seat_bitmap as final arbiter, implement incremental Redis repair on conflict.

**Architecture:** Redis Lua (fast path) → INSERT ticket_outbox → return. RocketMQ consumer in order-service does MySQL bitmap UPDATE (optimistic lock) → INSERT orders. On bitmap conflict, read MySQL real bitmap, repair only dirty Redis sections.

**Tech Stack:** MySQL, MyBatis-Plus, RocketMQ, Redis, Lua

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `docs/sql/seat_bitmap_ddl.sql` | Create | DDL for seat_bitmap + ticket_outbox tables |
| `common/src/main/java/.../pojo/entity/SeatBitmap.java` | Create | Entity for seat_bitmap table |
| `ticket-service/.../pojo/entity/TicketOutbox.java` | Create | Entity for ticket_outbox table |
| `ticket-service/.../mapper/TicketOutboxMapper.java` | Create | MyBatis-Plus mapper |
| `ticket-service/.../task/OutboxRetryScheduler.java` | Create | 5s scan PENDING outbox entries |
| `ticket-service/.../service/impl/TicketBuyServiceImpl.java` | Modify | INSERT outbox instead of HTTP call |
| `order-service/.../controller/OrderController.java` | Modify | Consumer: bitmap UPDATE + INSERT orders |
| `order-service/.../mapper/SeatBitmapMapper.java` | Create | MyBatis-Plus mapper for seat_bitmap |
| `order-service/.../service/impl/OrderServiceImpl.java` | Modify | create() calls bitmap update first |
| `order-service/src/main/resources/lua/ticket_conflict_fix.lua` | Create | Lua: repair dirty sections in Redis |
| `order-service/.../task/OrderCloseScheduler.java` | Modify | Also update MySQL bitmap on close |

---

### Task 1: DDL + Entities

**Files:**
- Create: `docs/sql/seat_bitmap_ddl.sql`
- Create: `common/src/main/java/com/project/common/pojo/entity/SeatBitmap.java`
- Create: `ticket-service/src/main/java/com/project/ticket/pojo/entity/TicketOutbox.java`
- Create: `ticket-service/src/main/java/com/project/ticket/mapper/TicketOutboxMapper.java`

- [ ] **Step 1: Create DDL**

```sql
-- 座位位图表 (MySQL 真相源)
CREATE TABLE IF NOT EXISTS seat_bitmap (
    id            BIGINT PRIMARY KEY COMMENT '雪花ID',
    train_code    VARCHAR(20) NOT NULL,
    date          DATE NOT NULL,
    seat_type     INT NOT NULL COMMENT '0商务/1一等/2二等',
    carriage_num  INT NOT NULL,
    seat_num      INT NOT NULL,
    bitmap        VARBINARY(256) NOT NULL DEFAULT '' COMMENT '每个section 1 bit, 1=已售',
    version       INT NOT NULL DEFAULT 0,
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_seat (train_code, date, seat_type, carriage_num, seat_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 本地消息表
CREATE TABLE IF NOT EXISTS ticket_outbox (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_type  VARCHAR(32) NOT NULL COMMENT 'ORDER_CREATE / ORDER_CLOSE',
    payload       TEXT NOT NULL COMMENT 'JSON',
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/DEAD',
    retry_count   INT NOT NULL DEFAULT 0,
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_retry    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: Create SeatBitmap entity in common**

```java
package com.project.common.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("seat_bitmap")
public class SeatBitmap implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String trainCode;
    private LocalDate date;
    private Integer seatType;
    private Integer carriageNum;
    private Integer seatNum;
    private byte[] bitmap;
    @Version
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 3: Create TicketOutbox entity in ticket-service**

```java
package com.project.ticket.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("ticket_outbox")
public class TicketOutbox implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageType;
    private String payload;
    private String status;
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime nextRetry;
}
```

- [ ] **Step 4: Create TicketOutboxMapper in ticket-service**

```java
package com.project.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.ticket.pojo.entity.TicketOutbox;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketOutboxMapper extends BaseMapper<TicketOutbox> {
}
```

- [ ] **Step 5: Compile and commit**

```bash
mvn compile -pl common,ticket-service -am -q
git add -A && git commit -m "feat: add seat_bitmap DDL, SeatBitmap entity, TicketOutbox entity+mapper"
```

---

### Task 2: Modify TicketBuyServiceImpl — outbox INSERT instead of HTTP

**Files:**
- Modify: `ticket-service/src/main/java/com/project/ticket/service/impl/TicketBuyServiceImpl.java`

- [ ] **Step 1: Remove HTTP-related imports and fields**

Remove:
```java
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import java.util.HashMap;
```

Remove field: `private final RestTemplate restTemplate;`

- [ ] **Step 2: Add outbox imports and field**

Add:
```java
import com.fasterxml.jackson.databind.ObjectMapper;  // already present
import com.project.ticket.mapper.TicketOutboxMapper;
import com.project.ticket.pojo.entity.TicketOutbox;
import java.time.LocalDateTime;
```

Add field: `private final TicketOutboxMapper outboxMapper;`

(Note: ObjectMapper and LocalDateTime already exist in imports.)

- [ ] **Step 3: Replace HTTP order creation with outbox INSERT**

Replace the entire block after `if (boughtCarRelIdx < 0)` (the order creation section) with:

```java
if (boughtCarRelIdx < 0) {
    return Result.error(attempts >= maxAttempts ? "系统繁忙，请重试" : "无可用座位");
}

// Outbox: 写入本地消息表，由 MQ 消费者异步创建订单
int finalCarAbsIdx = convertCarRelativeToAbsolute(boughtCarRelIdx, seatTypeCode);
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
            .map(p -> {
                Map<String, String> pm = new HashMap<>();
                pm.put("realName", p.getRealName());
                pm.put("idCard", p.getIdCard());
                return pm;
            }).collect(Collectors.toList());
    orderPayload.put("passengers", passengers);

    // ORDER_CREATE: 创建订单消息
    TicketOutbox outbox = TicketOutbox.builder()
            .messageType("ORDER_CREATE")
            .payload(objectMapper.writeValueAsString(orderPayload))
            .status("PENDING")
            .retryCount(0)
            .createTime(LocalDateTime.now())
            .nextRetry(LocalDateTime.now())
            .build();
    outboxMapper.insert(outbox);

    log.info("Outbox消息已写入：outboxId={}, trainCode={}, seat={}/{}",
            outbox.getId(), trainCode, finalCarAbsIdx, boughtSeatGlobalIdx);
} catch (Exception e) {
    log.error("Outbox写入失败：车次{}", trainCode, e);
}

log.info("购票成功：车次{}，车厢{}，座位{}", trainCode, finalCarAbsIdx, boughtSeatGlobalIdx);
return Result.success("排队中");
```

- [ ] **Step 4: Also remove RestTemplateConfig (optional, or leave for future use)**

Keep RestTemplateConfig for now — it may be used elsewhere later.

- [ ] **Step 5: Compile and commit**

```bash
mvn compile -pl ticket-service -am -q
git add -A && git commit -m "refactor: replace HTTP order call with outbox table INSERT"
```

---

### Task 3: Create OutboxRetryScheduler + ORDER_CREATE consumer

**Files:**
- Create: `ticket-service/src/main/java/com/project/ticket/task/OutboxRetryScheduler.java`
- Create: `order-service/src/main/java/com/project/order/mq/OrderCreateConsumer.java`

- [ ] **Step 1: Create OutboxRetryScheduler**

```java
package com.project.ticket.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.ticket.mapper.TicketOutboxMapper;
import com.project.ticket.pojo.entity.TicketOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OutboxRetryScheduler {

    private final TicketOutboxMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;

    private static final int MAX_RETRIES = 5;

    @Scheduled(fixedRate = 5000)
    public void retryPendingMessages() {
        LambdaQueryWrapper<TicketOutbox> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TicketOutbox::getStatus, "PENDING")
               .lt(TicketOutbox::getNextRetry, LocalDateTime.now())
               .last("LIMIT 100");
        List<TicketOutbox> pendingList = outboxMapper.selectList(wrapper);

        for (TicketOutbox outbox : pendingList) {
            try {
                String topic = outbox.getMessageType().equals("ORDER_CLOSE")
                        ? "order-close-topic" : "order-create-topic";
                rocketMQTemplate.syncSend(topic,
                        MessageBuilder.withPayload(outbox.getPayload()).build(),
                        3000, 0);

                outbox.setStatus("SENT");
                outboxMapper.updateById(outbox);
                log.debug("Outbox retry sent: id={}", outbox.getId());
            } catch (Exception e) {
                int retries = outbox.getRetryCount() + 1;
                outbox.setRetryCount(retries);
                outbox.setNextRetry(LocalDateTime.now().plusSeconds(10 * retries));
                if (retries >= MAX_RETRIES) {
                    outbox.setStatus("DEAD");
                    log.error("Outbox message dead after {} retries: id={}", retries, outbox.getId());
                }
                outboxMapper.updateById(outbox);
            }
        }
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile -pl ticket-service -am -q
git add -A && git commit -m "feat: add outbox retry scheduler (5s scan PENDING entries)"
```

---

### Task 4: Modify order-service consumer — MySQL bitmap UPDATE + INSERT orders

**Files:**
- Create: `order-service/src/main/java/com/project/order/mapper/SeatBitmapMapper.java`
- Modify: `order-service/src/main/java/com/project/order/service/impl/OrderServiceImpl.java`
- Create: `order-service/src/main/resources/lua/ticket_conflict_fix.lua`

- [ ] **Step 1: Create SeatBitmapMapper in order-service**

```java
package com.project.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.common.pojo.entity.SeatBitmap;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeatBitmapMapper extends BaseMapper<SeatBitmap> {

    @Update("UPDATE seat_bitmap SET bitmap = bitmap | #{mask}, version = version + 1 " +
            "WHERE train_code = #{trainCode} AND date = #{date} AND seat_type = #{seatType} " +
            "AND carriage_num = #{carriageNum} AND seat_num = #{seatNum} " +
            "AND (bitmap & #{mask}) = 0")
    int updateBitmapIfNoConflict(@Param("trainCode") String trainCode,
                                 @Param("date") java.time.LocalDate date,
                                 @Param("seatType") int seatType,
                                 @Param("carriageNum") int carriageNum,
                                 @Param("seatNum") int seatNum,
                                 @Param("mask") byte[] mask);
}
```

- [ ] **Step 2: Modify OrderServiceImpl.create() — bitmap check before insert**

Add SeatBitmapMapper field: `private final SeatBitmapMapper seatBitmapMapper;`

Modify `create()` method:

```java
@Override
@Transactional
public Order create(Order order, List<OrderPassenger> passengers) {
    // 1. 构建位图掩码
    long seatStartBit = order.getSeatStartBit();
    int startSection = order.getStartSection();
    int endSection = order.getEndSection();
    int totalSectionCount = order.getTotalSectionCount();

    byte[] mask = buildSectionMask(seatStartBit, startSection, endSection, totalSectionCount);

    // 2. MySQL 乐观锁更新位图
    int updated = seatBitmapMapper.updateBitmapIfNoConflict(
            order.getTrainCode(), order.getDate(), order.getSeatType(),
            order.getCarriageNum(), order.getSeatNum(), mask);

    if (updated == 0) {
        // 冲突！Redis 有脏数据
        log.warn("Bitmap conflict detected: train={}, seat={}/{}",
                order.getTrainCode(), order.getCarriageNum(), order.getSeatNum());
        // 读取 MySQL 当前位图，比较冲突区间
        SeatBitmap current = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeatBitmap>()
                .eq(SeatBitmap::getTrainCode, order.getTrainCode())
                .eq(SeatBitmap::getDate, order.getDate())
                .eq(SeatBitmap::getSeatType, order.getSeatType())
                .eq(SeatBitmap::getCarriageNum, order.getCarriageNum())
                .eq(SeatBitmap::getSeatNum, order.getSeatNum())
                .last("LIMIT 1");
        current = seatBitmapMapper.selectOne(current);
        if (current != null) {
            // 修复 Redis：标记脏区间+清零干净区间
            repairRedisAfterConflict(order, current.getBitmap(), mask);
        }
        throw new RuntimeException("座位冲突，请重试");
    }

    // 3. 创建订单
    order.setStatus("UNPAID");
    order.setExpireTime(LocalDateTime.now().plusMinutes(30));
    order.setCreateTime(LocalDateTime.now());
    order.setUpdateTime(LocalDateTime.now());
    orderMapper.insert(order);

    passengers.forEach(p -> {
        p.setOrderId(order.getId());
        orderPassengerMapper.insert(p);
    });

    log.info("订单创建成功：orderId={}, trainCode={}", order.getId(), order.getTrainCode());
    return order;
}
```

- [ ] **Step 3: Create ticket_conflict_fix.lua**

```lua
-- 冲突修复: 清干净区间 + 补脏区间 + 修正库存
-- KEYS[1] = bitmapKey, KEYS[2] = stockKey, KEYS[3] = tokenKey
-- ARGV[1] = seatStartBit (座位起始bit)
-- ARGV[2] = totalSectionCount
-- ARGV[3] = userStartSection  (乘客起始区间)
-- ARGV[4] = userEndSection    (乘客结束区间)
-- ARGV[5] = cleanSectionsJson  [[3,4]] 需要清零的区间号列表
-- ARGV[6] = dirtySectionsJson  [[5,8]] 需要置1的区间号列表
-- ARGV[7] = cleanStockDiff    (干净区间要加回的库存)
-- ARGV[8] = dirtyStockDiff    (脏区间要补扣的库存)
-- ARGV[9] = tokenDelta        (令牌修正量: 正数INCRBY, 负数DECRBY)
-- ARGV[10] = passengerCount
-- ARGV[11] = cleanSectionsForStock  JSON of all sections for stock adjust

local bitmapKey = KEYS[1]
local stockKey = KEYS[2]
local tokenKey = KEYS[3]

local seatStartBit = tonumber(ARGV[1])
local totalSectionCount = tonumber(ARGV[2])
local userStartSection = tonumber(ARGV[3])
local userEndSection = tonumber(ARGV[4])
local cleanSectionsStr = ARGV[5]
local dirtySectionsStr = ARGV[6]
local cleanStockDiff = tonumber(ARGV[7])
local dirtyStockDiff = tonumber(ARGV[8])
local tokenDelta = tonumber(ARGV[9])
local passengerCount = tonumber(ARGV[10])

-- Parse JSON arrays
local function parseSections(str)
    local result = {}
    str = string.gsub(str, '[%[%]%s]', '')
    if str == '' then return result end
    for num in string.gmatch(str, '%d+') do
        table.insert(result, tonumber(num))
    end
    return result
end

-- 1. 清零干净区间 (购买失败的部分)
local cleanSections = parseSections(cleanSectionsStr)
for _, section in ipairs(cleanSections) do
    local bitPos = seatStartBit + section - 1
    redis.call('BITFIELD', bitmapKey, 'SET', 'u'..totalSectionCount, bitPos, 0)
end

-- 2. 标记脏区间为已售 (Redis 丢失的数据)
local dirtySections = parseSections(dirtySectionsStr)
for _, section in ipairs(dirtySections) do
    local bitPos = seatStartBit + section - 1
    redis.call('BITFIELD', bitmapKey, 'SET', 'u'..totalSectionCount, bitPos, 1)
end

-- 3. 修正库存: 干净区间加回
local cleanStockSections = parseSections(ARGV[11])
for _, section in ipairs(cleanStockSections) do
    redis.call('HINCRBY', stockKey, tostring(section), cleanStockDiff)
end

-- 脏区间库存不碰 (原购买者已扣)

-- 4. 修正令牌
if tokenDelta > 0 then
    redis.call('INCRBY', tokenKey, tokenDelta)
elseif tokenDelta < 0 then
    redis.call('DECRBY', tokenKey, -tokenDelta)
end

return 1
```

- [ ] **Step 4: Compile and commit**

```bash
mvn compile -pl order-service,common -am -q
git add -A && git commit -m "feat: MySQL bitmap UPDATE check, conflict repair Lua"
```

---

### Task 5: Update OrderCloseScheduler — also update MySQL bitmap

**Files:**
- Modify: `order-service/src/main/java/com/project/order/task/OrderCloseScheduler.java`

- [ ] **Step 1: Add SeatBitmapMapper field and bitmap rollback**

Add: `private final SeatBitmapMapper seatBitmapMapper;`

In the `closeExpiredOrders()` method, after `rollbackRedisSeat(order)`, add:

```java
// 同时回滚 MySQL 位图
byte[] invertedMask = buildInvertedMask(order.getSeatStartBit(),
        order.getStartSection(), order.getEndSection(), order.getTotalSectionCount());
seatBitmapMapper.updateBitmapIfNoConflict(
        order.getTrainCode(), order.getDate(), order.getSeatType(),
        order.getCarriageNum(), order.getSeatNum(), invertedMask);
```

Note: `buildInvertedMask` creates a mask that clears the seat's section bits (bitmap & ~mask). Or use a dedicated SQL:

```java
@Update("UPDATE seat_bitmap SET bitmap = bitmap & ~#{mask}, version = version + 1 " +
        "WHERE train_code = #{trainCode} AND ...")
int clearBitmap(@Param("trainCode") String trainCode, ..., @Param("mask") byte[] mask);
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile -pl order-service -am -q
git add -A && git commit -m "feat: update MySQL bitmap on order close"
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

- [ ] seat_bitmap + ticket_outbox DDL
- [ ] SeatBitmap entity in common
- [ ] TicketOutbox entity + mapper in ticket-service
- [ ] TicketBuyServiceImpl writes outbox instead of HTTP
- [ ] OutboxRetryScheduler (5s scan, 5 retries max)
- [ ] SeatBitmapMapper with optimistic UPDATE SQL
- [ ] OrderServiceImpl.create() checks bitmap before insert
- [ ] ticket_conflict_fix.lua for Redis repair
- [ ] OrderCloseScheduler also updates MySQL bitmap
- [ ] All modules compile, all tests pass
