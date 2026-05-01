# 购票性能调试计划

## 问题分析

### HTTP 没落库
HTTP 调用 `restTemplate.postForObject("http://localhost:8083/order/create")`。如果 order-service 没启动或返回异常，catch 块静默吞掉。但 `buy()` 依然返回 `Result.success("排队中")`。**成功计数完全虚假**。

### MQ 吞吐远低于用户预期
用户之前测到 6000+ QPS，现在只有 1093。可能原因：
- MQ producer 是瓶颈（事务消息 3 次往返 → 已改为普通 syncSend 1 次）
- buy() 内部锁竞争、位图扫描、Redis 往返
- MQ broker 配置未优化

## 调试步骤

### Step 1: 消除 MQ 开销
注释掉 postLuaSuccess 中两条 MQ send，直接 log 成功。跑 benchmark。如果 QPS 接近用户预期（6000+），说明瓶颈在 MQ。如果 QPS 依然低，瓶颈在 buy() 内部。

### Step 2: 验证 HTTP 落库
在 HTTP benchmark 前确认：
- order-service 进程存在且端口 8083 可达
- 手工 curl 验证 /order/create 能返回正确结果
- benchmark 后查 MySQL 确认 orders 表有新增记录

### Step 3: 拆解 Redis 往返
如果 MQ 不是瓶颈，逐步排查：
- 库存检查（HMGET）→ 注释后测
- 令牌检查（Lua）→ 注释后测
- 位图拉取（GET byte[]）→ 注释后测
- 分布式锁 → 测本地锁 only
- Lua 原子操作 → 最终开销
