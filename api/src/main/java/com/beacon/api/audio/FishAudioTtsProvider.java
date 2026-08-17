package com.beacon.api.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streaming text-to-speech over Fish Audio's WebSocket API.
 *
 * <p>There is no official Java SDK, so this assembles the protocol directly:
 * MessagePack frames over an authenticated WebSocket. The event sequence is
 * {@code start} with the voice configuration, then {@code text}, then
 * {@code flush} to force synthesis of what has been sent rather than waiting
 * for more, then {@code stop}.
 *
 * <p>Synthesis happens when a route is built, never while someone is walking
 * it, so blocking here is acceptable and a mid-turn network call is not.
 */
public class FishAudioTtsProvider implements TtsProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(FishAudioTtsProvider.class);
    private static final String ENDPOINT = "wss://api.fish.audio/v1/tts/live";
    private static final String AUDIO_FORMAT = "mp3";
    private static final long TIMEOUT_SECONDS = 30;

    private final ObjectMapper messagePack = new ObjectMapper(new MessagePackFactory());
    private final OkHttpClient client;
    private final TtsProperties properties;

    public FishAudioTtsProvider(OkHttpClient client, TtsProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return properties.fishApiKey() != null && !properties.fishApiKey().isBlank();
    }

    @Override
    public String voiceId() {
        String reference = properties.fishReferenceId();
        return "fish:" + properties.fishModel() + ":"
                + (reference == null || reference.isBlank() ? "default" : reference);
    }

    @Override
    public SpeechClip synthesize(String text) {
        if (!isAvailable()) {
            throw new IllegalStateException("FISH_AUDIO_KEY is not configured");
        }

        Request request = new Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer " + properties.fishApiKey())
                .header("model", properties.fishModel())
                .build();

        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        WebSocket socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                try {
                    webSocket.send(frame(startEvent(text)));
                    webSocket.send(frame(Map.of("event", "text", "text", text)));
                    // Without an explicit flush the service waits for more
                    // text before synthesising anything.
                    webSocket.send(frame(Map.of("event", "flush")));
                    webSocket.send(frame(Map.of("event", "stop")));
                } catch (IOException exception) {
                    failure.set("Could not encode request: " + exception.getMessage());
                    finished.countDown();
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event =
                            messagePack.readValue(bytes.toByteArray(), Map.class);
                    String name = String.valueOf(event.get("event"));
                    if ("audio".equals(name)) {
                        Object chunk = event.get("audio");
                        if (chunk instanceof byte[] data) {
                            audio.write(data);
                        }
                    } else if ("finish".equals(name)) {
                        String reason = String.valueOf(event.getOrDefault("reason", "stop"));
                        if (!"stop".equals(reason)) {
                            failure.set("Fish Audio finished with reason " + reason);
                        }
                        finished.countDown();
                    } else if ("log".equals(name)) {
                        LOGGER.debug("Fish Audio log: {}", event.get("message"));
                    }
                } catch (IOException exception) {
                    failure.set("Could not decode response: " + exception.getMessage());
                    finished.countDown();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
                failure.set(throwable.getMessage());
                finished.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                finished.countDown();
            }
        });

        try {
            if (!finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                failure.set("Fish Audio did not respond within " + TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while synthesising speech", interrupted);
        } finally {
            socket.close(1000, "done");
        }

        if (failure.get() != null) {
            throw new TtsUnavailableException(failure.get());
        }
        if (audio.size() == 0) {
            throw new TtsUnavailableException("Fish Audio returned no audio");
        }
        return new SpeechClip(audio.toByteArray(), "audio/mpeg");
    }

    private Map<String, Object> startEvent(String text) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("text", text);
        request.put("format", AUDIO_FORMAT);
        request.put("latency", "normal");
        if (properties.fishReferenceId() != null && !properties.fishReferenceId().isBlank()) {
            request.put("reference_id", properties.fishReferenceId());
        }
        Map<String, Object> prosody = new LinkedHashMap<>();
        prosody.put("speed", properties.speed());
        request.put("prosody", prosody);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "start");
        event.put("request", request);
        return event;
    }

    private ByteString frame(Map<String, ?> event) throws IOException {
        return ByteString.of(messagePack.writeValueAsBytes(event));
    }
}
