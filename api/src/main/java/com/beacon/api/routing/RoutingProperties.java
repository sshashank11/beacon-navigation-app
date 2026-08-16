package com.beacon.api.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("beacon.routing")
public record RoutingProperties(
        String osmPath,
        String graphPath
) {
}
