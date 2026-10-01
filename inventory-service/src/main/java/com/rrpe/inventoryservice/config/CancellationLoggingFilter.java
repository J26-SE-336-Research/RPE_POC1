package com.rrpe.inventoryservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public final class CancellationLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(CancellationLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (Boolean.parseBoolean(request.getHeader("cancellation_Triggered"))) {
            log.warn("inventory-service received cancellation_Triggered=true for Request_id={}",
                    request.getHeader("Request_id"));
        }
        chain.doFilter(request, response);
    }
}
