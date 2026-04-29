# Phase 1: Functional Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all broken/incomplete functionality in the existing monolith so the code actually runs end-to-end.

**Architecture:** Add JWT auth (replacing broken Redis-token interceptor), fix user service NPE and Bloom filter gaps, re-enable ticket service validation chain and @Service registration, extract Lua scripts from Java strings to files, fix class/package naming.

**Tech Stack:** Spring Boot 3.5.10, Java 21, jjwt 0.12.6, MyBatis-Plus, Redis

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `pom.xml` | Modify | Add jjwt dependencies |
| `src/main/java/com/project/utils/JwtUtil.java` | Create | JWT generate/validate/parse |
| `src/main/java/com/project/config/JwtAuthFilter.java` | Create | Filter: extract token → validate → set context |
| `src/main/java/com/project/config/WebMvcConfiguration.java` | Modify | Register JWT filter, remove old interceptor |
| `src/main/java/com/project/interceptor/UserLoginInterceptor.java` | Delete | Replaced by JwtAuthFilter |
| `src/main/java/com/project/service/Impl/UserServiceImpl.java` | Modify | Fix NPE, bloom add, return JWT |
| `src/main/java/com/project/service/Impl/TicketBuyServiceImpl.java` | Modify | @Service, enable chain |
| `src/main/java/com/project/service/Impl/TicketGetGetServiceImpl.java` | Delete | Rename |
| `src/main/java/com/project/service/impl/TicketGetServiceImpl.java` | Create | Renamed + DB fallback enabled |
| `src/main/resources/lua/ticket_buy.lua` | Create | Extracted Lua |
| `src/main/resources/lua/token_bucket.lua` | Create | Extracted Lua |
| `src/main/resources/application.yml` | Modify | Add JWT config |

---

### Task 1: Add jjwt dependency

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add jjwt dependencies to pom.xml**

In `pom.xml`, after the `<!-- pom.xml 引入依赖 -->` comment block (before `commons-lang3`), add:

```xml
<!-- JWT -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: Verify Maven resolves the dependency**

Run: `mvn dependency:resolve -DincludeArtifactIds=jjwt-api 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat: add jjwt 0.12.6 dependencies for JWT auth"
```

---

### Task 2: Create JwtUtil

**Files:**
- Create: `src/main/java/com/project/utils/JwtUtil.java`

- [ ] **Step 1: Create JwtUtil.java**

```java
package com.project.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtUtil {

    private static final long EXPIRATION_MS = 2 * 60 * 60 * 1000L; // 2 hours
    private static final String SECRET_PROP = System.getProperty("JWT_SECRET", "12306Pro-jwt-secret-key-min-256bits!!");
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET_PROP.getBytes(StandardCharsets.UTF_8));

    public static String generateToken(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + EXPIRATION_MS))
                .signWith(SECRET_KEY)
                .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(SECRET_KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public static Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    public static String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }
}
```

- [ ] **Step 2: Compile check**

Run: `mvn compile -pl . 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/project/utils/JwtUtil.java
git commit -m "feat: add JwtUtil for JWT token generation and validation"
```

---

### Task 3: Create JwtAuthFilter and update WebMvcConfig

**Files:**
- Create: `src/main/java/com/project/config/JwtAuthFilter.java`
- Modify: `src/main/java/com/project/config/WebMvcConfiguration.java`
- Delete: `src/main/java/com/project/interceptor/UserLoginInterceptor.java`

- [ ] **Step 1: Create JwtAuthFilter.java**

```java
package com.project.config;

