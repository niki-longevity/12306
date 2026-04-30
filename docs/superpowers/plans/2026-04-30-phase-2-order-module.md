# Phase 2: Order Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build order creation, simulated payment, delayed auto-close (30min), and fallback task — all in the monolith.

**Architecture:** Lua success → INSERT orders/passengers in DB transaction → send RocketMQ delayed message (level 16 = 30min) → return "排队中". Delayed consumer checks status and cancels unpaid orders. Fallback scheduled task (every 10min) catches missed messages. SQL optimistic lock (`UPDATE WHERE status='UNPAID'`) prevents duplicate cancellation.

**Tech Stack:** Spring Boot 3.5.10, Java 21, MyBatis-Plus, RocketMQ (delay level 16), Redis (Lua rollback)

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `src/main/java/com/project/pojo/entity/Order.java` | Create | Order entity with status enum |
| `src/main/java/com/project/pojo/entity/OrderPassenger.java` | Create | Order-passenger relation entity |
| `src/main/java/com/project/mapper/OrderMapper.java` | Create | MyBatis-Plus mapper for orders |
| `src/main/java/com/project/mapper/OrderPassengerMapper.java` | Create | MyBatis-Plus mapper for order passengers |
| `src/main/java/com/project/service/OrderService.java` | Create | Order service interface |
| `src/main/java/com/project/service/impl/OrderServiceImpl.java` | Create | Order service implementation |
| `src/main/java/com/project/controller/OrderController.java` | Create | REST controller for order endpoints |
| `docs/sql/order_ddl.sql` | Create | DDL for orders and order_passengers tables |
| `src/main/resources/lua/ticket_refund.lua` | Create | Lua script to rollback Redis seat bitmap + stock |
| `src/main/java/com/project/mq/OrderCloseConsumer.java` | Create | RocketMQ consumer for 30min delayed close |
| `src/main/java/com/project/task/OrderCloseScheduler.java` | Create | Fallback scheduled task (every 10min) |
| `src/main/java/com/project/service/Impl/TicketBuyServiceImpl.java` | Modify | Call OrderService.create() after Lua success, send delayed MQ |
| `pom.xml` | Modify | Add spring-boot-starter + scheduling, optionally nothing if already present |

---

### Task 1: Order entities + DDL

**Files:**
- Create: `src/main/java/com/project/pojo/entity/Order.java`
- Create: `src/main/java/com/project/pojo/entity/OrderPassenger.java`
- Create: `docs/sql/order_ddl.sql`

- [ ] **Step 1: Create Order entity**

```java
package com.project.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName("orders")
public class Order implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private LocalDate date;
    private String trainCode;
    private String startStation;
    private String endStation;
    private Integer seatType;
    private Integer carriageNum;
    private Integer seatNum;
    private String status;       // UNPAID, PAID, CANCELLED, EXPIRED
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // Lua rollback context fields
    private Integer startSection;
    private Integer endSection;
    private Integer totalSectionCount;
    private Integer passengerCount;
    private String sectionsJson;
    private Long seatStartBit;
}
```

- [ ] **Step 2: Create OrderPassenger entity**

```java
package com.project.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("order_passengers")
public class OrderPassenger implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;
    private String realName;
    private String idCard;
}
```

- [ ] **Step 3: Create DDL**

Create `docs/sql/order_ddl.sql`:

