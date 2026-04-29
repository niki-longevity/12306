package com.project.interceptor;

import com.project.utils.BaseContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class UserLoginInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 从 Header 中获取令牌
        String token = request.getHeader("token");

        // 2. 校验令牌是否存在
        if (token == null || token.isEmpty()) {
            respond401(response);
            log.info("被拦截1...");
            return false; // 拦截
        }

        // 3. 去 Redis 查这个令牌是否有效
        // 假设 Key 是 "login_token:" + token
        String userId = redisTemplate.opsForValue().get("user_token:" + token);

        if (userId == null) {
            // Redis 里查不到，说明过期了或者伪造的
            respond401(response);
            log.info("被拦截2...");
            return false; // 拦截
        }

        // 4. 【重要】令牌续期（自动保活）
        // 只要用户在操作，就重置过期时间为 2 小时，避免用户用着用着掉线
        redisTemplate.expire("login_token:" + token, 2, TimeUnit.HOURS);

        // 5. 登录成功，将 用户ID 存入 ThreadLocal
        BaseContext.setCurrentId(Long.valueOf(userId));

        // 5. 放行
        log.info("权限校验通过，用户id为：{}", Long.valueOf(userId));
        return true;
    }

    // 辅助方法：返回 401 状态码给前端
    private void respond401(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"code\":0, \"msg\":\"NOT_LOGIN\"}");
    }
}