import com.project.utils.BaseContext;
import com.project.utils.JwtUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class JwtAuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String path = req.getRequestURI();
        // Skip public endpoints
        if (path.equals("/user/login") || path.equals("/user/add")) {
            chain.doFilter(request, response);
            return;
        }

        String token = req.getHeader("Authorization");
        if (token == null || token.isEmpty()) {
            respond401(res, "NOT_LOGIN");
            return;
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        if (!JwtUtil.validateToken(token)) {
            respond401(res, "TOKEN_INVALID");
            return;
        }

        Long userId = JwtUtil.getUserId(token);
        BaseContext.setCurrentId(userId);
        log.debug("JWT auth passed, userId={}", userId);

        try {
            chain.doFilter(request, response);
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    private void respond401(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"code\":0,\"msg\":\"" + msg + "\"}");
    }
}
```

- [ ] **Step 2: Rewrite WebMvcConfiguration.java**

Replace the entire content:

```java
package com.project.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebMvcConfiguration {

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilter() {
        FilterRegistrationBean<JwtAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new JwtAuthFilter());
        bean.addUrlPatterns("/user/*", "/ticket/*", "/order/*");
        bean.setOrder(1);
        return bean;
    }
}
```

- [ ] **Step 3: Delete UserLoginInterceptor.java**

Run: `rm src/main/java/com/project/interceptor/UserLoginInterceptor.java`

- [ ] **Step 4: Verify BaseContext has removeCurrentId method**

Check `src/main/java/com/project/utils/BaseContext.java`. If `removeCurrentId()` does not exist, read the file and add it:

```java
public static void removeCurrentId() {
    THREAD_LOCAL.remove();
}
```

- [ ] **Step 5: Compile check**

Run: `mvn compile 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/project/config/JwtAuthFilter.java src/main/java/com/project/config/WebMvcConfiguration.java
git rm src/main/java/com/project/interceptor/UserLoginInterceptor.java
git commit -m "feat: replace UserLoginInterceptor with JwtAuthFilter"
```

---

### Task 4: Fix UserServiceImpl — NPE, Bloom filter, JWT

**Files:**
- Modify: `src/main/java/com/project/service/Impl/UserServiceImpl.java`

- [ ] **Step 1: Read current file and verify line numbers**

Read `src/main/java/com/project/service/Impl/UserServiceImpl.java` to confirm the code matches the plan. If different, adapt.

- [ ] **Step 2: Fix login() — add NPE guard and return JWT**

Replace the `login` method body (lines 44-97) with:

```java
@Override
public UserLoginVO login(UserLoginDTO userLoginDTO) {
    String loginId = userLoginDTO.getLoginId();

    // 1. 判断登录类型（优先手机号）
    LoginType loginType = LoginIdentityUtils.judgeLoginType(loginId);

    // 2. 根据类型查询用户
    LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
    if (LoginType.PHONE.equals(loginType)) {
        wrapper.eq(User::getPhone, loginId);
    } else {
        LambdaQueryWrapper<UsernamePhone> wrapper1 = new LambdaQueryWrapper<>();
        wrapper1.eq(UsernamePhone::getUsername, loginId);
        UsernamePhone usernamePhone = usernamePhoneMapper.selectOne(wrapper1);
        if (usernamePhone == null) {
            throw new AccountNotFoundException("手机号或密码错误");
        }
        wrapper.eq(User::getPhone, usernamePhone.getPhone());
    }
    User user = userMapper.selectOne(wrapper);

    // 3. 判断账号是否存在
    if (user == null) {
        throw new AccountNotFoundException("手机号或密码错误");
    }

    // 4. 校验密码
    if (!userLoginDTO.getPassword().equals(user.getPassword())) {
        throw new PasswordErrorException("手机号或密码错误");
    }

    // 5. 生成 JWT token
    String token = JwtUtil.generateToken(user.getId(), user.getUsername());

    // 6. 封装返回结果 VO
    return UserLoginVO.builder()
            .id(user.getId())
            .username(user.getUsername())
            .token(token)
            .build();
}
```

- [ ] **Step 3: Add JwtUtil import**

Add to the imports at top:
```java
import com.project.utils.JwtUtil;
```

- [ ] **Step 4: Remove unused UUID import**

Remove `import java.util.UUID;` (no longer needed).

- [ ] **Step 5: Fix add() — bloom filter add after insert**

In the `add` method, after `userMapper.insert(user);` add:
```java
usernameBloomFilter.add(username);
```

Also insert into routing table:
```java
UsernamePhone up = new UsernamePhone();
up.setUsername(username);
up.setPhone(user.getPhone());
usernamePhoneMapper.insert(up);
```

The complete `add` method:
```java
@Override
public void add(UserRegisterDTO userRegisterDTO) {
    String username = userRegisterDTO.getUsername();

    // 布隆过滤器判断用户名是否已存在
    if (usernameBloomFilter.contains(username)) {
        throw new UsernameRepeatException("用户名已存在，无法注册");
    }

    User user = new User();
    BeanUtils.copyProperties(userRegisterDTO, user);
    user.setCreateTime(LocalDateTime.now());

    // 插入数据库
    userMapper.insert(user);

    // 注册成功后：布隆过滤器+路由表
    usernameBloomFilter.add(username);
    UsernamePhone up = new UsernamePhone();
    up.setUsername(username);
    up.setPhone(user.getPhone());
    usernamePhoneMapper.insert(up);
}
```

- [ ] **Step 6: Remove unused imports**

Remove:
- `import java.util.UUID;`
- `import java.util.concurrent.TimeUnit;`
- `import org.springframework.data.redis.core.StringRedisTemplate;` (if no longer used — check if any other method uses it; remove if not)
- `import org.redisson.api.RBloomFilter;` — keep this one, it's still used for bloom filter

- [ ] **Step 7: Remove StringRedisTemplate field if no longer used**

If `stringRedisTemplate` field is only used for token storage (now removed), delete the field:
```java
private final StringRedisTemplate stringRedisTemplate;
```
And remove from `@RequiredArgsConstructor` injected fields (it's auto, so removing the field removes the injection).

- [ ] **Step 8: Compile check**

Run: `mvn compile 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/project/service/Impl/UserServiceImpl.java
git commit -m "fix: NPE guard in login, bloom filter add in register, JWT token return"
```

---

### Task 5: Fix TicketBuyServiceImpl — @Service, enable validation chain

**Files:**
- Modify: `src/main/java/com/project/service/Impl/TicketBuyServiceImpl.java`

- [ ] **Step 1: Enable @Service annotation**

Line 30: Change `//@Service` to `@Service`