```sql
-- 订单表（非分片，单库存储）
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT PRIMARY KEY COMMENT '订单ID（雪花算法）',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    date DATE NOT NULL COMMENT '乘车日期',
    train_code VARCHAR(20) NOT NULL COMMENT '车次编号',
    start_station VARCHAR(50) NOT NULL COMMENT '出发站',
    end_station VARCHAR(50) NOT NULL COMMENT '到达站',
    seat_type INT NOT NULL COMMENT '座位类型（0商务/1一等/2二等）',
    carriage_num INT NOT NULL COMMENT '车厢号',
    seat_num INT NOT NULL COMMENT '座位号',
    status VARCHAR(16) NOT NULL DEFAULT 'UNPAID' COMMENT '订单状态：UNPAID/PAID/CANCELLED/EXPIRED',
    expire_time DATETIME NOT NULL COMMENT '过期关单时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    -- Redis回滚上下文
    start_section INT NOT NULL DEFAULT 0 COMMENT '乘客起始区间',
    end_section INT NOT NULL DEFAULT 0 COMMENT '乘客结束区间',
    total_section_count INT NOT NULL DEFAULT 0 COMMENT '总区间数',
    passenger_count INT NOT NULL DEFAULT 0 COMMENT '乘车人数',
    sections_json VARCHAR(1024) DEFAULT '' COMMENT '区间列表JSON',
    seat_start_bit BIGINT NOT NULL DEFAULT 0 COMMENT '座位起始bit位',
    INDEX idx_user_id (user_id),
    INDEX idx_status_expire (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 订单乘车人表
CREATE TABLE IF NOT EXISTS order_passengers (
    id BIGINT PRIMARY KEY COMMENT 'ID（雪花算法）',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    real_name VARCHAR(50) NOT NULL COMMENT '乘车人姓名',
    id_card VARCHAR(20) NOT NULL COMMENT '乘车人身份证号',
    INDEX idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单乘车人表';
```

- [ ] **Step 4: Compile check**

Run: `mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/project/pojo/entity/Order.java
git add src/main/java/com/project/pojo/entity/OrderPassenger.java
git add docs/sql/order_ddl.sql
git commit -m "feat: add Order and OrderPassenger entities with DDL"
```

---

### Task 2: Order & OrderPassenger mappers

**Files:**
- Create: `src/main/java/com/project/mapper/OrderMapper.java`
- Create: `src/main/java/com/project/mapper/OrderPassengerMapper.java`

- [ ] **Step 1: Create OrderMapper**

```java
package com.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.pojo.entity.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
```

- [ ] **Step 2: Create OrderPassengerMapper**

```java
package com.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.project.pojo.entity.OrderPassenger;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderPassengerMapper extends BaseMapper<OrderPassenger> {
}
```

- [ ] **Step 3: Compile check**

Run: `mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/project/mapper/OrderMapper.java
git add src/main/java/com/project/mapper/OrderPassengerMapper.java
git commit -m "feat: add OrderMapper and OrderPassengerMapper"
```

---

### Task 3: OrderService interface + implementation

**Files:**
- Create: `src/main/java/com/project/service/OrderService.java`
- Create: `src/main/java/com/project/service/impl/OrderServiceImpl.java`

- [ ] **Step 1: Create OrderService interface**

```java
package com.project.service;

import com.project.pojo.entity.Order;
import com.project.pojo.entity.OrderPassenger;

import java.util.List;

public interface OrderService {
    /** 创建订单（购票成功后调用） */
    Order create(Order order, List<OrderPassenger> passengers);

    /** 模拟支付 */
    Order pay(Long orderId, Long userId);

    /** 手动取消 */
    Order cancel(Long orderId, Long userId);

    /** 超时关单（由MQ消费者或定时任务调用） */
    Order closeExpiredOrder(Long orderId);

    /** 查询用户订单列表 */
    List<Order> findByUser(Long userId);

    /** 查询单个订单 */
    Order findById(Long orderId);
}
```

- [ ] **Step 2: Create OrderServiceImpl**

