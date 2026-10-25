package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValues;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import io.github.evildarkarchon.andysdice.core.planner.PlannedThrow;
import io.github.evildarkarchon.andysdice.core.planner.ThrowKind;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.render.DiceScene;
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
 * A Roll Expression end to end (tickets #34, #38): Enter on {@code 4d6rr1kh3} in the real window content plans
 * the whole chain with real Jolt on the physics worker, plays the first Throw and the Reroll Throw through the
 * real GL renderer and frame bridge on JavaFX's headless platform (ADR-0011), and shows the Roll Result only once
 * the last Throw is at rest. The Face Values are scripted so the Roll always has a Reroll; the playback clock is
 * the test's, advanced by hand frame by frame, so the result's timing is checked against the Recordings, not
 * against a sleep.
 *
 * <p>Needs an OpenGL 3.3 context, so it is {@code gl}-tagged, and it starts the JVM's one render thread.
 */
@Tag("gl")
class WalkingSkeletonTest {

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
    void enterPlansTheWholeChainPlaysEveryThrowAndShowsTheResultOnlyAtRest() throws Exception {
        HeadlessFx.start();
        PlaybackClock clock = new PlaybackClock();
        HandPulses pulses = new HandPulses();
        // The first Die shows a 1, so it is rerolled once, to a 3: the others show 4, 5 and 6.
        Generators generators = new Generators(new ScriptedRandom(1, 4, 5, 6, 3), new SplittableRandom(7),
                new SplittableRandom(9));
        Skeleton skeleton = HeadlessFx.call(() -> Skeleton.start(clock, pulses, generators));
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
                skeleton.pane().expressionBox().setText("4d6rr1kh3");
                skeleton.pane().expressionBox().fireEvent(
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            });
            assertThat(HeadlessFx.call(() -> skeleton.pane().resultStrip().getText())).isEqualTo("Rolling…");

            // Pre-simulation of the whole chain runs on the physics worker; playback starts only once it is done.
            assertThat(HeadlessFx.await(() -> skeleton.showing() != null, WAIT)).isTrue();
            ChainPlayback chain = skeleton.showing();
            List<PlannedThrow> throwChain = chain.plan().throwChain();
            assertThat(throwChain.getFirst().kind()).isEqualTo(ThrowKind.FIRST);
            assertThat(throwChain).extracting(PlannedThrow::kind).contains(ThrowKind.REROLL);
            assertThat(pulses.starts).hasValue(1);
            assertThat(clock.time()).isZero();
            long framesBefore = skeleton.view().framesPresented();

            // Play it back a 60 Hz frame at a time, noting the strip and the Dice drawn at each playback time.
            List<Double> times = new ArrayList<>();
            List<String> strips = new ArrayList<>();
            List<List<DiceScene.Die>> drawn = new ArrayList<>();
            HeadlessFx.run(() -> {
                while (!(skeleton.controller().status() instanceof RollStatus.Rested) && clock.time() < 30) {
                    clock.advance(FRAME);
                    times.add(clock.time());
                    strips.add(skeleton.pane().resultStrip().getText());
                    drawn.add(chain.diceAt(clock.time()));
                }
            });

            double rest = chain.restTime();
            for (int i = 0; i < times.size(); i++) {
                assertThat(strips.get(i)).as("result strip at %.3f s, rest at %.3f s", times.get(i), rest)
                        .isEqualTo(times.get(i) < rest ? "Rolling…" : "4d6rr1kh3 = 15");
            }
            assertThat(times.getLast()).isGreaterThanOrEqualTo(rest).isLessThan(rest + FRAME);
            assertThat(pulses.running).isFalse();

            // While a Throw plays, every Die it doesn't throw stays exactly where it lay.
            for (int i = 1; i < times.size(); i++) {
                PlannedThrow playing = throwChain.get(chain.throwAt(times.get(i)));
                for (int frozen : playing.frozen()) {
                    assertThat(drawn.get(i).get(frozen)).isEqualTo(drawn.get(i - 1).get(frozen));
                }
            }

            // At rest each d6 shows its last Face Value, from real Jolt's landing turned by its Numbering Rotation.
            List<DiceScene.Die> atRest = drawn.getLast();
            int[] values = {3, 4, 5, 6};
            for (int die = 0; die < values.length; die++) {
                UpFace up = UpFace.read(DieKind.D6, atRest.get(die).orientation());
                assertThat(atRest.get(die).kind()).isEqualTo(DieKind.D6);
                assertThat(up.face()).as("die %d", die).isEqualTo(FaceValues.face(DieKind.D6, values[die]));
                assertThat(up.cocked()).isFalse();
            }
            // The playback frames reached the screen through the bridge.
            assertThat(HeadlessFx.await(() -> skeleton.view().framesPresented() > framesBefore, WAIT)).isTrue();

            // The Dice stay on the Tray, and the result stays shown, until the next Roll.
            HeadlessFx.run(() -> clock.advance(2));
            assertThat(skeleton.showing()).isSameAs(chain);
            assertThat(HeadlessFx.call(() -> skeleton.pane().resultStrip().getText())).isEqualTo("4d6rr1kh3 = 15");
        } finally {
            HeadlessFx.run(() -> {
                stage.close();
                skeleton.close();
            });
        }
    }
}
