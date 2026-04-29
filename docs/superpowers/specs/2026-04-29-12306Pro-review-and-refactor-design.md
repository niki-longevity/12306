# 12306Pro 审查与重构设计文档

日期: 2026-04-29 | 版本: 1.0

---

## 1. 项目现状

**12306Pro** — 火车票购票系统。Spring Boot 3.5.10 + Java 21 + MyBatis-Plus + Redis + Caffeine + RocketMQ + ShardingSphere-JDBC。

现有代码约 50+ 文件，包含用户登录/注册、查票、购票三个功能。购票模块的缓存预热、位图选座、Lua 原子操作等核心骨架已搭建，但多项关键逻辑被注释或未完成。订单模块未开发。

---

## 2. 架构目标：单体拆微服务

### 2.1 拆分理由
- 购票模块（本地锁 + 位图遍历 + Lua）是并发密集型，需独立扩缩容
- 订单模块从零开发，在独立服务中不受现有耦合影响
- 用户模块变更频率低，独立部署更稳定
- 当前无正式接口契约，模块间直接依赖内部实现

### 2.2 目标架构

```
┌────────────────────────────────────────────────────┐
│                   API Gateway                       │
│            (JWT 验签 + 路由转发)                     │
└────┬────────────────┬────────────────┬─────────────┘
     │                │                │
     ▼                ▼                ▼
┌──────────┐  ┌──────────────┐  ┌──────────────┐
│  user-   │  │   ticket-    │  │   order-     │
│ service  │  │   service    │  │   service    │
│ 端口:8081│  │  端口:8082   │  │  端口:8083   │
│          │  │              │  │              │
│ 用户注册 │  │ 查票(读密集)  │  │ 订单创建     │
│ 用户登录 │  │ 购票(写密集)  │  │ 订单查询     │
│ JWT签发  │  │ 位图+Lua     │  │ 订单取消     │
│ 布隆过滤 │  │ JVM缓存      │  │              │
└────┬─────┘  └──────┬───────┘  └──────┬───────┘
     │               │                 │
     │        RocketMQ "购票成功"       │
     │               └─────────────────┤
     │                                 │
     ▼               ▼                 ▼
┌────────────────────────────────────────────────────┐
│            MySQL (ShardingSphere)                   │
│   user 库(3节点×6分表) + ticket 库 + order 库       │
└────────────────────────────────────────────────────┘
```

### 2.3 服务职责

| 服务 | 端口 | 核心职责 | 外部依赖 |
|------|------|---------|---------|
| user-service | 8081 | 注册/登录、JWT 签发/刷新、布隆过滤器维护 | MySQL(sharding), Redis(布隆) |
| ticket-service | 8082 | 车票查询、购票（位图遍历+本地锁+Lua）、缓存管理 | Redis, Caffeine, RocketMQ |
| order-service | 8083 | 消费购票消息、订单落库、订单查询/取消/状态追踪 | MySQL, RocketMQ |
| gateway | 8080 | JWT 验签、路由转发、限流 | 无 |

### 2.4 通信方式
- **同步调用**：Gateway → 各服务（HTTP + JWT Header）
- **异步消息**：ticket-service → order-service（RocketMQ，购票成功事件）
- **不引入 Feign**：初期服务间无同步调用需求，避免不必要的复杂度

---

## 3. 实施阶段

### 阶段 1 — B: 功能完整性修复（立刻，在单体中做）

不改架构，先把现有代码跑通。这是"能用的基线"，后续拆分才有意义。

#### 3.1 认证模块：切 JWT（替换现有拦截器）
- 新增 `JwtUtil`：生成/校验/刷新 JWT token（jjwt 库）
- 新增 `JwtAuthFilter`：从 Header 取 token → 验签 → 写入 SecurityContext
- 删除 `UserLoginInterceptor` 和 `WebMvcConfiguration` 中的旧拦截器注册
- UserService.login() 返回 JWT 而非 Redis UUID token
- pom.xml 添加 `jjwt-api` / `jjwt-impl` / `jjwt-jackson` 依赖

#### 3.2 用户模块修复
- `UserServiceImpl.login()` 第 60 行：`selectOne()` 结果判空，避免 NPE
- `UserServiceImpl.add()`：注册成功后，布隆过滤器 `add(username)`
- 密码加密（安全项，但属于 A 类，此处先标记，阶段 3 修复）

#### 3.3 购票模块修复
- `TicketBuyServiceImpl` 恢复 `@Service` 注解（第 30 行）
- 启用责任链校验（第 169-181 行取消注释）
- `TicketGetGetServiceImpl`：恢复 DB 降级查询（第 83-93 行取消注释并调整）
- 修复类名：`TicketGetGetServiceImpl` → `TicketGetServiceImpl`
- 修复包名：`service/Impl` → `service/impl`

#### 3.4 购票 Lua 脚本抽离
- 将 `TICKET_BUY_LUA_SCRIPT` 从 Java 字符串抽到 `src/main/resources/lua/ticket_buy.lua`
- 将 `TOKEN_BUCKET_LUA_SCRIPT` 抽到 `src/main/resources/lua/token_bucket.lua`

