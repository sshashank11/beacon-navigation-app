package com.beacon.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id and puts it in the logging context.
 *
 * <p>Work started by one request finishes in three places: this process, a
 * Redis queue, and a Python worker. Without a shared id, correlating a route
 * someone complained about with the inference that produced its numbers means
 * guessing from timestamps.
 *
 * <p>An incoming id is honoured so a proxy or a client retry can be followed
 * across hops rather than restarting the trail.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = resolve(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled, so leaving this set would attribute the next
            // request's logs to this one.
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        if (incoming == null || incoming.isBlank() || incoming.length() > 64) {
            return UUID.randomUUID().toString();
        }
        // Only characters that cannot break a log line or a queue payload.
        return incoming.replaceAll("[^A-Za-z0-9_.:-]", "");
    }

    /** The current request id, for attaching to queued work. */
    public static String current() {
        String requestId = MDC.get(MDC_KEY);
        return requestId == null ? "unknown" : requestId;
    }
}
