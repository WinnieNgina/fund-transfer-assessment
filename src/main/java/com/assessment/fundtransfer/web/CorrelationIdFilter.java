package com.assessment.fundtransfer.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String ATTRIBUTE_NAME = CorrelationIdFilter.class.getName() + ".correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        request.setAttribute(ATTRIBUTE_NAME, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        MDC.put("correlationId", correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }

    public static String getCorrelationId(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE_NAME);
        return value instanceof String correlationId ? correlationId : null;
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String headerValue = request.getHeader(HEADER_NAME);
        if (headerValue != null) {
            String normalized = headerValue.trim();
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return UUID.randomUUID().toString();
    }
}
