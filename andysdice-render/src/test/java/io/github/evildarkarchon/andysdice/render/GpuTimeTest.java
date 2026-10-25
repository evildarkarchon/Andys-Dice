package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The render thread's GPU timer (ADR-0014): GL timer queries measure how long the GPU spends drawing each frame,
 * and the times are reported off the render thread's own loop, never by stalling it. The slow-GPU Notice and
 * {@code --bench} (ticket #61) read them. Only invariants: CI's Mesa times say nothing about any GPU (ADR-0005).
 */
@Tag("gl")
class GpuTimeTest {

    private static final int SIZE = 96;

    private static CaptureTransport capture;
    private static DiceScene scene;
    private static RenderThread renderer;

    @BeforeAll
    static void startRenderThread() {
        capture = new CaptureTransport();
        scene = new DiceScene();
        scene.setTray(BuiltInTrays.defaultTray());
        scene.setDice(List.of(new DiceScene.Die(DieKind.D20, new Vector3d(0, 11, 0), new Quaterniond())));
        renderer = RenderThread.start(capture, scene);
        renderer.resize(SIZE, SIZE);
        // The frame the resize asked for, drawn before any test listens, so each test counts its own frames only.
        try {
            capture.take(renderer, () -> {}).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopRenderThread() {
        if (renderer != null) {
            renderer.close();
        }
    }

    @Test
    void everyFrameDrawnReportsTheGpuTimeItTook() throws Exception {
        List<Double> millis = new CopyOnWriteArrayList<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        renderer.setOnGpuTime(ms -> {
            millis.add(ms);
            threads.add(Thread.currentThread());
        });

        int frames = 6;
        for (int i = 0; i < frames; i++) {
            capture.take(renderer, () -> {}).get(20, TimeUnit.SECONDS);
        }
        // Results come back a frame or more late; the idle loop still collects them.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (millis.size() < frames && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(millis).as("one time per frame").hasSize(frames);
        assertThat(millis).allSatisfy(ms -> assertThat(ms).isPositive().isLessThan(10_000.0));
        assertThat(threads).extracting(Thread::getName).containsOnly("andysdice-render");
    }

    @Test
    void framesDrawnWithNoListenerAreNotReportedLater() throws Exception {
        renderer.setOnGpuTime(null);
        capture.take(renderer, () -> {}).get(20, TimeUnit.SECONDS);
        // Long enough for the idle loop (50 ms a turn) to have collected any result of that frame.
        Thread.sleep(200);
        List<Double> millis = new CopyOnWriteArrayList<>();
        renderer.setOnGpuTime(millis::add);
        Thread.sleep(200);

        assertThat(millis).isEmpty();
        renderer.setOnGpuTime(null);
    }
}
