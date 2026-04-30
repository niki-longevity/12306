# 购票流程解耦 Spec：Outbox + MySQL 位图 + 增量冲突修复

日期: 2026-05-01

---

## 1. 核心思想

MySQL 持久化位图是**最终裁判**，Redis 是**快速路径**。冲突时增量修复 Redis 脏数据，不清退合法已售区间。

### 1.1 数据模型

**MySQL seat_bitmap 表：**

```sql
CREATE TABLE seat_bitmap (
    id            BIGINT PRIMARY KEY,
    train_code    VARCHAR(20)  NOT NULL,
    date          DATE         NOT NULL,
    seat_type     INT          NOT NULL,
    carriage_num  INT          NOT NULL,
    seat_num      INT          NOT NULL,
    bitmap        VARBINARY(256) NOT NULL DEFAULT '',  -- 每个 section 1 bit
    version       INT          NOT NULL DEFAULT 0,     -- 乐观锁
    UNIQUE KEY uk_seat (train_code, date, seat_type, carriage_num, seat_num)
);
```

每个座位一行，`bitmap` 的 bit N 表示第 N+1 区间是否已售（1=已售）。

**ticket_outbox 表：**

```sql
CREATE TABLE ticket_outbox (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_type  VARCHAR(32)  NOT NULL,  -- ORDER_CREATE, ORDER_CLOSE
    payload       TEXT         NOT NULL,  -- JSON
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING, SENT, DEAD
    retry_count   INT          NOT NULL DEFAULT 0,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_retry    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status_retry (status, next_retry)
);
```

---

## 2. 购票完整流程

```
ticket-service                                      order-service
─────────────                                       ─────────────

① Redis Lua (快速路径，和现在一样):
   BITFIELD GET → 验证空地
   BITFIELD SET → 标记已售
   HINCRBY → 扣库存
   成功: 获得 seatStartBit + 乘客区间信息

② INSERT ticket_outbox:
   message_type = ORDER_CREATE
   payload = { trainCode, date, seatType, carriageNum, seatNum,
               startSection, endSection, userStartSection, userEndSection,
               totalSectionCount, passengerCount, sectionsJson,
               seatStartBit, userId, passengers }
   status = PENDING

③ 即时发 RocketMQ:
   成功 → UPDATE outbox SET status='SENT'
   失败 → 留为 PENDING，定时任务兜底

④ 返回"排队中"


⑤ 消费 ORDER_CREATE:
   BEGIN;

   -- 关键：乐观锁 UPDATE MySQL 位图
   UPDATE seat_bitmap
   SET bitmap = bitmap | #{userMask},
       version = version + 1
   WHERE train_code = #{trainCode}
     AND date = #{date}
     AND seat_type = #{seatType}
     AND carriage_num = #{carriageNum}
     AND seat_num = #{seatNum}
     AND (bitmap & #{userMask}) = 0;     ← 核心：无冲突才更新

   IF affected_rows = 1 THEN
       -- 位图更新成功，无冲突
       INSERT INTO orders (...);
       INSERT INTO order_passengers (...);
       COMMIT;
       -- ① 回 outbox 的 ACK 已在 Broker 层面确认
       -- ② 库存/令牌校准由定时任务负责

   ELSE
       -- 冲突！Redis 有脏数据
       ROLLBACK (orders 没创建);

       -- 读取 MySQL 当前位图，找出哪些区间真的冲突了
       SELECT bitmap FROM seat_bitmap WHERE ...;

       -- 计算冲突区间和干净区间
       -- 干净区间 (3-4): userMask 中 MySQL 显示空闲的部分
       -- 脏区间   (5-8): userMask 中 MySQL 显示已售的部分 → Redis 丢了这些数据

       -- ① 回滚 Redis 干净区间：BITFIELD SET 0 (这些区间购买失败)
       -- ② 修正 Redis 脏区间：  BITFIELD SET 1 (恢复真实已售状态)
       -- ③ 修正库存：脏区间的库存应该已扣（原购买者扣的），干净的加回
       -- ④ 修正令牌：按实际已售数重算

       执行 Redis 修复 Lua;
       COMMIT (seat_bitmap 的 SELECT 在事务外);
       -- 告警通知
   END IF;
```

