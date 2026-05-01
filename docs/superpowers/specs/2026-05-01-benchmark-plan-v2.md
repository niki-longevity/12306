# 购票三方案对比计划 v2

## 1. 数据隔离

每次跑新方案前：
- 杀掉所有 Java 进程
- `BenchmarkCompare.compareAll()` 顺序跑三个方案，每个方案前 `resetData()`
- resetData 重置：9900 趟车的 bitmap（全 0）、stock（540）、token（10260）
- 不启动 order-service（除非测 HTTP）

## 2. 三方案定义

| 方案 | Lua 成功后 | 可靠性 |
|------|-----------|--------|
| **PlainMQ** | `syncSend` + `syncSend`(delay) | async_flush + sync_repl |
| **Outbox** | `INSERT outbox` + scheduler 5s 扫 | MySQL WAL |
| **HTTP** | `restTemplate.postForObject` | 同步调用 |

## 3. 实现改动

### 3.1 PlainMQ（替代 TxMsg）
- `TicketBuyServiceImpl.postLuaSuccess()` → 改 `sendMessageInTransaction` 为 `syncSend`
- 删 `TicketTransactionListener`（不再需要）
- 代码：1 处改动

### 3.2 Outbox
- OutboxRetryScheduler 加 `@Profile("bench-outbox")` 已有，但 benchmark 期间会竞争
- **修复：** OutboxRetryScheduler 加 `@ConditionalOnProperty(name="rocketmq.name-server")`，benchmark 期间不对 DB 扫描
- 或者：schedule 改为 `fixedDelay=30s`，避免与 benchmark 重叠

### 3.3 HTTP
- 保持现状，已工作

## 4. 测试方法

```
1. kill java 进程
2. 启动 order-service（仅 HTTP 需要）
3. mvn test -Dtest=BenchmarkCompare [-Dspring.profiles.active=xxx]
4. 记录 QPS/RT
5. 循环下一个方案
```

## 5. DTO 预构建（去噪音）

`buildDto(code)` 每次读 Redis，已修复为预先构建 `List<TicketBuyDTO>`。

## 6. 预期结果

| 方案 | 预期 QPS | 理由 |
|------|---------|------|
| PlainMQ | ~2000-6000 | 1 次网络往返，非阻塞 |
| Outbox | ~800-1500 | MySQL INSERT 本地 |
| HTTP | ~800-1000 | 同步 HTTP 往返 + order-service 处理 |
