package com.beacon.api.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps how often the expensive endpoints can be called.
 *
 * <p>Routing runs a search over a million-edge graph, and an analysis request
 * can queue image inference, which costs real compute and can cost real money.
 * Neither should be free to call in a loop.
 *
 * <p>Limits are per caller: the account when there is one, otherwise the
 * client address, so one signed-in user cannot exhaust everyone else's budget
 * and an anonymous flood is confined to its own source.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Generous enough for real use, small enough to stop a script. */
    private static final int ROUTE_REQUESTS_PER_MINUTE = 30;
    private static final int ANALYSIS_REQUESTS_PER_MINUTE = 6;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter() {
        LOGGER.info(
                "Rate limiting active: {}/min on routing and audio, {}/min on analysis",
                ROUTE_REQUESTS_PER_MINUTE,
                ANALYSIS_REQUESTS_PER_MINUTE);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        Integer limit = limitFor(request);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = limit + ":" + callerKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> newBucket(limit));
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"Too many requests. Try again in a minute.\"}");
    }

    /** Null means this path is not limited. */
    private static Integer limitFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        if (path.endsWith("/analysis") || path.startsWith("/api/v1/analysis/")) {
            return ANALYSIS_REQUESTS_PER_MINUTE;
        }
        if (path.equals("/api/v1/routes") || path.equals("/api/v1/routes/compare")
                || path.endsWith("/audio")) {
            return ROUTE_REQUESTS_PER_MINUTE;
        }
        return null;
    }

    private static String callerKey(HttpServletRequest request) {
        String user = request.getRemoteUser();
        return user != null ? "user:" + user : "ip:" + request.getRemoteAddr();
    }

    private static Bucket newBucket(int perMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(perMinute)
                        .refillGreedy(perMinute, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
