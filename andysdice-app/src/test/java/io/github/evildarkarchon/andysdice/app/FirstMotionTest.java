package io.github.evildarkarchon.andysdice.app;

import static io.github.evildarkarchon.andysdice.app.RollExpressions.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.time.Duration;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code --bench}'s Enter-to-first-motion timing (ADR-0014, ticket #61) through the real pipeline: Enter in the box,
 * pre-simulation with real Jolt on the physics worker, the first frame of the Throw drawn by the real GL renderer and
 * put on screen by the frame bridge. Only invariants; the bench itself judges the time against its budget.
 *
 * <p>Needs an OpenGL 3.3 context, so it is {@code gl}-tagged, and it starts the JVM's one render thread.
 */
@Tag("gl")
class FirstMotionTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private static Skeleton skeleton;
    private static Stage stage;

    @BeforeAll
    static void startTheApp() throws Exception {
        HeadlessUiTest.startToolkit();
        Generators generators = new Generators(new SplittableRandom(3), new SplittableRandom(5),
                new SplittableRandom(7));
        AtomicBoolean firstRunShown = new AtomicBoolean();
        skeleton = HeadlessUiTest.fromFx(() -> {
            Skeleton started = Skeleton.start(new PlaybackClock(), new HandPulses(), generators);
            // Set on the JavaFX thread before it can present the first-run frame, so that frame is heard.
            started.glTray().onFramePresented(() -> firstRunShown.set(true));
            return started;
        });
        stage = HeadlessUiTest.fromFx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(skeleton.session().ui(), 640, 480));
            s.show();
            return s;
        });
        // The empty first-run Tray is on screen before anything is timed.
        assertThat(HeadlessUiTest.await(firstRunShown::get, WAIT)).isTrue();
    }

    @AfterAll
    static void stopTheApp() throws Exception {
        HeadlessUiTest.onFx(() -> {
            stage.close();
            skeleton.close();
        });
    }

    @Test
    void itTimesEnterToTheFirstFrameOfTheThrowOnScreen() throws Exception {
        long start = System.nanoTime();

        CompletableFuture<Duration> timed = HeadlessUiTest.fromFx(() -> FirstMotion.time(skeleton,
                valid(Bench.EXPRESSION)));
        Duration firstMotion = timed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        assertThat(firstMotion).isPositive();
        assertThat(firstMotion.toNanos()).isLessThanOrEqualTo(elapsed);
        // By then the Roll is on the Tray and a frame of it has been shown: the timing only completes from the GL
        // Tray's frame-presented callback.
        ChainPlayback shown = HeadlessUiTest.fromFx(() -> skeleton.glTray().showing());
        assertThat(shown).isNotNull();
        assertThat(shown.plan().dice()).hasSize(20);
        assertThat(shown.plan().throwChain().getFirst().thrown()).hasSize(20);
        assertThat(HeadlessUiTest.fromFx(() -> skeleton.session().ui().expressionBox().getText()))
                .isEqualTo(Bench.EXPRESSION);
    }
}