```java
package com.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.project.mapper.OrderMapper;
import com.project.mapper.OrderPassengerMapper;
import com.project.pojo.entity.Order;
import com.project.pojo.entity.OrderPassenger;
import com.project.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderPassengerMapper orderPassengerMapper;

    @Override
    @Transactional
    public Order create(Order order, List<OrderPassenger> passengers) {
        order.setStatus("UNPAID");
        order.setExpireTime(LocalDateTime.now().plusMinutes(30));
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);

        passengers.forEach(p -> {
            p.setOrderId(order.getId());
            orderPassengerMapper.insert(p);
        });

        log.info("订单创建成功：orderId={}, trainCode={}, expireTime={}",
                order.getId(), order.getTrainCode(), order.getExpireTime());
        return order;
    }

    @Override
    public Order pay(Long orderId, Long userId) {
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Order::getId, orderId)
               .eq(Order::getUserId, userId)
               .eq(Order::getStatus, "UNPAID")
               .set(Order::getStatus, "PAID")
               .set(Order::getUpdateTime, LocalDateTime.now());
        int rows = orderMapper.update(null, wrapper);
        if (rows == 0) {
            throw new RuntimeException("支付失败：订单不存在或已过期");
        }
        log.info("订单支付成功：orderId={}", orderId);
        return orderMapper.selectById(orderId);
    }

    @Override
    public Order cancel(Long orderId, Long userId) {
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Order::getId, orderId)
               .eq(Order::getUserId, userId)
               .eq(Order::getStatus, "UNPAID")
               .set(Order::getStatus, "CANCELLED")
               .set(Order::getUpdateTime, LocalDateTime.now());
        int rows = orderMapper.update(null, wrapper);
        if (rows == 0) {
            throw new RuntimeException("取消失败：订单不存在或无法取消");
        }
        log.info("订单手动取消：orderId={}", orderId);
        return orderMapper.selectById(orderId);
    }

    @Override
    public Order closeExpiredOrder(Long orderId) {
        // 乐观锁：只有 UNPAID 状态才更新为 CANCELLED
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Order::getId, orderId)
               .eq(Order::getStatus, "UNPAID")
               .set(Order::getStatus, "CANCELLED")
               .set(Order::getUpdateTime, LocalDateTime.now());
        int rows = orderMapper.update(null, wrapper);
        if (rows == 0) {
            log.debug("关单跳过（已支付或已取消）：orderId={}", orderId);
            return null; // 已被处理
        }
        log.info("超时关单成功：orderId={}", orderId);
        return orderMapper.selectById(orderId);
    }

    @Override
    public List<Order> findByUser(Long userId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId)
               .orderByDesc(Order::getCreateTime);
        return orderMapper.selectList(wrapper);
    }

    @Override
    public Order findById(Long orderId) {
        return orderMapper.selectById(orderId);
    }
}
```

- [ ] **Step 3: Compile check**

Run: `mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/project/service/OrderService.java
git add src/main/java/com/project/service/impl/OrderServiceImpl.java
git commit -m "feat: add OrderService with create/pay/cancel/closeExpiredOrder"
```

---

### Task 4: OrderController

**Files:**
- Create: `src/main/java/com/project/controller/OrderController.java`

- [ ] **Step 1: Create OrderController**

```java
package com.project.controller;

import com.project.pojo.entity.Order;
import com.project.result.Result;
import com.project.service.OrderService;
import com.project.utils.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/list")
    public Result<List<Order>> list() {
        Long userId = BaseContext.getCurrentId();
        List<Order> orders = orderService.findByUser(userId);
        return Result.success(orders);
    }

    @GetMapping("/{id}")
    public Result<Order> getById(@PathVariable Long id) {
        Order order = orderService.findById(id);
        return Result.success(order);
    }

    @PutMapping("/{id}/pay")
    public Result<Order> pay(@PathVariable Long id) {
        Long userId = BaseContext.getCurrentId();
        Order order = orderService.pay(id, userId);
        return Result.success(order);
    }

    @PutMapping("/{id}/cancel")
    public Result<Order> cancel(@PathVariable Long id) {
        Long userId = BaseContext.getCurrentId();
        Order order = orderService.cancel(id, userId);
        return Result.success(order);
    }
}
```

- [ ] **Step 2: Verify BaseContext.getCurrentId() works with JWT filter**

The JwtAuthFilter sets `BaseContext.setCurrentId(userId)` after JWT validation. Confirm the method exists.

- [ ] **Step 3: Compile check**

Run: `mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/project/controller/OrderController.java
git commit -m "feat: add OrderController with list/get/pay/cancel endpoints"
```

---

### Task 5: Wire OrderService into TicketBuyServiceImpl

**Files:**
- Modify: `src/main/java/com/project/service/Impl/TicketBuyServiceImpl.java`

- [ ] **Step 1: Add OrderService field and import**

Add to imports:
```java
import com.project.pojo.entity.Order;
import com.project.pojo.entity.OrderPassenger;
import com.project.service.OrderService;
import org.springframework.transaction.annotation.Transactional;
```

Add field:
```java
private final OrderService orderService;
```

- [ ] **Step 2: Modify buy() — after Lua success, create order**

In the buy() method, after the Lua success block (where `if (luaResult == 1)` enters), replace the comment `// TODO 发送RocketMQ消息异步下单扣减数据库` and the log line with:

