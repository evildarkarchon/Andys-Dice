package io.github.evildarkarchon.andysdice.core.tray;

import java.io.IOException;
import java.io.Serial;

/**
 * A {@code .tray} file that can't be read as a Tray at all: its {@code format} is missing, isn't a version number,
 * or is newer than this build knows (ADR-0010, following ADR-0009). Individual bad values never cause it; they fall
 * back to their defaults.
 *
 * <p>An {@link IOException}, so that a caller reading Player Trays handles an unreadable file and an unsupported one
 * in one place, as ADR-0010 treats them alike: skipped, and named in a Notice.
 */
public final class TrayFormatException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates one.
     *
     * @param message what is wrong, naming the Tray's identity
     */
    public TrayFormatException(String message) {
        super(message);
    }
}
