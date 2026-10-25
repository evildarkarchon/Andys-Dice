package io.github.evildarkarchon.andysdice.physics;

import java.io.Serial;

/**
 * Jolt couldn't be started: its native library is missing or wouldn't load, or its process-wide setup failed.
 * The message names the platform and the library (ADR-0003), so the app can fail fast with it, as it does
 * without GL 3.3 (ADR-0002).
 */
public final class JoltStartupException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed, naming the platform and the library
     */
    public JoltStartupException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message what failed, naming the platform and the library
     * @param cause the underlying failure
     */
    public JoltStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
