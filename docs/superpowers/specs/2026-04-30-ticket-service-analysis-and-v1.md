# 购票模块分析：当前逻辑、优缺点与进化方案

日期: 2026-04-30

---

## 1. 当前逻辑全景

```
buy() 请求
  │
  ├── ① 责任链校验 (Param → TrainInfo → Station)
  │     └── TrainInfoValidateHandler 已从 JVM 缓存取车次信息，但 buy() 又取了一次——重复
  │
  ├── ② Caffeine 缓存取经停站信息 (Key: date:trainCode, 15天TTL)
  │
  ├── ③ 提取起止站序 → 计算区间列表 [startSection, endSection]
  │     └── 例如：北京(站序1) → 上海(站序20)，区间为 [1, 19]
  │
  ├── ④ Redis HMGET 查各区间库存，取最小值
  │     └── 最小值 < 乘车人数 → 返回"余票不足"
  │
  ├── ⑤ Token 桶限流 (Lua原子: DECR tokenKey)
  │     └── 令牌不足时自动重置为 minStock-1
  │
  ├── ⑥ 从 JVM 缓存取车厢数量 (按 seatType)
  │
  ├── ⑦ Redis GET 拉取整个位图 (Key: date:trainCode:seatType:bitmap)
  │     └── 例如二等座: 90座×6车厢×19区间 = 10260 bits ≈ 1.3KB
  │
  ├── ⑧ JVM 遍历位图，筛选所有空闲座位 → freeSeatList
  │     └── isSeatFreeInMemory(): 逐 bit 检查 [startSection, endSection] 是否全为 0
  │
  ├── ⑨ 遍历 freeSeatList，逐个尝试抢占：
  │     ├── 本地锁 (ConcurrentHashMap<String, ReentrantLock>.tryLock(0))
  │     ├── Redis Lua 原子执行:
  │     │     ├── BITFIELD GET  → 再次验证空闲
  │     │     ├── BITFIELD SET  → 标记已售
  │     │     └── HINCRBY      → 扣减库存
  │     ├── result=1 → 成功，break
  │     ├── result=0 → 已被占，下一个
  │     └── 异步延迟1s删除锁对象 (ScheduledExecutorService)
  │
  └── ⑩ 返回"排队中" / "无可用座位"
        └── 订单创建已注释（微服务拆分后待恢复）
```

---

## 2. 优点

### 2.1 位图设计是核心亮点
每个座位用 `totalSectionCount` 个 bit 表示各区间占用。一次 `BITFIELD GET` 读取整个座位的全部区间，Redis 网络往返从 O(区间数) 降到 O(1)。这是 12306 级别系统的基础设计。

### 2.2 JVM 预筛选 + Lua 双检
Step ⑧ 在 JVM 内存从位图批量筛出空闲座位，过滤掉 99% 无效候选，避免大量无效 Lua 执行。Lua 中再 BITFIELD GET 验证 —— 防止 JVM read 和 Lua execute 之间的 race condition。

### 2.3 本地锁削峰
`ConcurrentHashMap<String, ReentrantLock>` 按座位粒度加锁。`tryLock(0)` 立即返回，避免线程排队等待。同一座位在同一 JVM 实例内只有一个线程能进入 Lua 执行。

### 2.4 Token 桶限流
Lua 实现的库存感知限流 —— 令牌数 = 最小库存 - 1，库存越低令牌越少，自动减速，防止无效请求冲击 Redis。

### 2.5 Lua 原子性
位图标记 + 库存扣减在同一次 Redis 调用中原子完成，无中间状态。

---

## 3. 缺点

### 3.1 严重问题

**位图全量拉取是内存炸弹。** Step ⑦ 每次请求都从 Redis GET 整个位图到 JVM 堆。二等座 1.3KB 虽不大，但高并发时持续分配 byte[] 导致 GC 抖动。300 QPS 时每秒产生 ~400KB 的 byte[] 分配，加上 freeSeatList 中 SeatInfo 对象的分配，GC 压力显著。

