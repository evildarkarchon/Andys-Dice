package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
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
 * <p>The app must be idle when it starts (its Roll state {@linkplain RollState#settled settled}, and no frame in
 * flight): the first frame shown once the Roll's status is {@link RollStatus.Playing} is then the Roll's own, not
 * one drawn before it.
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
     * screen: the first frame presented once the Roll's state snapshot says it is Playing. It uses the frame view's
     * presented-frame listener until then.
     *
     * @param skeleton the started app, idle
     * @param parsed the Roll Expression, parsed, so Enter on its text rolls it rather than showing an error
     * @return the time from Enter to the first motion; completed exceptionally if the app hasn't settled (a Roll
     *     under way or queued, or the camera still moving), the Roll fails, or it takes longer than {@link #TIMEOUT}
     */
    static CompletableFuture<Duration> time(Skeleton skeleton, RollExpression parsed) {
        CompletableFuture<Duration> timed = new CompletableFuture<>();
        String expression = parsed.source();
        if (!skeleton.controller().state().settled()) {
            // Enter would make it the Queued Roll, or a frame of what is still moving could be taken for its first.
            timed.completeExceptionally(new IllegalStateException("can't time " + expression
                    + ": the app hasn't settled"));
            return timed;
        }
        FrameView view = skeleton.view();
        long[] entered = new long[1];
        // Set once the Roll's status is Playing: by then the Tray has its chain, at its first Release.
        boolean[] playing = new boolean[1];
        view.setOnFramePresented(() -> {
            // The app was settled, so the first frame shown once the Roll plays is drawn from its chain. Playing is
            // published on this thread right after the Tray asked for that frame, so no frame of it is shown before.
            if (playing[0]) {
                timed.complete(Duration.ofNanos(System.nanoTime() - entered[0]));
            }
        });
        // State listeners can't be removed; once this timing is over, this one does nothing.
        skeleton.controller().addStateListener(RollState.statusChanges(status -> {
            if (timed.isDone()) {
                return;
            }
            if (status instanceof RollStatus.Playing) {
                // Only as a signal: Playing.start() is a playback clock time, and the presented-frame callback
                // carries no clock time to compare it with, so the timing runs on System.nanoTime() alone.
                playing[0] = true;
            } else if (status instanceof RollStatus.Failed failed) {
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
