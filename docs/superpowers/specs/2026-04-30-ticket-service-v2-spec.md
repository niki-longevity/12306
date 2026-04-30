# 购票模块 V2 优化 Spec

日期: 2026-04-30 | 基于 V1 分析文档

---

## 1. 优化清单

| # | 改动 | 说明 |
|---|------|------|
| 1 | 自适应熔断 | 票多时限制搜索次数，票少时放开，默认 maxAttempts=100 |
| 2 | 双锁机制 | 本地锁 (ReentrantLock) + 分布式锁 (Redisson RLock) |
| 3 | 脑裂处理 | 位图 Lua 脚本是最终裁判，锁只削峰不保正确性 |
| 4 | 令牌桶分场景 | 日常：总可售区间数作为令牌上限；高峰：OD 对方案 |
| 5 | 位图检查优化 | 一次提取 int + 一次 AND，替代逐 bit 循环 |
| 6 | Pipeline 候选座查询 | 用 Redis Pipeline BITFIELD GET 批量查候选座，位图不出 Redis |

---

## 2. 自适应熔断

```
minStock = Redis 查到的最低区间库存

if minStock < passengerCount * 3:
    maxAttempts = Integer.MAX_VALUE   # 票少，不限制
else:
    maxAttempts = 100                 # 票多，随便找就能中
```

票多时熔断防止无效 CPU 空转；票少时允许扫完所有座位，不做限制。

---

## 3. 双锁机制

### 3.1 锁层次

```
请求进来
  │
  ├── ① 本地锁 ReentrantLock.tryLock(0)  ← JVM 内互斥，失败立即下一个座
  │
  ├── ② Redisson RLock.tryLock(100ms)    ← 跨 JVM 互斥，失败释放本地锁继续
  │
  ├── ③ Lua 原子操作                      ← 最终仲裁（BITFIELD GET + SET + 库存扣减）
  │
  └── finally: 释放分布式锁 → 释放本地锁 → 删除锁对象
```

### 3.2 为什么是这样的顺序

- **本地锁先上**：同 JVM 内 90%+ 的冲突被 `tryLock(0)` 挡掉，分布式锁只处理跨实例的少数场景
- **分布式锁有超时**：`tryLock(100ms)` 防止死等。抢不到说明另一个实例正在处理同一座位
- **Lua 是最终裁判**：即使分布式锁出现异常（脑裂），Lua 里的 `BITFIELD GET` 会再次验证——bit 已为 1 则返回 0

### 3.3 脑裂：不需要红锁

红锁需要 N 个 Redis 独立节点全部成功，性能差。我们的方案不需要：

- Redisson RLock 单节点 + watchdog 自动续期
- 锁的正确性由 **Lua BITFIELD** 保证，不是锁本身
- 脑裂场景：两节点同时执行 Lua → 第一个 SET 成功 → 第二个 GET 看到 bit=1 → 返回 0，天然串行化
- 锁的作用是**削峰**（减少无效 Lua 执行），不是**正确性依赖**

---

## 4. 令牌桶分场景

### 4.1 日常场景：宽松令牌

```
令牌总数 = 座位数 × 区间数
二等座: 540 座 × 19 区间 = 10260 个令牌

购票时: DECR token BY passengerCount（一个乘车人买 N 个区间扣 N）
```

日常流量下令牌远远够用，几乎不会触发限流。

### 4.2 节假高峰：OD 对预计算

**OD 对定义**：从出发站 A 到到达站 B 的组合。一趟 20 站的车次有 190 个 OD 对（20×19/2）。

**Redis 存储：**

```
Key:   OD:2026-05-01:G1:北京:上海
Value: Hash {
         business:  {startStock},    # 商务座在该OD区间的最小初始库存
         firstClass: {startStock},   # 一等座
         second:    {startStock}     # 二等座
       }
```

**购票时的操作：**

```
1. 查 OD 余额：HGET OD:date:trainCode:出发站:到达站 seatType
2. 如果 OD 余额 < passengerCount → 直接返回"售罄"，不进入搜索
3. 如果通过 → 进入搜索流程 → Lua 扣减
4. Lua 成功 → 同步 HINCRBY 扣减所有经过的 OD 对
```

**内存估算：**

