package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.util.Objects;
import javafx.beans.value.ObservableValue;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

/**
 * Fast-forward (spec §3.2 "Fast-forward"): while the Player holds Shift by itself, the playback clock runs
 * ×{@value #SPEED} faster, so the whole chain (Throws, the reroll pickup and the camera's easing, which all play on
 * that clock) plays faster; releasing Shift puts it back at once. Nothing is ever skipped: only the clock's speed
 * changes, so every Die is still seen landing.
 *
 * <p>It writes only the clock's Fast-forward factor, never its base speed, which is the launch mode's: ×1 in the
 * Player's app, so there Fast-forward means ×{@value #SPEED} and back to ×1; in an unattended run it multiplies that
 * run's own speed, and releasing Shift returns to it rather than to real time.
 *
 * <p>Fast-forward starts only once Shift has been held alone for {@value #HOLD_SECONDS} s. Shift is also how
 * {@code +}, {@code %}, {@code <}, {@code >} and capitals are typed into the box, so any other key pressed during
 * the hold cancels it for good (and ends Fast-forward, if it had started) until Shift comes up and is held alone
 * again. A held Shift's auto-repeat presses don't restart the hold.
 *
 * <p>The hold is timed on the playback clock, not a wall clock, so tests time it by advancing the injected clock
 * (ADR-0011). Throughout a hold the Fast-forward factor is ×1, so the clock runs at exactly its base speed, and its
 * time since the press divided by the base speed is real time: the hold lasts {@value #HOLD_SECONDS} s of real time
 * at any base speed. The clock only moves while something plays, though, so Shift held while the Tray is idle starts
 * counting when the next Roll starts playing.
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread, like the clock it speeds up.
 */
final class FastForward {

    /** The clock's Fast-forward factor while Fast-forward is on (spec §4 "Fast-forward speed"). */
    static final double SPEED = 4;

    /** How long Shift must be held alone before Fast-forward starts, in seconds (spec §4, a tunable). */
    static final double HOLD_SECONDS = 0.2;

    /** Where Shift stands. */
    enum State {
        /** Shift is up: a Fast-forward factor of ×1, so the clock runs at its base speed. */
        OFF,
        /** Shift is down alone, not yet for the whole hold time: still ×1. */
        HOLDING,
        /** Shift has been held alone for the hold time: ×{@value #SPEED} until it comes up. */
        ON,
        /** Another key came during this hold: ×1 until Shift comes up and is held alone again. */
        CANCELLED
    }

    private final PlaybackClock clock;
    private State state = State.OFF;
    private double holdStart;

    /**
     * Creates Fast-forward for a clock, with no hold. It leaves the clock's Fast-forward factor at its default ×1.
     *
     * @param clock the playback clock it speeds up; its advances time the hold
     */
    FastForward(PlaybackClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        clock.addListener(c -> advanced());
    }

    /**
     * Listens for keys on the window a node is shown in, from now on and whenever it moves to another window.
     *
     * <p>The keys are heard through event filters on the window itself, the first stop of every key event's
     * dispatch, so a control or scene filter that consumes a key (Esc, Enter) can't hide it from the hold, and a key
     * still counts when nothing in the window has focus. The window losing focus ends the hold: Shift may then come
     * up elsewhere, where this window would never hear it, and the clock must not stay fast-forwarded.
     *
     * @param node a node of the window's content (the Layout C root)
     */
    void install(Node node) {
        EventHandler<KeyEvent> pressed = event -> pressed(event.getCode());
        EventHandler<KeyEvent> released = event -> released(event.getCode());
        ObservableValue<Window> window = node.sceneProperty().flatMap(Scene::windowProperty);
        if (window.getValue() != null) {
            window.getValue().addEventFilter(KeyEvent.KEY_PRESSED, pressed);
            window.getValue().addEventFilter(KeyEvent.KEY_RELEASED, released);
        }
        window.subscribe((before, after) -> {
            if (before != null) {
                before.removeEventFilter(KeyEvent.KEY_PRESSED, pressed);
                before.removeEventFilter(KeyEvent.KEY_RELEASED, released);
            }
            if (after != null) {
                after.addEventFilter(KeyEvent.KEY_PRESSED, pressed);
                after.addEventFilter(KeyEvent.KEY_RELEASED, released);
            }
        });
        // Not covered by the headless tests: JavaFX's headless platform never takes focus from a window.
        window.flatMap(Window::focusedProperty).subscribe(focused -> {
            if (Boolean.FALSE.equals(focused) && state != State.OFF) {
                become(State.OFF);
            }
        });
    }

    /**
     * Returns where Shift stands.
     *
     * @return the current state; the clock's Fast-forward factor is ×{@value #SPEED} exactly when it is
     *     {@link State#ON}
     */
    State state() {
        return state;
    }

    /**
     * A key went down. Shift starts a hold (a held key's auto-repeat presses don't restart it); any other key
     * cancels the hold, or ends Fast-forward if it was on, until Shift comes up.
     */
    private void pressed(KeyCode key) {
        if (key == KeyCode.SHIFT) {
            if (state == State.OFF) {
                state = State.HOLDING;
                holdStart = clock.time();
            }
        } else if (state != State.OFF) {
            become(State.CANCELLED);
        }
    }

    /** A key came up: Shift ends the hold, whatever became of it, and puts the clock back to its base speed at once. */
    private void released(KeyCode key) {
        if (key == KeyCode.SHIFT) {
            become(State.OFF);
        }
    }

    /** The clock moved on: a hold that has lasted long enough in real time turns Fast-forward on. */
    private void advanced() {
        // While HOLDING the Fast-forward factor is x1, so the clock runs at exactly the base speed, and its time since
        // the press divided by that speed is real time. The base speed is read here, on every advance, rather than kept
        // from construction: the launch mode sets it, and Fast-forward mustn't depend on which was made first. The
        // tolerance absorbs the rounding of a sum of 1/60 s frames that should land exactly on the hold time.
        if (state == State.HOLDING && (clock.time() - holdStart) / clock.baseSpeed() >= HOLD_SECONDS - 1e-9) {
            become(State.ON);
        }
    }

    /**
     * Moves to a state and sets the clock's Fast-forward factor to match it. Only the factor: the base speed is the
     * launch mode's, so leaving Fast-forward returns to the run's own speed, not to real time.
     */
    private void become(State next) {
        state = next;
        clock.setFastForwardFactor(next == State.ON ? SPEED : 1);
    }
}
