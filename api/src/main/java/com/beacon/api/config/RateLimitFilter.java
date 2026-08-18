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
 * Caps how often the expensive and abusable endpoints can be called.
 *
 * <p>Routing runs a search over a million-edge graph and an analysis request can
 * queue image inference, which costs real compute and can cost real money.
 * Registration is cheap per call but creates durable rows, so an open endpoint
 * is an invitation to fill the database with accounts.
 *
 * <p>Limits are per caller: the account when there is one, otherwise the client
 * address, so one signed-in user cannot exhaust everyone else's budget and an
 * anonymous flood is confined to its own source.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Generous enough for real use, small enough to stop a script. */
    static final Limit ROUTING = new Limit("routing", 30, Duration.ofMinutes(1));
    static final Limit ANALYSIS = new Limit("analysis", 6, Duration.ofMinutes(1));
    /**
     * Registration is measured per hour rather than per minute. A real person
     * signs up once; a per-minute cap would still allow hundreds of accounts a
     * day from one source.
     */
    static final Limit REGISTRATION = new Limit("registration", 5, Duration.ofHours(1));

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter() {
        LOGGER.info("Rate limiting active: {}, {}, {}", ROUTING, ANALYSIS, REGISTRATION);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        Limit limit = limitFor(request);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = limit.name() + ":" + callerKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> newBucket(limit));
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(limit.window().toSeconds()));
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"Too many requests. Try again later.\"}");
    }

    /** Null means this path is not limited. */
    static Limit limitFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        if (path.equals("/api/v1/auth/register")) {
            return REGISTRATION;
        }
        if (path.endsWith("/analysis") || path.startsWith("/api/v1/analysis/")) {
            return ANALYSIS;
        }
        if (path.equals("/api/v1/routes") || path.equals("/api/v1/routes/compare")
                || path.endsWith("/audio")) {
            return ROUTING;
        }
        return null;
    }

    private static String callerKey(HttpServletRequest request) {
        String user = request.getRemoteUser();
        return user != null ? "user:" + user : "ip:" + request.getRemoteAddr();
    }

    private static Bucket newBucket(Limit limit) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.window())
                        .build())
                .build();
    }

    /** A named allowance: how many calls in how long. */
    record Limit(String name, int capacity, Duration window) {

        @Override
        public String toString() {
            return capacity + " per " + window.toMinutes() + "min on " + name;
        }
    }
}
