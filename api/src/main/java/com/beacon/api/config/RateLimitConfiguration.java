package com.beacon.api.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {

    /**
     * Registers the limiter ahead of Spring Security.
     *
     * <p>Security runs at {@code -100} by default. Sitting in front of it means
     * a flood of unauthenticated requests is turned away before authentication
     * work happens, and authenticated callers are still counted because the
     * limiter falls back to the client address when there is no principal yet.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration() {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/api/v1/*");
        return registration;
    }
}
