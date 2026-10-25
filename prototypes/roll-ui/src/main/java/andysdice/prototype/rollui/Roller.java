package andysdice.prototype.rollui;

import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.util.Duration;

/**
 * PROTOTYPE controller between a variant's entry controls and its tray: parse, roll, play, and on reveal append
 * to the Roll History (ADR-0007: a Roll joins the history when its Roll Result is revealed, at rest or on a skip).
 * It applies the switcher's input mode for a Roll asked for while dice are still moving.
 */
final class Roller {

    record Pending(String text, String savedName) {
    }

    private final AppState state;
    private final TrayView tray;
    private final Behaviour beh;

    /** The Roll waiting for the current one to come to rest (QUEUE mode only). */
    final ObjectProperty<Pending> queued = new SimpleObjectProperty<>();

    Consumer<AppState.HistoryEntry> onReveal = e -> {
    };
    Runnable onStart = () -> {
    };

    Roller(AppState state, TrayView tray, Behaviour beh) {
        this.state = state;
        this.tray = tray;
        this.beh = beh;
    }

    /** True while the entry controls should be disabled outright (LOCKED mode, dice moving). */
    BooleanBinding inputLocked() {
        return Bindings.createBooleanBinding(() -> tray.isBusy() && beh.input.get() == Behaviour.Input.LOCKED,
                tray.busyProperty(), beh.input);
    }

    /** True while Enter / the Roll button can't start or queue a Roll (TYPE_AHEAD or LOCKED, dice moving). */
    BooleanBinding rollBlocked() {
        return Bindings.createBooleanBinding(() -> tray.isBusy()
                        && (beh.input.get() == Behaviour.Input.LOCKED || beh.input.get() == Behaviour.Input.TYPE_AHEAD),
                tray.busyProperty(), beh.input);
    }

    /**
     * Rolls {@code text}, which may come from a Saved Roll named {@code savedName} (or null).
     *
     * @return null if the Roll started, was queued or was ignored because dice are moving; otherwise the one
     *     error and span to show (#7: all or nothing, so nothing was rolled)
     */
    Expr.ParseError submit(String text, String savedName) {
        Expr.Result r = Expr.parse(text);
        if (r.error() != null) {
            return r.error();
        }
        if (tray.isBusy()) {
            switch (beh.input.get()) {
                case QUEUE -> {
                    queued.set(new Pending(text, savedName));
                    return null;
                }
                case INTERRUPT -> tray.finishNow();
                default -> {
                    // TYPE_AHEAD and LOCKED keep Roll disabled while dice move; a stray Enter does nothing.
                    return null;
                }
            }
        }
        return start(r.ok(), savedName);
    }

    private Expr.ParseError start(Expr.Parsed parsed, String savedName) {
        RollPlan plan;
        try {
            plan = RollPlan.roll(parsed, state.rng);
        } catch (RollPlan.RollFailed e) {
            return new Expr.ParseError(e.getMessage(), 0, parsed.source().length());
        }
        onStart.run();
        tray.play(plan, done -> {
            AppState.HistoryEntry entry = state.addHistory(parsed.source(), savedName, done);
            onReveal.accept(entry);
            Pending q = queued.get();
            if (q != null) {
                queued.set(null);
                // A short beat so the revealed Roll Result can be read before the queued Roll clears the tray.
                PauseTransition beat = new PauseTransition(Duration.seconds(0.9));
                beat.setOnFinished(x -> submit(q.text(), q.savedName()));
                beat.play();
            }
        });
        return null;
    }
}
