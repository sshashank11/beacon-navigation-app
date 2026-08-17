package com.beacon.api.analysis;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands unscored images to the Python worker over a Redis list.
 *
 * <p>The API never calls the worker synchronously. Inference is seconds per
 * image, so putting it in the request path would make requesting an analysis
 * feel broken. A queue push is the whole handoff.
 */
@Component
public class AnalysisQueue {

    public static final String QUEUE_KEY = "beacon:analysis:queue";
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisQueue.class);

    private final StringRedisTemplate redis;

    public AnalysisQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Enqueues image ids for scoring.
     *
     * <p>A queue failure must not fail the request: the frames are already
     * persisted, the offline scoring job covers the same images, and the
     * stream degrades to serving whatever is scored rather than erroring.
     */
    public void enqueue(List<String> mapillaryIds) {
        if (mapillaryIds.isEmpty()) {
            return;
        }
        try {
            redis.opsForList().rightPushAll(QUEUE_KEY, mapillaryIds);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Could not enqueue {} image(s) for scoring; "
                            + "the offline scoring job will still pick them up",
                    mapillaryIds.size(),
                    exception);
        }
    }
}