- [ ] **Step 2: Enable validation chain in buy() method**

Lines 169-181: Uncomment the validation chain block. Remove the `//` prefix from each line. The block should be:

```java
// ===== 责任链校验 =====
// 校验参数是否为空、校验车次是否存在、校验车站在车次中是否合法
// 1. 构建校验上下文
TicketValidateContext context = new TicketValidateContext();
context.setTicketBuyDTO(ticketBuyDTO);
// 2. 获取校验链条并执行
AbstractTicketValidateHandler validateChain = ticketValidateChainBuilder.buildChain();
validateChain.handle(context);
// 3. 校验结果判断
if (!context.isPass()) {
    log.error("购票校验失败：{}，参数：{}", context.getErrorMsg(), ticketBuyDTO);
    return context.getErrorMsg();
}
```

- [ ] **Step 3: Add imports for validation chain**

Add to imports:
```java
import com.project.handler.chain.AbstractTicketValidateHandler;
import com.project.utils.TicketValidateContext;
```

- [ ] **Step 4: Read TicketValidateContext to verify it has required methods**

Quick-check `src/main/java/com/project/utils/TicketValidateContext.java` for: `setTicketBuyDTO()`, `isPass()`, `getErrorMsg()`. Add if missing.

- [ ] **Step 5: Compile check**

Run: `mvn compile 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/project/service/Impl/TicketBuyServiceImpl.java
git commit -m "fix: enable @Service and validation chain on TicketBuyServiceImpl"
```

---

### Task 6: Rename TicketGetGetServiceImpl → TicketGetServiceImpl, enable DB fallback

**Files:**
- Create: `src/main/java/com/project/service/impl/TicketGetServiceImpl.java`
- Delete: `src/main/java/com/project/service/Impl/TicketGetGetServiceImpl.java`
- Modify: `src/main/java/com/project/controller/TicketController.java`

- [ ] **Step 1: Create new package `service.impl`**

Run: `mkdir -p src/main/java/com/project/service/impl`

- [ ] **Step 2: Copy and rename the file**

