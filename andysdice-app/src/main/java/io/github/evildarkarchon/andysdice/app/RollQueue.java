package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Queued Roll (spec §3.2 "Entry"): a Roll asked for while Dice move waits behind the current one and starts
 * about {@value #BEAT_SECONDS} s after its Roll Result is revealed. There is at most one; a later request replaces
 * it, and {@link #cancel} (Esc) drops it. Otherwise a request rolls at once. Rolling from the box, a Saved Roll or a
 * Roll History entry all come through {@link #roll}, so they queue the same way.
 *
 * <p>The beat is timed on the playback clock, so tests advance it by hand and Fast-forward shortens it with the
 * rest of playback. Whoever drives the clock must keep it running while a Queued Roll {@link #waiting waits}, even
 * once nothing on the Tray moves ({@link RollSession} keeps the pulse going).
 *
 * <p>A current Roll that fails starts the beat too, from the moment it failed: the Failed Roll's message is shown
 * for the beat before the Queued Roll replaces it. A Queued Roll that fails shows its own Failed Roll message, as any
 * Roll does.
 *
 * <p>Thread contract: the controller's thread (the JavaFX Application Thread), which also advances the clock.
 */
final class RollQueue {

    /** The beat between a reveal and the Queued Roll's start, playback seconds (spec §4: a tunable, about 0.9 s). */
    static final double BEAT_SECONDS = 0.9;

    private final RollController controller;
    private final PlaybackClock clock;
    private Consumer<Optional<String>> onQueuedChanged = queued -> {};
    /** The Queued Roll's text, or {@code null}. */
    private String queued;
    /** The name of the Saved Roll the Queued Roll came from (#56); empty while none is queued. */
    private Optional<String> queuedName = Optional.empty();
    /** When on the clock the Queued Roll starts; NaN until the current Roll has ended. */
    private double startsAt = Double.NaN;
    /** Set once the session has failed ({@link #close}): nothing rolls from then on. */
    private boolean closed;

    /**
     * Creates an empty queue in front of a controller and starts it listening to the clock.
     *
     * @param controller what rolls
     * @param clock the playback clock the beat is timed on
     */
    RollQueue(RollController controller, PlaybackClock clock) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.clock = Objects.requireNonNull(clock, "clock");
        clock.addListener(c -> advanced());
    }

    /**
     * Rolls a Roll Expression the Player asked for: at once if nothing is under way, else as the Queued Roll in
     * place of any earlier one. A request while a Queued Roll waits out its beat replaces it too, rather than
     * jumping ahead of it. The caller has already checked that the text parses (errors never queue).
     *
     * @param text the Roll Expression as typed
     */
    void roll(String text) {
        roll(text, Optional.empty());
    }

    /**
     * Rolls a Roll Expression as {@link #roll(String)} does, carrying the name of the Saved Roll it came from to the
     * controller, so the Roll is recorded with it, even when it waits as the Queued Roll first.
     *
     * @param text the Roll Expression as written
     * @param savedRollName the Saved Roll's name, or empty
     */
    void roll(String text, Optional<String> savedRollName) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(savedRollName, "savedRollName");
        if (closed) {
            return;
        }
        if (controller.underWay() || queued != null) {
            queued = text;
            queuedName = savedRollName;
            onQueuedChanged.accept(queued());
            return;
        }
        controller.roll(text, savedRollName);
    }

    /**
     * Drops the Queued Roll, as Esc does when nothing else takes it.
     *
     * @return whether there was one to drop
     */
    boolean cancel() {
        if (queued == null) {
            return false;
        }
        clear();
        return true;
    }

    /**
     * Ends the queue for good, as the failure panel does (spec §3.2 "Runtime failure"): the Queued Roll is dropped,
     * and every later request is ignored, so nothing starts behind the panel.
     */
    void close() {
        closed = true;
        cancel();
    }

    /**
     * Returns the Queued Roll.
     *
     * @return its text as typed, or empty if none is queued
     */
    Optional<String> queued() {
        return Optional.ofNullable(queued);
    }

    /**
     * Tells whether a Queued Roll is waiting, behind the current Roll or through its beat; the clock must keep
     * running meanwhile.
     *
     * @return {@code true} while a Roll is queued
     */
    boolean waiting() {
        return queued != null;
    }

    /**
     * Sets who is told when the Queued Roll changes: queued, replaced, cancelled or started.
     *
     * @param listener told the Queued Roll's text, or empty; it replaces any earlier one
     */
    void setOnQueuedChanged(Consumer<Optional<String>> listener) {
        onQueuedChanged = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Hears the controller's status: when the current Roll is revealed, or fails, a Queued Roll's beat starts.
     *
     * @param status the controller's new status
     */
    void statusChanged(RollStatus status) {
        if (queued != null && Double.isNaN(startsAt)
                && (status instanceof RollStatus.Rested || status instanceof RollStatus.Failed)) {
            startsAt = clock.time() + BEAT_SECONDS;
        }
    }

    /** The clock moved on: start the Queued Roll once its beat is over. */
    private void advanced() {
        // A hair of slack: 54 frames of 1/60 s add up to a shade under 0.9 s in floating point.
        if (queued == null || Double.isNaN(startsAt) || clock.time() < startsAt - 1e-9) {
            return;
        }
        String next = queued;
        Optional<String> nextName = queuedName;
        clear();
        controller.roll(next, nextName);
    }

    private void clear() {
        queued = null;
        queuedName = Optional.empty();
        startsAt = Double.NaN;
        onQueuedChanged.accept(Optional.empty());
    }
}
