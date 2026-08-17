package com.beacon.api.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Local speech synthesis with Piper.
 *
 * <p>Exists so a Fish Audio outage, or exhausted credits, degrades to a
 * robotic-but-working voice instead of silence. Piper is a separate binary
 * rather than a library, so this shells out and reports honestly when it is
 * not installed.
 */
public class PiperTtsProvider implements TtsProvider {

    private static final long TIMEOUT_SECONDS = 30;

    private final TtsProperties properties;

    public PiperTtsProvider(TtsProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        String binary = properties.piperBinary();
        if (binary == null || binary.isBlank()) {
            return false;
        }
        Path path = Path.of(binary);
        // An absolute path must exist; a bare command is left to PATH lookup.
        return !path.isAbsolute() || Files.isExecutable(path);
    }

    @Override
    public String voiceId() {
        String voice = properties.piperVoice();
        return "piper:" + (voice == null || voice.isBlank() ? "default" : voice);
    }

    @Override
    public SpeechClip synthesize(String text) {
        if (!isAvailable()) {
            throw new TtsUnavailableException("Piper is not configured on this host");
        }

        Path output = null;
        try {
            output = Files.createTempFile("beacon-tts-", ".wav");
            List<String> command = new java.util.ArrayList<>(List.of(
                    properties.piperBinary(),
                    "--output_file", output.toString()));
            if (properties.piperVoice() != null && !properties.piperVoice().isBlank()) {
                command.add("--model");
                command.add(properties.piperVoice());
            }

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().close();

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new TtsUnavailableException("Piper timed out");
            }
            if (process.exitValue() != 0) {
                throw new TtsUnavailableException("Piper exited with " + process.exitValue());
            }
            return new SpeechClip(Files.readAllBytes(output), "audio/wav");
        } catch (IOException exception) {
            throw new TtsUnavailableException("Piper failed: " + exception.getMessage(), exception);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TtsUnavailableException("Interrupted while running Piper", interrupted);
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing a request over.
                }
            }
        }
    }
}