### 阶段 2 — B: 订单模块（在单体中开发）

订单模块依赖购票结果，拆成微服务之前先在单体中验证接口和业务逻辑。

#### 3.5 订单实体与表结构
- `entity/Order`：id, userId, date, trainCode, startStation, endStation, seatType, carriageNum, seatNum, status(UNPAID/PAID/CANCELLED/EXPIRED), expireTime, createTime, updateTime
- `entity/OrderPassenger`：id, orderId, realName, idCard
- `mapper/OrderMapper` + `mapper/OrderPassengerMapper`
- 建表 DDL（放在 `docs/` 目录）

#### 3.6 订单流程：同步落库 + 延时关单

**购票成功链路：**

```
Lua 脚本成功（Redis 位图+库存已扣）
    │
    ├── INSERT orders (status=UNPAID, expire_time=now()+30min)
    ├── INSERT order_passengers
    │   └── 以上在同一个 DB 事务中
    │
    ├── 发 RocketMQ 延时消息（delayLevel=16 → 30min）
    │   └── payload: {orderId, trainCode, date, seatType, sections...}
    │
    └── 返回 "排队中，请30分钟内支付"
```

**支付模拟：**

```
PUT /order/{id}/pay
    │
    └── UPDATE orders SET status='PAID' WHERE id=? AND status='UNPAID'
        └── affected_rows=1 → 成功
        └── affected_rows=0 → 已过期/重复支付，抛异常
```

**延时关单消费者：**

```
收到 30min 延时消息 → SELECT order WHERE id = orderId
    │
    ├── status=PAID  → ACK，不管
    ├── status=CANCELLED → ACK，不管
    │
    └── status=UNPAID →
          ├── UPDATE orders SET status='CANCELLED' WHERE id=? AND status='UNPAID'
          ├── Lua 脚本回滚 Redis：
          │     ├── BITFIELD SET 把座位区间 bit 位清零
          │     └── HINCRBY 库存加回
          └── ACK
```

**回收 Redis 库存的 Lua 脚本（与购票镜像对称）：**

```lua
-- 传入：bitmapKey, stockKey, seatStartBit, userStartSection, userEndSection, 
--       totalSectionCount, passengerCount, sectionsJson
-- 1. 计算乘客区间掩码
-- 2. BITFIELD SET 把对应 bit 位设回 0（可重入：设两次还是0）
-- 3. 遍历 sections，HINCRBY 加回库存
```

**定时任务兜底（每 10 分钟）：**

```sql
SELECT * FROM orders 
WHERE status = 'UNPAID' AND expire_time < NOW() 
LIMIT 100;
```
对每条记录执行和延时消息相同的关单逻辑。`UPDATE ... WHERE status='UNPAID'` 是天然乐观锁，定时任务和延时消息抢同一行，只有一个能成功（affected_rows=1），后续的 affected_rows=0 直接跳过。Lua 回滚 Redis 也是幂等的——bit 位清零操作重复执行结果不变。

#### 3.7 订单 Service & Controller
- `OrderService`：create、pay（模拟支付）、cancel（手动取消）、findByUser、findById
- `OrderController`：`GET /order/list`、`GET /order/{id}`、`PUT /order/{id}/pay`、`PUT /order/{id}/cancel`
- `TicketBuyServiceImpl` 购票成功后调用 `OrderService.create()` 同步落库

### 阶段 3 — A: 安全修复

#### 3.7 密码加密
- 引入 `spring-security-crypto`（仅用 BCrypt，不引入整个 Security 框架）
- `UserServiceImpl` 注册时 `BCryptPasswordEncoder.encode(password)` 后入库
- 登录时 `BCryptPasswordEncoder.matches(password, user.getPassword())`
- 提供 SQL 迁移脚本：用 BCrypt 加密存量明文密码（先备份，在测试库验证后再生产执行）

#### 3.8 密钥与密码脱敏
- `sharding.yaml` 数据库密码 → 环境变量 `${DB_PASSWORD}`（ShardingSphere 配置文件暂不支持直接读 env，改为启动时通过 `-D` JVM 参数注入）
- `application-dev.yml` 删除硬编码 Redis 密码
- JWT secret → 环境变量 `${JWT_SECRET}`，开发环境通过 IDE run config 注入，禁止在配置文件中写死

### 阶段 4 — 微服务拆分

#### 3.9 拆分步骤
1. 创建 `user-service/`、`ticket-service/`、`order-service/`、`gateway/` 四个 Maven 子模块
2. 父 pom.xml 改为 `<packaging>pom</packaging>`，添加 `<modules>`
3. 按 2.3 的职责表迁移代码到对应模块
4. **公共模块** (`common/`)：Result、BaseException、全局异常处理器、共享实体
5. Gateway 引入 `spring-cloud-gateway` + JWT 验签 Filter

#### 3.10 拆分后：订单异步落库方案对比

拆分后 ticket-service 和 order-service 不在同一进程，购票成功后的订单落库需要从同步切异步。两种方案：

