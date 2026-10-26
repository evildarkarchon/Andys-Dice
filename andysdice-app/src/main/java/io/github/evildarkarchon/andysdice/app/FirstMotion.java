package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;
import io.github.evildarkarchon.andysdice.render.FrameView;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Times one Roll from Enter to its first motion on screen (ADR-0014), for {@code --bench}: Enter in the box, the
 * parse, the whole chain's pre-simulation on the physics worker, the hand-over to the Tray, the first frame of the
 * Throw drawn on the render thread, read back, and put on screen by the frame bridge. That frame shows the Dice at
 * their Release, the first moment of the Throw's motion.
 *
 * <p>The Tray must be idle when it starts (no Roll under way, the camera settled, no frame in flight): the first
 * frame shown after the Roll reaches the Tray is then the Roll's own, not one drawn before it.
 *
 * <p>Thread contract: {@link #time} on the JavaFX Application Thread; its result completes there too, except a
 * timeout, which completes it on a timer thread.
 */
final class FirstMotion {

    /** How long one Roll may take to reach the screen before the timing gives up. */
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    private FirstMotion() {}

    /**
     * Types a Roll Expression into the box, presses Enter, and times it until the first frame of its Throw is on
     * screen. It uses the frame view's presented-frame listener until then.
     *
     * @param skeleton the started app, idle
     * @param expression a Roll Expression that parses
     * @return the time from Enter to the first motion; completed exceptionally if the expression doesn't parse, a
     *     Roll is already under way, the Roll fails, or it takes longer than {@link #TIMEOUT}
     */
    static CompletableFuture<Duration> time(Skeleton skeleton, String expression) {
        CompletableFuture<Duration> timed = new CompletableFuture<>();
        if (RollExpressionParser.parse(expression) instanceof ParseResult.Invalid invalid) {
            // Enter would only show the error popup, and nothing would ever move.
            timed.completeExceptionally(new IllegalArgumentException("can't time " + expression + ": "
                    + invalid.error().message()));
            return timed;
        }
        if (skeleton.controller().state().underWay()) {
            // Enter would make it the Queued Roll, which waits for the one under way.
            timed.completeExceptionally(new IllegalStateException("can't time " + expression
                    + ": a Roll is already under way"));
            return timed;
        }
        ChainPlayback before = skeleton.showing();
        FrameView view = skeleton.view();
        long[] entered = new long[1];
        view.setOnFramePresented(() -> {
            // The Tray was idle, so the first frame shown once the new chain is on the Tray is drawn from it.
            if (skeleton.showing() != before) {
                timed.complete(Duration.ofNanos(System.nanoTime() - entered[0]));
            }
        });
        // State listeners can't be removed; once this timing is over, this one does nothing.
        skeleton.controller().addStateListener(RollState.statusChanges(status -> {
            if (status instanceof RollStatus.Failed failed && !timed.isDone()) {
                timed.completeExceptionally(new IllegalStateException("the Roll of " + expression + " failed: "
                        + failed.text()));
            }
        }));
        // Through the JavaFX thread, which owns the listener: a timeout completes the timing on a timer thread.
        timed.whenComplete((duration, error) -> Platform.runLater(() -> view.setOnFramePresented(null)));

        TextField box = skeleton.pane().expressionBox();
        box.setText(expression);
        entered[0] = System.nanoTime();
        // Enter, as a key press on the box: the path the Player's Enter takes (entry, then the controller).
        box.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        return timed.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
}
