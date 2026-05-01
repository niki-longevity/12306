package com.project.ticket.config;

import com.project.common.utils.BaseContext;
import com.project.ticket.utils.JwtUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class JwtAuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String token = req.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token != null && JwtUtil.validateToken(token)) {
            BaseContext.setCurrentId(JwtUtil.getUserId(token));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            BaseContext.removeCurrentId();
        }
    }
}