**方案 A：outbox 本地消息表**（默认方案）

```
Lua 成功 → INSERT ticket_outbox → 立即发 RocketMQ → order-service 消费
                                           │
                                           └── 失败？→ 定时扫描 PENDING 记录重试
```

- 优点：实现简单，无额外中间件
- 缺点：代码侵入（每发消息都得写 outbox），数据膨胀需清理

**方案 B：Canal CDC**（升级方向，面试高阶回答）

```
Lua 成功 → INSERT ticket_outbox（业务代码只管写表）
                │
                ▼
         MySQL binlog 产生 INSERT 记录
                │
                ▼
         Canal 监听 → 推送到 RocketMQ → order-service 消费
```

- 优点：应用代码零感知，binlog 级别可靠性（与 DB 强一致），不丢消息
- 缺点：多一个中间件，需部署 Canal Server + ZK

**本阶段采用方案 A（outbox）**，Canal 列为拆分后第二阶段的升级方向。选择理由：当前交易量不需要中间件级别的 CDC，outbox 模式实现简单且够用。

#### 3.11 拆分后延时关单调整

拆分后延时消息由 ticket-service 在写 outbox 的同时发送。关单消费者仍然在 order-service，收到消息后：
```
SELECT order → 判断状态 → CANCELLED 则 Lua 回滚 Redis（Redis 是共享的）
```
注意：回滚 Redis 位图的 Lua 脚本需要 ticket-service 暴露一个内部接口，或关单消费者直接连 Redis 执行 Lua。

#### 3.12 拆分后各模块依赖

| 模块 | Spring Boot | 特殊依赖 |
|------|------------|---------|
| common | - | Result, Exception, 共享 DTO |
| gateway | spring-cloud-gateway | jjwt |
| user-service | web, mybatis-plus | redisson, jjwt, BCrypt |
| ticket-service | web, data-redis, cache | caffeine, redisson, rocketmq |
| order-service | web, mybatis-plus | rocketmq |

### 阶段 5 — C: 代码质量

#### 3.11 消重 & 重构
- `calculateSeatStockFromJvm`（TicketGetServiceImpl）和 `calculateSeatStock`（TicketStockCalculator）合并到 `TicketStockCalculator`
- 座位类型枚举 (SeatType: 0/1/2 → BUSINESS/FIRST/SECOND)
- 车厢信息硬编码 → 从数据库 `train_carriage` 表读取
- 魔法数字提取为常量或配置

#### 3.12 类命名 & 包结构规范
- `TicketGetGetServiceImpl` → `TicketGetServiceImpl`
- `service/Impl` → `service/impl`
- `pojo/bo` / `pojo/dto` / `pojo/vo` / `pojo/entity` → 保留，结构合理

### 阶段 6 — D: 测试与运维

#### 3.13 测试
- user-service: 注册/登录/NPE 场景单元测试
- ticket-service: 购票 Lua 脚本单独测试、位图算法测试
- order-service: CRUD 测试

---

## 4. 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| JWT 库 | jjwt (io.jsonwebtoken) | 社区最活跃的 Java JWT 库 |
| 加密算法 | BCrypt | 行业标准，Spring Security 内置 |
| 微服务通信 | HTTP（无 Feign） | 初期无服务间同步调用，保持简单 |
| 订单落库 | 阶段2同步 → 阶段4切 outbox + MQ | 先在单体中验证逻辑，拆分后再切异步 |
| 延时关单 | RocketMQ delayLevel=16 (30min) + 定时任务兜底 | 双保险，UPDATE WHERE status='UNPAID' 天然乐观锁防重 |
| 关单防重 | SQL 乐观锁 + Lua 幂等 | UPDATE WHERE status='UNPAID' 保证只有一条执行成功，Lua bit 清零可重入 |
| CDC 方案 | 阶段4用 outbox，Canal 列后续升级 | 当前量级 outbox 够用，Canal 需额外运维成本 |
| 公共代码 | common 模块（非独立服务） | 避免重复，但只放真正共享的代码 |
| ShardingSphere | 保留现有配置 | 拆分后各服务各连各的数据源 |

---

## 5. 风险与边界

- **不引入 Spring Cloud 全家桶**：只用 Gateway，不引入 Nacos/Consul（初期用硬编码 URL，后续需要服务发现再加）
- **数据一致性**：购票成功后同步落库（阶段2）→ outbox + Canal（阶段4）；延时关单双保险（MQ + 定时任务），UPDATE WHERE status='UNPAID' 防重
- **延时消息误差**：RocketMQ delayLevel=16 对应 30min，实际投递可能有秒到十余秒偏差，关单以 expire_time 为准，消息只是触发器
- **表结构不变**：user 表分片策略不变；ticket 相关表保持现有结构；order 表新建
- **不碰 RocketMQ 配置**：保留现有配置，仅新增延时消息 topic 和 order 消费组
- **支付为模拟实现**：不接入真实支付渠道，`PUT /order/{id}/pay` 直接改状态
