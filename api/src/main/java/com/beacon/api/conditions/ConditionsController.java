package com.beacon.api.conditions;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conditions")
public class ConditionsController {

    private final ConditionsService conditionsService;

    public ConditionsController(ConditionsService conditionsService) {
        this.conditionsService = conditionsService;
    }

    @GetMapping("/now")
    public ConditionSnapshot now() {
        return conditionsService.currentSnapshot();
    }
}