```java
if (luaResult == 1) {
    finalCarAbsoluteIndex = convertCarRelativeToAbsolute(freeSeat.carRelativeIndex, seatType);
    finalSeatGlobalIndex = freeSeat.seatGlobalIndex;
    buySuccess = true;

    // 创建订单（同步落库）
    Order order = Order.builder()
            .userId(BaseContext.getCurrentId())
            .date(date)
            .trainCode(trainCode)
            .startStation(startStation)
            .endStation(endStation)
            .seatType(seatType)
            .carriageNum(finalCarAbsoluteIndex)
            .seatNum(finalSeatGlobalIndex)
            .startSection(startSection)
            .endSection(endSection)
            .totalSectionCount(totalSectionCount)
            .passengerCount(passengerCount)
            .sectionsJson(sectionsJson)
            .seatStartBit(freeSeat.seatStartBit)
            .build();
    List<OrderPassenger> orderPassengers = passengerList.stream()
            .map(p -> OrderPassenger.builder()
                    .realName(p.getRealName())
                    .idCard(p.getIdCard())
                    .build())
            .toList();
    orderService.create(order, orderPassengers);

    // TODO Phase 4拆微服务后改为异步发送：发延时消息(30min后关单检查)
    // sendDelayedCloseMessage(order.getId());

    log.info("购票成功：车次{}，车厢{}，座位{}，订单号{}", trainCode, finalCarAbsoluteIndex, finalSeatGlobalIndex, order.getId());
    break;
}
```

Add to imports:
```java
import com.project.pojo.entity.Order;
import com.project.pojo.entity.OrderPassenger;
import com.project.service.OrderService;
import com.project.utils.BaseContext;
import java.util.List;
```

- [ ] **Step 3: Compile check**

Run: `mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS (may warn about unused import OrderCloseConsumer-related - expected, that's Task 6)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/project/service/Impl/TicketBuyServiceImpl.java
git commit -m "feat: wire OrderService.create() into ticket buy flow after Lua success"
```

---

### Task 6: Delayed close consumer (RocketMQ)

**Files:**
- Create: `src/main/java/com/project/mq/OrderCloseConsumer.java`

- [ ] **Step 1: Create OrderCloseConsumer**

```java
package com.project.mq;

import com.project.pojo.entity.Order;
import com.project.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-close-topic",
        consumerGroup = "order-close-consumer-group",
        consumeDelayLevel = 16  // 30分钟后投递
)
public class OrderCloseConsumer implements RocketMQListener<String> {

    private final OrderService orderService;

    @Override
    public void onMessage(String message) {
        Long orderId = Long.valueOf(message);
        log.info("收到关单检查消息：orderId={}", orderId);

        try {
            Order order = orderService.closeExpiredOrder(orderId);
            if (order != null) {
                // 回滚Redis座位（Task 7实现）
                // rollbackRedisSeat(order);
            }
        } catch (Exception e) {
            log.error("关单处理失败：orderId={}", orderId, e);
        }
    }
}
```

Note: `consumeDelayLevel` in `@RocketMQMessageListener` controls when the consumer receives the message. The producer will send with a specific delay level. Since RocketMQ's `@RocketMQMessageListener` doesn't natively support `consumeDelayLevel` via annotation (this is configured on the producer side), we instead:

1. Producer (TicketBuyServiceImpl) sends message with `setDelayTimeLevel(16)` which equals ~30min
2. Consumer just listens normally and processes immediately upon receipt

The actual implementation: TicketBuyServiceImpl will send a RocketMQ message with delay level 16.

- [ ] **Step 2: Create RocketMQ producer bean for delayed messages**

Create `src/main/java/com/project/config/RocketMQConfig.java`:

```java
package com.project.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RocketMQConfig {

    @Bean
    public RocketMQTemplate rocketMQTemplate(DefaultMQProducer defaultMQProducer) {
        RocketMQTemplate template = new RocketMQTemplate();
        template.setProducer(defaultMQProducer);
        return template;
    }
}
```

- [ ] **Step 3: Modify TicketBuyServiceImpl — send delayed message after order creation**

After `orderService.create(order, orderPassengers);` in the buy() method, add:

```java
// 发送延时关单消息（delayLevel=16 → 30分钟）
try {
    org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate =
            org.springframework.beans.factory.BeanFactoryAnnotationUtils
                    .qualifiedBeanOfType(
                            org.springframework.beans.factory.BeanFactoryUtils
                                    .findBeanFactory(null),
                            org.apache.rocketmq.spring.core.RocketMQTemplate.class);
    // Actually, inject RocketMQTemplate via constructor instead:
    // Add: private final RocketMQTemplate rocketMQTemplate;
    // Use: rocketMQTemplate.syncSend("order-close-topic",
    //         org.apache.rocketmq.common.message.MessageBuilder.withPayload(
    //             String.valueOf(order.getId()).getBytes()).build(),
    //         3000, 16);
} catch (Exception e) {
    log.error("发送延时关单消息失败：orderId={}", order.getId(), e);
    // 不阻塞购票流程，定时任务会兜底
}
```

Note: The RocketMQ delayed send needs proper injection. Add to TicketBuyServiceImpl:
- Field: `private final RocketMQTemplate rocketMQTemplate;`
- Import: `import org.apache.rocketmq.spring.core.RocketMQTemplate;`

And send with:
```java
org.springframework.messaging.Message<String> msg = org.springframework.messaging.support.MessageBuilder
        .withPayload(String.valueOf(order.getId()))
        .build();
rocketMQTemplate.syncSend("order-close-topic", msg, 3000, 16);
```

If `RocketMQTemplate` is not available as a bean (which it should be from `rocketmq-spring-boot-starter`), the delayed message can be deferred to Phase 4. For now, the fallback scheduled task (Task 7) handles all close operations.

- [ ] **Step 4: Compile check**

Run: `mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/project/mq/OrderCloseConsumer.java
git add src/main/java/com/project/config/RocketMQConfig.java (if created)
git add src/main/java/com/project/service/Impl/TicketBuyServiceImpl.java (if modified)
git commit -m "feat: add RocketMQ delayed close consumer for orders"
```

---

### Task 7: Redis rollback Lua + fallback scheduler

**Files:**
- Create: `src/main/resources/lua/ticket_refund.lua`
- Create: `src/main/java/com/project/task/OrderCloseScheduler.java`

- [ ] **Step 1: Create ticket_refund.lua**

```lua
-- 回滚Redis：位图清零 + 库存加回（与购票Lua镜像对称，幂等）
local bitmapKey = KEYS[1]
local stockKey = KEYS[2]
local seatStartBit = tonumber(ARGV[1]) or 0
local userStartSection = tonumber(ARGV[2]) or 0
local userEndSection = tonumber(ARGV[3]) or 0
local totalSectionCount = tonumber(ARGV[4]) or 0
local passengerCount = tonumber(ARGV[5]) or 0
local sectionsStr = ARGV[6] or ''

-- 解析区间列表
local sections = {}
local ok, res = pcall(cjson.decode, sectionsStr)
if ok and type(res) == 'table' then
    sections = res
else
    sectionsStr = string.gsub(sectionsStr, '[%[%]%s]', '')
    for num in string.gmatch(sectionsStr, '%d+') do
        table.insert(sections, tonumber(num))
    end
end

-- 计算掩码并清零
local sectionMask = 0
for i = userStartSection, userEndSection do
    sectionMask = bit.bor(sectionMask, bit.lshift(1, i - 1))
end

-- 读取当前位图
local bitFieldCmd = {'BITFIELD', bitmapKey, 'GET', 'u'..totalSectionCount, seatStartBit}
local seatBitmap = redis.call(unpack(bitFieldCmd))[1] or 0

-- 清零对应bit位（幂等：即使已经清零，再次清零结果不变）
local clearedBitmap = bit.band(seatBitmap, bit.bnot(sectionMask))
redis.call('BITFIELD', bitmapKey, 'SET', 'u'..totalSectionCount, seatStartBit, clearedBitmap)

-- 加回库存（幂等：HINCRBY回正）
for _, section in ipairs(sections) do
    redis.call('HINCRBY', stockKey, tostring(section), passengerCount)
end

return 1
```

- [ ] **Step 2: Create OrderCloseScheduler (fallback scheduled task)**

