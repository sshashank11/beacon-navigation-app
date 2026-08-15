package com.beacon.api.hazards;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hazard-fields")
public class HazardFieldController {

    private final HazardFieldService hazardFieldService;

    public HazardFieldController(HazardFieldService hazardFieldService) {
        this.hazardFieldService = hazardFieldService;
    }

    @GetMapping("/current")
    public List<HazardArea> current() {
        return hazardFieldService.currentAreaSummaries();
    }
}
