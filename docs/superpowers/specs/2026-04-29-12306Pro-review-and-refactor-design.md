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
- `entity/Order`：id, userId, date, trainCode, startStation, endStation, seatType, carriageNum, seatNum, status, createTime
- `entity/OrderPassenger`：id, orderId, realName, idCard
- `mapper/OrderMapper` + `mapper/OrderPassengerMapper`
- 建表 DDL（放在 `docs/` 目录）

#### 3.6 订单 Service & Controller
- `OrderService`：create(购票成功后调用)、findByUser、findById、cancel
- `OrderController`：`GET /order/list`、`GET /order/{id}`、`PUT /order/cancel/{id}`
- `TicketBuyServiceImpl` 购票成功后调用 `OrderService.create()` 同步落库（RocketMQ 在拆分后启用）

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
6. ticket-service 购票成功后通过 RocketMQ 发送消息，order-service 消费

#### 3.10 拆分后各模块依赖

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
| 订单落库 | 阶段2同步 → 阶段4切MQ | 先在单体中验证逻辑，拆分后再切异步 |
| 公共代码 | common 模块（非独立服务） | 避免重复，但只放真正共享的代码 |
| ShardingSphere | 保留现有配置 | 拆分后各服务各连各的数据源 |

---

## 5. 风险与边界

- **不引入 Spring Cloud 全家桶**：只用 Gateway，不引入 Nacos/Consul（初期用硬编码 URL，后续需要服务发现再加）
- **数据一致性**：购票成功后同步落库（阶段2）→ 异步落库（阶段4）；订单状态"待确认→已确认"需对账机制
- **表结构不变**：user 表分片策略不变；ticket 相关表保持现有结构；order 表新建
- **不碰 RocketMQ 配置**：保留现有配置，仅新增 order 消费组
