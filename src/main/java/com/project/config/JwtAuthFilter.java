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
