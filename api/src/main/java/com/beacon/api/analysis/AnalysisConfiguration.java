package com.beacon.api.analysis;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AnalysisConfiguration {

    /**
     * Virtual threads: each open stream parks on a poll interval far more than
     * it runs, so a platform thread per subscriber would be mostly idle.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService analysisStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