```
一趟 20 站车次: 190 个 OD 对
每对 3 种座位 × 4 字节 int = 12 bytes
190 × 12 = 2.3 KB/趟

15 天 × 10,000 趟/天 = 150,000 趟
150,000 × 2.3 KB = 345 MB (纯数据)
加 key 开销 (~60 bytes/key): 150,000 × 190 × 60 = 1.7 GB
总计 ≈ 2 GB

过期策略: TTL = 发车时间 + 1 天，自动清理
```

单个 16GB Redis 实例轻松容纳。

---

## 5. 位图空闲检查：一次提取替代逐 bit

### 5.1 数据结构关系

```
一趟车次（如 G1，20 个站）：

经停站:  [北京, 天津, 济南, ..., 上海]     ← 20 个站
站序:     [1,     2,    3,   ..., 20]      ← stationIndex
区间:     [1,     2,    3,   ..., 19]      ← sectionCount = 19

座位:     二等座: 90座/厢 × 6厢 = 540 座
          一等座: 28座/厢 × 1厢 = 28 座
          商务座: 5座/厢 × 1厢  = 5 座

位图 Key: 2026-05-01:G1:2:bitmap       ← date:trainCode:seatType
位图结构: 每个座位占 19 个 bit（totalSectionCount）
          第 0 座 → bit[0..18]
          第 1 座 → bit[19..37]
          第 k 座 → bit[k*19 .. (k+1)*19-1]

          每个 bit 表示该区间是否已售 (1=已售)
          
          例: 座位第 3 个区间的 bit = k*19 + (3-1) = k*19 + 2

车厢映射: 车厢 1 (商务) → 座位 0~4
          车厢 2 (一等) → 座位 0~27
          车厢 3~8 (二等) → 座位 0~89 (每厢)
```

### 5.2 算法：一次提取 + 一次 mask

```
输入: bitmapBytes (byte[]), seatStartBit, userStartSection, userEndSection

1. 计算乘客区间在座位 bit 组中的绝对区间:
     rangeStartBit = seatStartBit + userStartSection - 1    // 如：offset 2
     rangeEndBit   = seatStartBit + userEndSection - 1      // 如：offset 7
     rangeBits     = userEndSection - userStartSection + 1  // 6 个 bit

2. 确定这些 bit 跨越哪些字节:
     startByte = rangeStartBit / 8
     endByte   = rangeEndBit / 8                           // 最多跨 3 个字节

3. 一次读取 1~3 字节拼成 int:
     value = bitmapBytes[startByte] | (bitmapBytes[startByte+1] << 8) | ...

4. 构建 mask（rangeBits 个连续的 1）:
     startBitInValue = rangeStartBit % 8                   // value 内的起始偏移
     mask = ((1 << rangeBits) - 1) << startBitInValue

5. 一次判断完成:
     return (value & mask) == 0
```

### 5.3 性能对比

| | 逐 bit (V1) | 一次提取 (V2) |
|---|------------|-------------|
| 循环次数 | 5~19 次 (rangeBits) | 1~3 次 (读字节拼 int) |
| 核心操作 | 每次: 除法 + 取模 + 位移 + AND | 1 次 mask 构建 + 1 次 AND |
| 堆分配 | 无 | 无（全栈上局部变量） |

---

## 6. Pipeline 候选座查询（备选）

**当前方案 V1**：一次 Redis GET 拉全量位图到 JVM。保留此方案作为默认。

**备选方案**：需要进一步评估的场景——当位图特别大（如 30 站车次，每座 29 bits，总位图 15KB+）时：

```
Redis Pipeline（一次网络往返）:
  BITFIELD GET bitmapKey u29 (seat1_startBit)  ← 只读一个 U29 整数
  BITFIELD GET bitmapKey u29 (seat2_startBit)
  BITFIELD GET bitmapKey u29 (seat3_startBit)
  ...
  BITFIELD GET bitmapKey u29 (seat10_startBit)

返回 10 个整数 → JVM 判断空闲 → 选一个 Lua 抢
```

**当前不激活**：当前位图 ~1.3KB，一次 GET 开销可接受。Pipeline 方案留作扩展点。

---

## 7. 代码改动清单

| 文件 | 改动 |
|------|------|
| `TicketBuyServiceImpl.java` | 1. 自适应 maxAttempts 2. 双锁（Redisson RLock） 3. 位图检查替换为一次提取 |
| `ticket_buy.lua` | 可选：新增 carAvailable 计数器更新（OD 对相关） |
| `pom.xml` (ticket-service) | 无需新增依赖，Redisson 已存在 |
| `application.yml` | 无需改动，Redisson 已自动配置 |