Copy `TicketGetGetServiceImpl.java` to `TicketGetServiceImpl.java` in the new package. Change:
- Package: `com.project.service.Impl` → `com.project.service.impl`
- Class name: `TicketGetGetServiceImpl` → `TicketGetServiceImpl`

- [ ] **Step 3: Enable DB fallback query**

Lines 83-93: Uncomment the fallback block:

```java
List<String> missTrainCodes = trainCodes.stream()
        .filter(code -> !trainBoMap.containsKey(code))
        .toList();
if (!CollectionUtils.isEmpty(missTrainCodes)) {
    log.warn("JVM缓存未命中车次数量：{}，开始降级查询", missTrainCodes.size());
    Map<String, TicketListBO> missTrainBoMap = getTrainBoFromDbWithLimit(date, missTrainCodes);
    // 降级查询结果放入JVM缓存（预热）
    putMissTrainBoToJvmCache(missTrainBoMap);
    // 合并缓存命中+降级结果
    trainBoMap.putAll(missTrainBoMap);
}
```

- [ ] **Step 4: Add missing import for `toList()`**

If `import java.util.stream.Collectors;` doesn't cover `.toList()`, add:
```java
import java.util.List;
```
(The `.toList()` in Java 16+ is from `Stream` directly, no extra import needed.)

- [ ] **Step 5: Update TicketController import**

