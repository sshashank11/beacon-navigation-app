package com.beacon.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitFilterTest {

    private static HttpServletRequest request(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }

    @Test
    void registrationIsLimitedSoAccountsCannotBeMassCreated() {
        RateLimitFilter.Limit limit = RateLimitFilter.limitFor(request("/api/v1/auth/register"));

        assertThat(limit).isNotNull();
        assertThat(limit.name()).isEqualTo("registration");
        // Per hour, not per minute: a per-minute cap still allows hundreds a day.
        assertThat(limit.window()).isEqualTo(Duration.ofHours(1));
        assertThat(limit.capacity()).isLessThanOrEqualTo(10);
    }

    @Test
    void analysisIsCappedHardestBecauseItQueuesInference() {
        RateLimitFilter.Limit analysis =
                RateLimitFilter.limitFor(request("/api/v1/routes/abc/analysis"));
        RateLimitFilter.Limit routing =
                RateLimitFilter.limitFor(request("/api/v1/routes/compare"));

        assertThat(analysis.capacity()).isLessThan(routing.capacity());
    }

    @Test
    void routingAudioAndAnalysisReadsAreAllCovered() {
        assertThat(RateLimitFilter.limitFor(request("/api/v1/routes"))).isNotNull();
        assertThat(RateLimitFilter.limitFor(request("/api/v1/routes/compare"))).isNotNull();
        assertThat(RateLimitFilter.limitFor(request("/api/v1/routes/x/audio"))).isNotNull();
        assertThat(RateLimitFilter.limitFor(request("/api/v1/analysis/x"))).isNotNull();
        assertThat(RateLimitFilter.limitFor(request("/api/v1/analysis/x/stream"))).isNotNull();
    }

    @Test
    void ambientDataAndSignInAreNotLimited() {
        // These are cheap and describe the city rather than a person; limiting
        // them would break the banner without protecting anything.
        assertThat(RateLimitFilter.limitFor(request("/api/v1/conditions/now"))).isNull();
        assertThat(RateLimitFilter.limitFor(request("/api/v1/tiles/hazard/pm25/14/1/1.mvt"))).isNull();
        assertThat(RateLimitFilter.limitFor(request("/api/v1/auth/me"))).isNull();
    }

    @Test
    void aMissingUriIsNotLimitedRatherThanCrashing() {
        MockHttpServletRequest bare = new MockHttpServletRequest();
        bare.setRequestURI(null);

        assertThat(RateLimitFilter.limitFor(bare)).isNull();
    }
}