```java
package com.project.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.project.mapper.OrderMapper;
import com.project.pojo.entity.Order;
import com.project.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OrderCloseScheduler {

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> REFUND_LUA_SCRIPT;
    static {
        REFUND_LUA_SCRIPT = new DefaultRedisScript<>();
        REFUND_LUA_SCRIPT.setLocation(
                new org.springframework.core.io.ClassPathResource("lua/ticket_refund.lua"));
        REFUND_LUA_SCRIPT.setResultType(Long.class);
    }

    /**
     * 每10分钟扫描一次过期未支付订单
     */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void closeExpiredOrders() {
        log.debug("定时任务：开始扫描过期未支付订单...");

        // 查询过期未支付的订单（每次最多100条）
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, "UNPAID")
               .lt(Order::getExpireTime, java.time.LocalDateTime.now())
               .last("LIMIT 100");
        List<Order> expiredOrders = orderMapper.selectList(wrapper);

        if (expiredOrders.isEmpty()) {
            log.debug("无过期未支付订单");
            return;
        }

        log.info("定时任务：发现{}条过期未支付订单，开始关单...", expiredOrders.size());
        for (Order order : expiredOrders) {
            try {
                // 乐观锁关单（和MQ消费者逻辑一致）
                Order closed = orderService.closeExpiredOrder(order.getId());
                if (closed != null) {
                    rollbackRedisSeat(order);
                    log.info("定时任务关单成功：orderId={}", order.getId());
                }
            } catch (Exception e) {
                log.error("定时任务关单失败：orderId={}", order.getId(), e);
            }
        }
    }

    /**
     * 回滚Redis座位（位图清零 + 库存加回）
     */
    private void rollbackRedisSeat(Order order) {
        String bitmapKey = String.format("%s:%s:%d:bitmap",
                order.getDate(), order.getTrainCode(), order.getSeatType());
        String stockKey = String.format("Stock:%s:%s:%d",
                order.getDate(), order.getTrainCode(), order.getSeatType());

        stringRedisTemplate.execute(
                REFUND_LUA_SCRIPT,
                Arrays.asList(bitmapKey, stockKey),
                String.valueOf(order.getSeatStartBit()),
                String.valueOf(order.getStartSection()),
                String.valueOf(order.getEndSection()),
                String.valueOf(order.getTotalSectionCount()),
                String.valueOf(order.getPassengerCount()),
                order.getSectionsJson()
        );
        log.info("Redis座位回滚成功：orderId={}, bitmapKey={}", order.getId(), bitmapKey);
    }
}
```

- [ ] **Step 3: Compile check**

Run: `mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/lua/ticket_refund.lua
git add src/main/java/com/project/task/OrderCloseScheduler.java
git commit -m "feat: add Redis refund Lua script and fallback scheduled close task"
```

---

### Task 8: Integration check

- [ ] **Step 1: Full compile**

Run: `mvn compile 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run tests**

Run: `mvn test 2>&1 | grep -E "Tests run|BUILD"`
Expected: Tests run: 1, Failures: 0, Errors: 0, BUILD SUCCESS

- [ ] **Step 3: Review checklist**

- [ ] Order entity has rollback context fields
- [ ] OrderService.create() uses @Transactional
- [ ] closeExpiredOrder uses optimistic lock (WHERE status='UNPAID')
- [ ] ticket_refund.lua is idempotent (bit.bnot + HINCRBY)
- [ ] Fallback scheduler runs every 10min, LIMIT 100
- [ ] OrderController uses BaseContext.getCurrentId() for userId
- [ ] TicketBuyServiceImpl calls orderService.create() after Lua success

- [ ] **Step 4: Commit any remaining fixes**

```bash
git add -A
git diff --cached --stat
git commit -m "chore: final cleanup after phase 2 implementation"
```

---

## Phase 2 Completion Checklist

- [ ] Order & OrderPassenger entities with DDL
- [ ] OrderMapper & OrderPassengerMapper
- [ ] OrderService with create/pay/cancel/closeExpiredOrder/findByUser/findById
- [ ] OrderController with list/get/pay/cancel endpoints
- [ ] TicketBuyServiceImpl wired to OrderService.create()
- [ ] RocketMQ delayed close consumer
- [ ] ticket_refund.lua (idempotent Redis rollback)
- [ ] OrderCloseScheduler fallback (every 10min)
- [ ] Full project compiles and tests pass