Change `com.project.service.Impl.TicketGetGetServiceImpl` → `com.project.service.impl.TicketGetServiceImpl` in the import (if it was imported by name — check the actual injection. Since it's injected via interface `TicketGetService`, the import may not be there directly. Verify and fix.)

- [ ] **Step 6: Delete old file**

Run: `rm src/main/java/com/project/service/Impl/TicketGetGetServiceImpl.java`

- [ ] **Step 7: Compile check**

Run: `mvn compile 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/project/service/impl/TicketGetServiceImpl.java
git add src/main/java/com/project/controller/TicketController.java
git rm src/main/java/com/project/service/Impl/TicketGetGetServiceImpl.java
git commit -m "fix: rename TicketGetGetServiceImpl, enable DB fallback query, fix package casing"
```

---

### Task 7: Extract Lua scripts to files

**Files:**
- Create: `src/main/resources/lua/ticket_buy.lua`
- Create: `src/main/resources/lua/token_bucket.lua`
- Modify: `src/main/java/com/project/service/Impl/TicketBuyServiceImpl.java`

- [ ] **Step 1: Create lua directory**

Run: `mkdir -p src/main/resources/lua`

- [ ] **Step 2: Extract ticket_buy.lua**

Create `src/main/resources/lua/ticket_buy.lua`:

```lua
-- 1. 先解析所有ARGV参数
local bitmapKey = KEYS[1]
local stockKey = KEYS[2]
local seatStartBit = tonumber(ARGV[1]) or 0
local userStartSection = tonumber(ARGV[2]) or 0
local userEndSection = tonumber(ARGV[3]) or 0
local totalSectionCount = tonumber(ARGV[4]) or 0
local passengerCount = tonumber(ARGV[5]) or 0
local sectionsStr = ARGV[6] or ''

-- 2. 解析区间列表
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
if #sections == 0 then
    return -2
end

-- 3. 一次性读取该座位的所有bit位
local bitFieldCmd = {'BITFIELD', bitmapKey, 'GET', 'u'..totalSectionCount, seatStartBit}
local seatBitmap = redis.call(unpack(bitFieldCmd))[1] or 0

-- 4. 生成乘客区间的精准掩码
local sectionMask = 0
for i = userStartSection, userEndSection do
    sectionMask = bit.bor(sectionMask, bit.lshift(1, i - 1))
end

-- 5. 判断：仅乘客区间的bit位全为0才算空闲
if bit.band(seatBitmap, sectionMask) ~= 0 then
    return 0
end

-- 6. 一次性设置乘客区间的bit位为1
local newSeatBitmap = bit.bor(seatBitmap, sectionMask)
redis.call('BITFIELD', bitmapKey, 'SET', 'u'..totalSectionCount, seatStartBit, newSeatBitmap)

-- 7. 扣减库存
for _, section in ipairs(sections) do
    redis.call('HINCRBY', stockKey, tostring(section), -passengerCount)
end

return 1
```

- [ ] **Step 3: Extract token_bucket.lua**

Create `src/main/resources/lua/token_bucket.lua`:

```lua
local tokenKey = KEYS[1]
local needToken = tonumber(ARGV[1])
local resetToken = tonumber(ARGV[2])

local currentToken = tonumber(redis.call('GET', tokenKey) or 0)
if currentToken < needToken then
    redis.call('SET', tokenKey, resetToken)
    return -1
end
return redis.call('DECRBY', tokenKey, needToken)
```

- [ ] **Step 4: Update TicketBuyServiceImpl to load Lua from files**

Replace the static block (lines 78-158) with:

```java
static {
    TICKET_BUY_LUA_SCRIPT = new DefaultRedisScript<>();
    TICKET_BUY_LUA_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("lua/ticket_buy.lua"));
    TICKET_BUY_LUA_SCRIPT.setResultType(Long.class);

    TOKEN_BUCKET_LUA_SCRIPT = new DefaultRedisScript<>();
    TOKEN_BUCKET_LUA_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("lua/token_bucket.lua"));
    TOKEN_BUCKET_LUA_SCRIPT.setResultType(Long.class);
}
```

Remove the TODO comment at line 77.

- [ ] **Step 5: Compile check**

Run: `mvn compile 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/lua/ticket_buy.lua
git add src/main/resources/lua/token_bucket.lua
git add src/main/java/com/project/service/Impl/TicketBuyServiceImpl.java
git commit -m "refactor: extract Lua scripts from Java strings to .lua files"
```

---

### Task 8: Add JWT config to application.yml

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add JWT configuration**

Add to `application.yml` under the `spring:` block:

```yaml
jwt:
  secret: ${JWT_SECRET:12306Pro-jwt-secret-key-min-256bits!!}
  expiration-ms: 7200000
```

(Note: the JwtUtil reads from system property via `-DJWT_SECRET=xxx`. This yml config is for documentation/reference. The env variable approach uses `-DJWT_SECRET=xxx` JVM arg at startup.)

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "chore: add JWT config documentation to application.yml"
```

---

### Task 9: Integration check — full compile and review

- [ ] **Step 1: Full compile**

Run: `mvn compile 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 2: Verify all changed imports resolve**

Run: `mvn dependency:analyze 2>&1 | grep -E "WARNING|ERROR" || echo "No issues"`
This may produce warnings about unused declared deps (acceptable at this stage).

- [ ] **Step 3: Review checklist**

Go through each file and confirm:
- [ ] No import of deleted `UserLoginInterceptor`
- [ ] No reference to old `UUID` token generation
- [ ] No reference to `redisTemplate` in UserServiceImpl (unless still used elsewhere)
- [ ] Package `service/impl` exists (lowercase)
- [ ] `@Service` is active on TicketBuyServiceImpl
- [ ] Validation chain code is not commented

- [ ] **Step 4: Commit any remaining fixes**

```bash
git add -A
git diff --cached --stat  # review what's staged
git commit -m "chore: final cleanup after phase 1 fixes"
```

---

## Phase 1 Completion Checklist

- [ ] jjwt dependency added
- [ ] JwtUtil created with generate/validate/parse
- [ ] JwtAuthFilter created and registered
- [ ] UserLoginInterceptor deleted
- [ ] UserServiceImpl: login NPE fixed
- [ ] UserServiceImpl: login returns JWT not Redis token
- [ ] UserServiceImpl: register adds to bloom filter + routing table
- [ ] TicketBuyServiceImpl: @Service enabled
- [ ] TicketBuyServiceImpl: validation chain enabled
- [ ] TicketGetServiceImpl: renamed (no more GetGet)
- [ ] TicketGetServiceImpl: DB fallback uncommented
- [ ] Lua scripts extracted to .lua files
- [ ] Package `service/impl` lowercase
- [ ] Full project compiles
