package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.render.ThrowPlayback;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The walking skeleton end to end (ticket #34): Enter on {@code 1d6} in the real window content Throws the cube
 * with real Jolt on the physics worker and plays it back through the real GL renderer and frame bridge, on
 * JavaFX's headless platform (ADR-0011). Only the playback clock is the test's: it is advanced by hand, frame by
 * frame, so the result's timing is checked against the Recording, not against a sleep.
 *
 * <p>Needs an OpenGL 3.3 context, so it is {@code gl}-tagged, and it starts the JVM's one render thread.
 */
@Tag("gl")
class WalkingSkeletonTest {

    private static final long FACE_SEED = 20261025;
    private static final double FRAME = 1.0 / 60;
    private static final Duration WAIT = Duration.ofSeconds(30);

    /** Pulses that only count starts and stops: the test advances the clock itself. */
    private static final class HandPulses implements Pulses {
        final AtomicInteger starts = new AtomicInteger();
        volatile boolean running;

        @Override
        public void start() {
            starts.incrementAndGet();
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }
    }

    @Test
    void enterOnOneD6ThrowsTheCubePlaysItBackAndShowsTheResultOnlyAtRest() throws Exception {
        HeadlessFx.start();
        PlaybackClock clock = new PlaybackClock();
        HandPulses pulses = new HandPulses();
        Skeleton skeleton = HeadlessFx.call(() -> Skeleton.start(clock, pulses, new SplittableRandom(FACE_SEED),
                new SplittableRandom(7)));
        Stage stage = HeadlessFx.call(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(skeleton.pane(), 640, 480));
            s.show();
            return s;
        });
        try {
            // The empty first-run Tray is drawn before anything is rolled.
            assertThat(HeadlessFx.await(() -> skeleton.view().framesPresented() > 0, WAIT)).isTrue();

            HeadlessFx.run(() -> {
                skeleton.pane().expressionBox().setText("1d6");
                skeleton.pane().expressionBox().fireEvent(
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            });
            assertThat(HeadlessFx.call(() -> skeleton.pane().resultStrip().getText())).isEqualTo("Rolling…");

            // Pre-simulation runs on the physics worker; playback starts only once it is done.
            assertThat(HeadlessFx.await(() -> skeleton.showing() != null, WAIT)).isTrue();
            ThrowPlayback playback = skeleton.showing();
            assertThat(pulses.starts).hasValue(1);
            assertThat(clock.time()).isZero();
            long framesBefore = skeleton.view().framesPresented();

            // Play it back a 60 Hz frame at a time, noting what the result strip says at each playback time.
            List<Double> times = new ArrayList<>();
            List<String> strips = new ArrayList<>();
            HeadlessFx.run(() -> {
                while (!(skeleton.controller().status() instanceof RollStatus.Rested) && clock.time() < 10) {
                    clock.advance(FRAME);
                    times.add(clock.time());
                    strips.add(skeleton.pane().resultStrip().getText());
                }
            });

            double rest = playback.recording().restTime();
            int value = new FaceValueGenerator(new SplittableRandom(FACE_SEED)).faceValue(DieKind.D6);
            for (int i = 0; i < times.size(); i++) {
                assertThat(strips.get(i)).as("result strip at %.3f s, rest at %.3f s", times.get(i), rest)
                        .isEqualTo(times.get(i) < rest ? "Rolling…" : "1d6 = " + value);
            }
            assertThat(times.getLast()).isGreaterThanOrEqualTo(rest).isLessThan(rest + FRAME);
            assertThat(pulses.running).isFalse();
            // The playback frames reached the screen through the bridge.
            assertThat(HeadlessFx.await(() -> skeleton.view().framesPresented() > framesBefore, WAIT)).isTrue();

            // The cube stays on the Tray, and the result stays shown, until the next Roll.
            HeadlessFx.run(() -> clock.advance(2));
            assertThat(skeleton.showing()).isSameAs(playback);
            assertThat(HeadlessFx.call(() -> skeleton.pane().resultStrip().getText())).isEqualTo("1d6 = " + value);
        } finally {
            HeadlessFx.run(() -> {
                stage.close();
                skeleton.close();
            });
        }
    }
}
