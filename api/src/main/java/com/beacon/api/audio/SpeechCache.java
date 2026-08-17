package com.beacon.api.audio;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores synthesised speech in object storage, keyed by its content.
 *
 * <p>Turn instructions repeat enormously across routes: "Turn left onto
 * Broadway" is the same audio every time. Keying on a hash of the normalised
 * text plus the voice and speed means the second route that says it pays
 * nothing, which is what makes a metered voice API affordable. Changing voice
 * or speed changes the key, so stale audio is never served for new settings.
 */
public class SpeechCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpeechCache.class);

    private final MinioClient minio;
    private final String bucket;

    public SpeechCache(MinioClient minio, String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    /** Hash of what was said, in which voice, at which speed. */
    public static String cacheKey(String text, String voiceId, double speed) {
        String normalized = normalize(text);
        String material = normalized + "|" + voiceId + "|" + speed;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    /**
     * Collapses differences that do not change how a line sounds, so trivial
     * whitespace or casing variation still hits the same cached clip.
     */
    static String normalize(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    public Optional<SpeechClip> find(String key, String contentType) {
        try (InputStream stream = minio.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectName(key))
                .build())) {
            return Optional.of(new SpeechClip(stream.readAllBytes(), contentType));
        } catch (ErrorResponseException notFound) {
            return Optional.empty();
        } catch (Exception exception) {
            // A cache that cannot be read is a performance problem, not a
            // correctness one: fall through and synthesise again.
            LOGGER.warn("Could not read cached speech {}: {}", key, exception.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, SpeechClip clip) {
        try {
            ensureBucket();
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName(key))
                    .contentType(clip.contentType())
                    .stream(new ByteArrayInputStream(clip.audio()), clip.sizeBytes(), -1)
                    .build());
        } catch (Exception exception) {
            LOGGER.warn("Could not cache speech {}: {}", key, exception.getMessage());
        }
    }

    private void ensureBucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private static String objectName(String key) {
        // Two levels of prefix keep listings usable once this grows.
        return "speech/" + key.substring(0, 2) + "/" + key + ".audio";
    }
}
