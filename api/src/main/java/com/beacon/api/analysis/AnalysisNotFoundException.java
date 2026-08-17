package com.beacon.api.analysis;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class AnalysisNotFoundException extends RuntimeException {

    public AnalysisNotFoundException(UUID analysisId) {
        super("Unknown analysis " + analysisId);
    }
}
