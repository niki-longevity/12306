# 购票三种实现对比 Spec

日期: 2026-05-01

---

## 1. 目标

对比事务消息、本地消息表、同步 HTTP 三种订单创建方式的 QPS 和 RT。

## 2. 架构

```
TicketBuyService (interface)
  ├── @Primary TicketBuyServiceImpl       → 事务消息 (默认)
  ├── @Profile("bench-outbox") OutboxImpl → 本地消息表 INSERT ticket_outbox
  └── @Profile("bench-http") HttpImpl     → RestTemplate → order-service /order/create
```

三种实现共享：责任链、库存检查、令牌、位图扫描、双锁、Redis Lua。唯一不同的是 Lua 成功后的"订单创建"方式。

## 3. 对比项

| 方案 | Lua成功后 | 特点 |
|------|----------|------|
| 事务消息 | `sendMessageInTransaction` + 延时关单 | 半消息隔离，checkLocalTransaction 兜底 |
| 本地消息表 | `INSERT ticket_outbox` + `OutboxRetryScheduler` 定时扫 + 延时关单 | MySQL 持久化保证 |
| HTTP 同步 | `restTemplate.postForObject("...8083/order/create")` | 同步调用，阻塞等返回 |

## 4. 测试方法

```
for each profile in [default, bench-outbox, bench-http]:
    BenchmarkSetup.resetData()     ← 重置 Redis 位图/库存/令牌
    SpringBootTest(profile=xxx)
    16 线程 × 60s buy()
    记录: QPS, avg RT, p50, p99, Success
```

三个测试串行，各跑一次。数据从同一 DB 重新加载（保证公平）。

## 5. 恢复历史代码

- OutboxImpl: 从 git 恢复 `OutboxRetryScheduler`、`TicketOutboxMapper`、`TicketOutbox` 实体
- HttpImpl: 从 git 恢复 `RestTemplate.postForObject("http://localhost:8083/order/create")`

## 6. 文件清单

| 文件 | Action |
|------|--------|
| `ticket-service/.../TicketBuyService.java` | 不变 |
| `ticket-service/.../TicketBuyServiceImpl.java` | 加 `@Profile("!bench-outbox & !bench-http")` |
| `ticket-service/.../bench/OutboxImpl.java` | 新建 |
| `ticket-service/.../bench/HttpImpl.java` | 新建 |
| `ticket-service/.../bench/OutboxRetryScheduler.java` | 恢复（仅 bench-outbox profile） |
| `ticket-service/.../TicketOutboxMapper.java` | 恢复 |
| `ticket-service/.../TicketOutbox.java` | 恢复 |
| `ticket-service/.../bench/BenchmarkCompare.java` | 新建：串行跑三个方案 |
