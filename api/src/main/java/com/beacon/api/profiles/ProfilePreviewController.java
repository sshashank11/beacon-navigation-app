package com.beacon.api.profiles;

import com.beacon.api.routing.RouteComparisonRequest;
import com.beacon.api.routing.RouteComparisonResponse;
import com.beacon.api.routing.RouteComparisonService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles")
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProfilePreviewController {

    private final RouteComparisonService routeComparisons;

    public ProfilePreviewController(RouteComparisonService routeComparisons) {
        this.routeComparisons = routeComparisons;
    }

    @PostMapping("/preview")
    public RouteComparisonResponse preview(
            @Valid @RequestBody RouteComparisonRequest request
    ) {
        return routeComparisons.compare(request);
    }
}
