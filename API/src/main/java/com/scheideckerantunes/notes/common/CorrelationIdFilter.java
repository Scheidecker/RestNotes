package com.scheideckerantunes.notes.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives errors a bounded safe correlation identifier without logging request bodies.
 *
 * @author Carlos Scheidecker
 */
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlation = request.getHeader(HEADER);
        if (correlation == null || !correlation.matches("[A-Za-z0-9._-]{1,64}")) {
            correlation = UUID.randomUUID().toString();
        }
        request.setAttribute(HEADER, correlation);
        response.setHeader(HEADER, correlation);
        chain.doFilter(request, response);
    }
}
