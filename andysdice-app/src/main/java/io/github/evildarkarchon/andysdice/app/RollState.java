package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One immutable snapshot of where the Roll stands, as the roll controller publishes it on every change: the status
 * the result strip shows, the Queued Roll waiting behind it, and whether the app has settled. Everything that follows
 * a Roll (the result strip, entry's "next: …" chip, the Roll History, the dice sound, the smoke test, the bench) learns about it
 * from these, through {@link RollController#addStateListener}.
 *
 * @param status where the current Roll stands
 * @param queued the Queued Roll, if one waits
 * @param settled whether the app has settled: no Roll under way, no Queued Roll waiting, and the Tray no longer
 *     {@linkplain RollController.TrayView#moving moving} (the camera's settle-in over)
 */
record RollState(RollStatus status, Optional<QueuedRoll> queued, boolean settled) {

    /** Checks the parts are present. */
    RollState {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(queued, "queued");
    }

    /**
     * The Queued Roll (spec §3.2 "Entry"): what was asked for while a Roll was under way, and the Saved Roll it came
     * from, if any (#56).
     *
     * @param expression the Roll Expression asked for, already parsed
     * @param savedRollName the Saved Roll's name, or empty for a Roll typed or taken from the History
     */
    record QueuedRoll(RollExpression expression, Optional<String> savedRollName) {

        /** Checks the parts are present. */
        QueuedRoll {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(savedRollName, "savedRollName");
        }
    }

    /**
     * Tells whether a Roll is under way: its status is Pre-simulating or Playing ({@link RollStatus.UnderWay}).
     * Camera gestures are blocked exactly while it is.
     *
     * @return {@code true} from the Roll's start until it is at rest or has failed
     */
    boolean underWay() {
        return status instanceof RollStatus.UnderWay;
    }

    /**
     * Adapts an action on the status alone into a snapshot listener that runs it once per status, when the status
     * changes, and not again for a snapshot that changed only the Queued Roll or {@code settled}. The status is told
     * apart by identity: the controller makes a new one for each change, so two Rolls in a row that rest on the same
     * total are still two statuses, and each Roll joins the Roll History once.
     *
     * <p>The listener remembers the last status it saw, so make one per action. Its first snapshot always runs the
     * action.
     *
     * @param action told each new status
     * @return the listener
     */
    static Consumer<RollState> statusChanges(Consumer<RollStatus> action) {
        Objects.requireNonNull(action, "action");
        return new Consumer<>() {
            private RollStatus last;

            @Override
            public void accept(RollState state) {
                if (state.status() != last) {
                    last = state.status();
                    action.accept(last);
                }
            }
        };
    }
}
