package com.beacon.api.routing;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routes")
@ConditionalOnProperty(prefix = "beacon.routing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RouteFeedbackController {

    private final RouteFeedbackService feedback;

    public RouteFeedbackController(RouteFeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping("/{routeId}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteFeedbackResponse submit(
            @PathVariable UUID routeId,
            @Valid @RequestBody RouteFeedbackRequest request
    ) {
        return feedback.submit(routeId, request);
    }
}
