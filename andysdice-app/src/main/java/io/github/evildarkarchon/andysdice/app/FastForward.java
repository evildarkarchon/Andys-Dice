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
 * Fast-forward (spec §3.2 "Fast-forward"): while the Player holds Shift by itself, the playback clock runs at
 * ×{@value #SPEED}, so the whole chain (Throws, the reroll pickup and the camera's easing, which all play on that
 * clock) plays faster; releasing Shift puts it back to ×1 at once. Nothing is ever skipped: only the clock's speed
 * changes, so every Die is still seen landing.
 *
 * <p>Fast-forward starts only once Shift has been held alone for {@value #HOLD_SECONDS} s. Shift is also how
 * {@code +}, {@code %}, {@code <}, {@code >} and capitals are typed into the box, so any other key pressed during
 * the hold cancels it for good (and ends Fast-forward, if it had started) until Shift comes up and is held alone
 * again. A held Shift's auto-repeat presses don't restart the hold.
 *
 * <p>The hold is timed on the playback clock, not a wall clock, so tests time it by advancing the injected clock
 * (ADR-0011). The clock runs at ×1 throughout a hold, so its time is real time; but it only moves while something
 * plays, so Shift held while the Tray is idle starts counting when the next Roll starts playing.
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread, like the clock it speeds up.
 */
final class FastForward {

    /** How fast the clock runs while Fast-forward is on (spec §4 "Fast-forward speed"). */
    static final double SPEED = 4;

    /** How long Shift must be held alone before Fast-forward starts, in seconds (spec §4, a tunable). */
    static final double HOLD_SECONDS = 0.2;

    /** Where Shift stands. */
    enum State {
        /** Shift is up: the clock runs at ×1. */
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
     * Creates Fast-forward for a clock, at ×1 with no hold.
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
     * up elsewhere, where this window would never hear it, and the clock must not stay at ×{@value #SPEED}.
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
     * @return the current state; the clock runs at ×{@value #SPEED} exactly when it is {@link State#ON}
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

    /** A key came up: Shift ends the hold, whatever became of it, and puts the clock back to ×1 at once. */
    private void released(KeyCode key) {
        if (key == KeyCode.SHIFT) {
            become(State.OFF);
        }
    }

    /** The clock moved on: a hold that has lasted long enough turns Fast-forward on. */
    private void advanced() {
        // While HOLDING the clock runs at x1, so its time since the press is real time. The tolerance absorbs the
        // rounding of a sum of 1/60 s frames that should land exactly on the hold time.
        if (state == State.HOLDING && clock.time() - holdStart >= HOLD_SECONDS - 1e-9) {
            become(State.ON);
        }
    }

    /** Moves to a state and sets the clock's speed to match it. */
    private void become(State next) {
        state = next;
        clock.setSpeed(next == State.ON ? SPEED : 1);
    }
}
