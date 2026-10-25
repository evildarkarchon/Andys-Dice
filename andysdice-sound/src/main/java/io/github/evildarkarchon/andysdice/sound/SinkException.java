package io.github.evildarkarchon.andysdice.sound;

import java.io.Serial;

/**
 * A sink couldn't be opened or stopped taking sound. Sound is optional, so this never fails the app: the
 * current sound is dropped and the default device is tried again at the next Throw (ADR-0012 "Failure means
 * silence").
 */
public final class SinkException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Makes the exception.
     *
     * @param message what failed
     * @param cause the underlying failure, or {@code null}
     */
    public SinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
