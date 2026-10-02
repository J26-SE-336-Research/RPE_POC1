package com.rrpe.orderservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@Component
public final class RequestPropagationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestPropagationFilter.class);
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader("Request_id");
        boolean cancellationTriggered = Boolean.parseBoolean(request.getHeader("cancellation_Triggered"));
        if (cancellationTriggered) {
            log.warn("order-service received cancellation_Triggered=true for Request_id={}", requestId);
        }
        RequestPropagationContext.set(requestId);
        if (requestId != null && !requestId.isBlank()) response.setHeader("Request_id", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestPropagationContext.clear();
        }
    }
}
