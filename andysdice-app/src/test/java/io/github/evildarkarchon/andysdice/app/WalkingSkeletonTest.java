package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValues;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import io.github.evildarkarchon.andysdice.core.planner.PlannedThrow;
import io.github.evildarkarchon.andysdice.core.planner.ThrowKind;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.camera.Framing;
import io.github.evildarkarchon.andysdice.render.camera.TrayCamera;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
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

    @Test
    void enterPlansTheWholeChainPlaysEveryThrowAndShowsTheResultOnlyAtRest() throws Exception {
        HeadlessUiTest.startToolkit();
        PlaybackClock clock = new PlaybackClock();
        HandPulses pulses = new HandPulses();
        // The first Die shows a 1, so it is rerolled once, to a 3: the others show 4, 5 and 6.
        Generators generators = new Generators(new ScriptedRandom(1, 4, 5, 6, 3), new SplittableRandom(7),
                new SplittableRandom(9));
        // Every frame the GL Tray puts on screen, counted from its frame-presented hook.
        AtomicLong framesPresented = new AtomicLong();
        Skeleton skeleton = HeadlessUiTest.fromFx(() -> {
            Skeleton started = Skeleton.start(clock, pulses, generators);
            // Set on the JavaFX thread before it can present the first-run frame, so no frame goes uncounted.
            started.glTray().onFramePresented(framesPresented::incrementAndGet);
            return started;
        });
        RollSession session = skeleton.session();
        GlTray glTray = skeleton.glTray();
        Stage stage = HeadlessUiTest.fromFx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(session.ui(), 640, 480));
            s.show();
            return s;
        });
        try {
            // The empty first-run Tray is drawn before anything is rolled.
            assertThat(HeadlessUiTest.await(() -> framesPresented.get() > 0, WAIT)).isTrue();

            HeadlessUiTest.onFx(() -> {
                session.ui().expressionBox().setText("4d6rr1kh3");
                session.ui().expressionBox().fireEvent(
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            });
            assertThat(HeadlessUiTest.fromFx(() -> session.ui().resultStrip().text())).isEqualTo("Rolling…");

            // Pre-simulation of the whole chain runs on the physics worker; playback starts only once it is done.
            assertThat(HeadlessUiTest.await(() -> glTray.showing() != null, WAIT)).isTrue();
            ChainPlayback chain = glTray.showing();
            List<PlannedThrow> throwChain = chain.plan().throwChain();
            assertThat(throwChain.getFirst().kind()).isEqualTo(ThrowKind.FIRST);
            assertThat(throwChain).extracting(PlannedThrow::kind).contains(ThrowKind.REROLL);
            assertThat(pulses.starts).hasValue(1);
            assertThat(clock.time()).isZero();
            long framesBefore = framesPresented.get();

            // Play it back a 60 Hz frame at a time, noting the strip and the Dice drawn at each playback time.
            List<Double> times = new ArrayList<>();
            List<String> strips = new ArrayList<>();
            List<List<DiceScene.Die>> drawn = new ArrayList<>();
            HeadlessUiTest.onFx(() -> {
                while (!(session.controller().state().status() instanceof RollStatus.Rested) && clock.time() < 30) {
                    clock.advance(FRAME);
                    times.add(clock.time());
                    strips.add(session.ui().resultStrip().text());
                    drawn.add(chain.diceAt(clock.time()));
                }
            });

            double rest = chain.restTime();
            for (int i = 0; i < times.size(); i++) {
                assertThat(strips.get(i)).as("result strip at %.3f s, rest at %.3f s", times.get(i), rest)
                        .isEqualTo(times.get(i) < rest ? "Rolling…" : "4d6rr1kh3 = 15");
            }
            assertThat(times.getLast()).isGreaterThanOrEqualTo(rest).isLessThan(rest + FRAME);
            // The clock runs on while the camera settles in on the Dice at rest (#46), then JavaFX may idle.
            assertThat(pulses.running).isTrue();
            double settled = HeadlessUiTest.fromFx(() -> {
                while (pulses.running && clock.time() < 30) {
                    clock.advance(FRAME);
                }
                return clock.time();
            });
            assertThat(settled).isGreaterThanOrEqualTo(rest + TrayCamera.SETTLE_SECONDS)
                    .isLessThan(rest + TrayCamera.SETTLE_SECONDS + 2 * FRAME);
            // The Tray's node is the view the camera fits; a Region's layout bounds are its laid-out width and height.
            assertThat(HeadlessUiTest.fromFx(() -> glTray.camera().camera())).isEqualTo(
                    HeadlessUiTest.fromFx(() -> Framing.of(Skeleton.TRAY.geometry()).resting(chain.diceAt(rest + 1),
                            glTray.node().getLayoutBounds().getWidth() / glTray.node().getLayoutBounds().getHeight())
                            .camera()));

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
            assertThat(HeadlessUiTest.await(() -> framesPresented.get() > framesBefore, WAIT)).isTrue();

            // The Dice stay on the Tray, and the result stays shown, until the next Roll.
            HeadlessUiTest.onFx(() -> clock.advance(2));
            assertThat(HeadlessUiTest.fromFx(glTray::showing)).isSameAs(chain);
            assertThat(HeadlessUiTest.fromFx(() -> session.ui().resultStrip().text())).isEqualTo("4d6rr1kh3 = 15");
        } finally {
            HeadlessUiTest.onFx(() -> {
                stage.close();
                skeleton.close();
            });
        }
    }
}
