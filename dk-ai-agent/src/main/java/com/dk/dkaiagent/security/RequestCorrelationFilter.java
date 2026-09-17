package com.dk.dkaiagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

/** Server-issued identifiers only; no request body, cookie or user-supplied log fields. */
@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", id);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", id)) {
            chain.doFilter(request, response);
        }
    }
}