**双层遍历冗余。** Step ⑧ 遍历所有座位生成 `freeSeatList`，Step ⑨ 再遍历 `freeSeatList` 逐个尝试 Lua。两次遍历：第一次浪费 CPU 生成可能永远用不到的中间列表，第二次可能遍历到已被其他线程抢占的座位（Lua 返回 0）。合并后可省去中间对象分配。

**无随机起始位置。** 遍历总是从车厢 1、座位 1 开始。高并发时所有请求争抢同一批前排座位，冲突率极高。随机起始位置可以分散竞争。

**订单落库缺失。** 微服务拆分后 OrderService 和 RocketMQ 调用被注释。Lua 成功后直接返回"排队中"，Redis 扣了库存但数据库无订单记录。缓存过期后座位可能"复活"重复出售。

**错误处理用字符串。** `return "系统异常"` 调用方无法区分错误类型，无法做自动化重试或降级。

### 3.2 设计问题

**责任链和主流程重复取缓存。** TrainInfoValidateHandler 已经从 Caffeine 取了 TicketListBO，但 buy() 方法再次从缓存获取。Context 传递了 BO 对象，校验完可以直接复用。

**异步删除锁对象过度设计。** `ScheduledExecutorService` 延迟 1s 删除 ConcurrentHashMap 中的锁对象，增加单线程池维护成本和潜在的任务积压。可以直接在 finally 中 delete。

**DB 限流无意义。** TicketGetServiceImpl 中 Guava RateLimiter 限制数据库查询 20 QPS。但在缓存优先架构下 DB 降级是低频操作，限流器在热点路径上几乎不触发，反而增加对象创建开销。

### 3.3 isSeatFreeInMemory 逐 bit 检查效率低

循环体内 `bitOffset / 8`、`bitOffset % 8`、`1 << bitInByte` 对每个区间执行一次。对于 19 个区间的车次，每个座位执行 19 次位操作。可以优化为一次提取座位全部 bit + 一次 AND mask。

---

## 4. 进化方案

### V1（立即可做）

| # | 改进 | 做法 |
|---|------|------|
| 1 | **合并双层遍历** | 去掉 `freeSeatList`，扫描位图发现空闲座位立即尝试 Lua 抢，成功 break |
| 2 | **随机起始位置** | `ThreadLocalRandom` 随机选起始车厢+座位索引，分散并发冲突 |
| 3 | **恢复订单落库** | ticket-service HTTP 调用 order-service `POST /order/create`，或用 Feign |
| 4 | **超时熔断** | 遍历超过 N 次尝试或 T 毫秒后返回"系统繁忙"，防止请求堆积 |
| 5 | **异常改为结构化返回** | 返回 `Result<String>` 或抛业务异常带 code |
| 6 | **去掉异步锁删除** | finally 中直接 `localLockMap.remove(localLockKey)`，不再延迟 1s |
| 7 | **去掉 DB 限流** | 移除 TicketGetServiceImpl 中 Guava RateLimiter |

### V2（中期优化）

| # | 改进 | 做法 |
|---|------|------|
| 1 | **Caffeine 缓存位图** | 5s 本地缓存热点车次位图，90%+ 命中率 |
| 2 | **分片位图** | 540 座拆成 6 个车厢位图，JVM 只扫目标车厢 |
| 3 | **异步预筛选** | CompletableFuture 并行：拉位图 + 查库存 |
| 4 | **批量座位分配** | Lua 脚本改为一次请求尝试多个座位（如 5 个），减少 Redis RTT |
| 5 | **车厢级别库存热数据** | 缓存每个车厢的剩余座位数，快速跳过已满车厢 |

### V3（长期演进，极高并发）

| # | 改进 | 做法 |
|---|------|------|
| 1 | **无锁 CAS** | Lua 纯 CAS：BITFIELD SET 失败直接返回，省去本地锁 |
| 2 | **一致性 Hash 分片** | 不同车次位图按 trainCode hash 到不同 Redis 实例 |
| 3 | **异步队列化** | 请求入内存队列 → 攒批 → 一次 Lua 分配多张票 |
| 4 | **预分片位图写入** | 写入时直接分片，避免单 key 热点 |
| 5 | **读写分离** | 位图查票走 replica，写走 master |