---

## 3. 冲突修复 Lua 脚本

**ticket_conflict_fix.lua：**

```lua
-- KEYS[1] = bitmapKey    (Redis 位图 key)
-- KEYS[2] = stockKey     (Redis 库存 key)
-- KEYS[3] = tokenKey     (Redis 令牌 key)
--
-- ARGV[1] = seatStartBit     (座位起始 bit)
-- ARGV[2] = cleanSections    JSON: [[3,4]] 需要清零的区间
-- ARGV[3] = dirtySections    JSON: [[5,8]] 需要置 1 的区间
-- ARGV[4] = totalSectionCount
-- ARGV[5] = passengerCount
-- ARGV[6] = sectionsJson     JSON: [[3,4,5,6,7,8]] 所有区间（用于库存修正）
-- ARGV[7] = dirtyStockDiff   dirty 区间库存需扣多少
-- ARGV[8] = cleanStockDiff   clean 区间库存需加回多少

-- 1. 清零干净区间 (购买失败，回滚)
--    对每个 clean 区间: BITFIELD SET bitPos 0

-- 2. 标记脏区间为已售 (Redis 丢失的数据)
--    对每个 dirty 区间: BITFIELD SET bitPos 1

-- 3. 修正库存: 干净区间 HINCRBY +passengerCount, 脏区间不碰
-- 4. 修正令牌: 从 MySQL 已售座位数计算 Token 应剩余值
```

---

## 4. 定时任务

### 4.1 Outbox 兜底投递 (每 5 秒)

```sql
SELECT * FROM ticket_outbox
WHERE status = 'PENDING' AND next_retry < NOW()
LIMIT 100;
```
→ 重试发 MQ → 成功 UPDATE status='SENT' → 失败 retry_count+1 → 超过 5 次 status='DEAD' 告警

### 4.2 关单回滚 (每 10 秒)

```sql
SELECT * FROM orders
WHERE status = 'UNPAID' AND expire_time < NOW()
LIMIT 100;
```
→ 每条记录:
```
BEGIN;
  UPDATE orders SET status='CANCELLED' WHERE id=? AND status='UNPAID';
  IF affected_rows = 1 THEN
    -- 更新 MySQL 位图: bitmap = bitmap & ~mask
    UPDATE seat_bitmap SET bitmap = bitmap & #{invertedMask};
    -- 回滚 Redis: BITFIELD 清零 + HINCRBY 加回 + INCRBY 令牌
  END IF;
COMMIT;
```

### 4.3 库存令牌校准 (每 5 分钟)

```sql
-- 每个车次+座位类型:
SELECT COUNT(*) FROM seat_bitmap WHERE ...
-- 从 MySQL 位图重建总已售卖区间数 → 计算 stock 和 token 该是多少
-- 与 Redis 值比较，差值 > 阈值 → 告警 + 自动修正
```

---

## 5. 定时任务去重 (乐观锁)

所有定时任务都用 `UPDATE ... WHERE status = 'UNPAID'` 或 `WHERE (bitmap & mask) = 0` 的方式。这本身就是乐观锁——`affected_rows = 0` 直接跳过，不会重复处理。

---

## 6. 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 位图真相源 | MySQL `seat_bitmap` 表 | 持久化保证，写前 WAL |
| 快速路径 | Redis BITFIELD | 99.99% 请求在这里完成，性能不降 |
| 冲突处理 | 增量修复，不清退合法已售 | 只回滚购买失败的区间 |
| MQ 投递 | outbox 表 + 定时扫描 | 消息不丢 |
| 对账 | 不做全量对账 | 只在 Redis 重启/冲突时触发修复 |
