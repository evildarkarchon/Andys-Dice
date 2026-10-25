package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;

/**
 * One Notice: something the Player should know that isn't worth interrupting them for (spec §3.2 "Notices and
 * confirmations"). It never pops up; it waits behind the bell with the others ({@link Notices}).
 *
 * <p>Plain data; safe on any thread.
 *
 * @param title one short line naming what happened ("No audio device")
 * @param detail what it means for the Player, or what they can do, in a sentence or two; empty if the title says
 *     it all
 */
record Notice(String title, String detail) {

    /**
     * Checks the parts.
     *
     * @throws NullPointerException if either is null
     * @throws IllegalArgumentException if the title is blank
     */
    Notice {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");
        if (title.isBlank()) {
            throw new IllegalArgumentException("a Notice needs a title");
        }
    }
}
