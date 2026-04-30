# Phase 4: Microservice Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the monolith into 4 services (user, ticket, order, gateway) + common shared module using Maven multi-module.

**Architecture:** Parent POM (packaging=pom) with 5 sub-modules. common has no main class — it's a library. Each service has its own Spring Boot main class on a different port. Services communicate via HTTP (no Feign yet). Gateway handles JWT validation and routing.

**Tech Stack:** Spring Boot 3.5.10, Java 21, Spring Cloud Gateway, Maven multi-module

---

## File Map (Target Structure)

```
12306Pro/
├── pom.xml                          (parent, packaging=pom)
├── common/
│   └── pom.xml                      (no parent-pom reference needed beyond project parent)
│   └── src/main/java/com/project/common/
│       ├── result/Result.java
│       ├── exception/BaseException.java
│       ├── exception/GlobalExceptionHandler.java
│       ├── pojo/entity/User.java
│       ├── pojo/entity/UsernamePhone.java
│       ├── pojo/entity/Order.java
│       ├── pojo/entity/OrderPassenger.java
│       ├── pojo/dto/UserLoginDTO.java
│       ├── pojo/dto/UserRegisterDTO.java
│       ├── pojo/vo/UserLoginVO.java
│       └── utils/BaseContext.java
├── user-service/
│   └── pom.xml
│   └── src/main/java/com/project/user/
│       ├── UserServiceApplication.java
│       ├── config/SecurityConfig.java
│       ├── config/RBloomFilterConfig.java
│       ├── controller/UserController.java
│       ├── service/UserService.java
│       ├── service/impl/UserServiceImpl.java
│       ├── mapper/UserMapper.java
│       ├── mapper/UsernamePhoneMapper.java
│       ├── utils/JwtUtil.java
│       ├── utils/LoginIdentityUtils.java
│       ├── utils/LoginType.java
│       └── resources/application.yml
├── ticket-service/
│   └── pom.xml
│   └── src/main/java/com/project/ticket/
│       ├── TicketServiceApplication.java
│       ├── config/CacheConfig.java
│       ├── config/WebMvcConfiguration.java (JWT filter, internal)
│       ├── controller/TicketController.java
│       ├── service/TicketBuyService.java
│       ├── service/TicketGetService.java
│       ├── service/impl/TicketBuyServiceImpl.java
│       ├── service/impl/TicketGetServiceImpl.java
│       ├── mapper/*.java (ticket-related mappers)
│       ├── handler/** (validation chain)
│       ├── cache/warmup/** (cache warmup classes)
│       ├── pojo/bo/TicketListBO.java
│       ├── pojo/dto/TicketBuyDTO.java
│       ├── pojo/dto/TicketListDTO.java
│       ├── pojo/vo/TicketListVO.java
│       ├── utils/TicketValidateContext.java
│       └── resources/application.yml
│       └── resources/lua/*.lua
├── order-service/
│   └── pom.xml
│   └── src/main/java/com/project/order/
│       ├── OrderServiceApplication.java
│       ├── controller/OrderController.java
│       ├── service/OrderService.java
│       ├── service/impl/OrderServiceImpl.java
│       ├── mapper/OrderMapper.java
│       ├── mapper/OrderPassengerMapper.java
│       ├── mq/OrderCloseConsumer.java
│       ├── task/OrderCloseScheduler.java
│       └── resources/application.yml
├── gateway/
│   └── pom.xml
│   └── src/main/java/com/project/gateway/
│       ├── GatewayApplication.java
│       └── resources/application.yml
```

---

### Task 1: Create parent POM and common module

- [ ] Convert parent pom.xml from jar to pom packaging
- [ ] Add `<modules>` section
- [ ] Create `common/` directory with its own pom.xml (no spring-boot-maven-plugin, no main class)
- [ ] Move shared classes to common: Result, BaseException, GlobalExceptionHandler, shared entities (User, UsernamePhone, Order, OrderPassenger), shared DTOs (UserLoginDTO, UserRegisterDTO, TicketListDTO, TicketBuyDTO), shared VOs (UserLoginVO, TicketListVO), BaseContext, TicketValidateContext
- [ ] Update package from `com.project.*` to `com.project.common.*`
- [ ] Delete moved files from original src/main/java/com/project/
- [ ] Compile and commit

### Task 2: Create user-service module

- [ ] Create `user-service/` with pom.xml (depends on common)
- [ ] Move/copy user-related files to user-service with package `com.project.user.*`
- [ ] Create `UserServiceApplication.java` (main class, port 8081)
- [ ] Create application.yml with port, datasource, redis config
- [ ] Keep JwtUtil in user-service (JWT generation belongs to auth service)
- [ ] Compile and commit

### Task 3: Create ticket-service module

- [ ] Create `ticket-service/` with pom.xml (depends on common)
- [ ] Move ticket-related files to ticket-service with package `com.project.ticket.*`
- [ ] Create `TicketServiceApplication.java` (main class, port 8082)
- [ ] Keep lua files, cache config, handlers
- [ ] Add JWT filter (dependency on user-service for JwtUtil? Or duplicate JwtUtil? → Keep JWT parsing in common or duplicate simple validator)
- [ ] Compile and commit

### Task 4: Create order-service module

- [ ] Create `order-service/` with pom.xml (depends on common)
- [ ] Move order-related files to order-service with package `com.project.order.*`
- [ ] Create `OrderServiceApplication.java` (main class, port 8083)
- [ ] Move OrderCloseConsumer, OrderCloseScheduler
- [ ] Compile and commit

### Task 5: Create gateway module

- [ ] Create `gateway/` with pom.xml (spring-cloud-gateway, jjwt)
- [ ] Create `GatewayApplication.java` with JWT filter
- [ ] Configure routes to user/ticket/order services
- [ ] Compile and commit

### Task 6: Integration build and test

- [ ] Full `mvn compile` from parent
- [ ] Full `mvn test`
- [ ] Verify each service starts on its own port
- [ ] Commit any fixes
