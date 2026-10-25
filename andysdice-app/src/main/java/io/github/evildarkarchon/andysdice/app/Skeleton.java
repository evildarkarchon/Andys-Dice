package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.physics.PhysicsWorker;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.FrameView;
import io.github.evildarkarchon.andysdice.render.GlDriver;
import io.github.evildarkarchon.andysdice.render.RenderThread;
import io.github.evildarkarchon.andysdice.render.ThrowPlayback;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import javafx.application.Platform;
import org.joml.Vector3d;

/**
 * The walking skeleton wired together (spec slice S3): the physics worker with Jolt, the render thread drawing
 * the Dice scene into a {@link FrameView}, the playback clock, and the roll controller between them, under one
 * {@link RollPane}.
 *
 * <p>The clock and what advances it are handed in, so the app drives it from JavaFX's pulse and tests advance
 * it by hand. The app wires the window around {@link #pane()}; #48's Roll UI shell replaces the pane, and #38
 * the controller's 1d6-only planning, without changing this wiring's shape.
 *
 * <p>Thread contract: started, used and closed on the JavaFX Application Thread.
 */
final class Skeleton implements AutoCloseable {

    /** The fixed camera (#46 adds the settle-in camera): over the near end, pitched 55° down at the Tray's middle. */
    private static final DiceScene.Camera CAMERA = camera(700, 55);

    private final PhysicsWorker worker;
    private final RenderThread renderer;
    private final DiceScene scene;
    private final FrameView view;
    private final RollController controller;
    private final RollPane pane;
    private ThrowPlayback showing;
    private Consumer<RollStatus> onStatusChanged = status -> {};

    private Skeleton(PhysicsWorker worker, RenderThread renderer, DiceScene scene, FrameView view,
            PlaybackClock clock, Pulses pulses, FaceValueGenerator faceValues, RandomGenerator releases) {
        this.worker = worker;
        this.renderer = renderer;
        this.scene = scene;
        this.view = view;
        this.controller = new RollController(clock, worker::submit, Platform::runLater, faceValues, releases,
                new RollController.TrayView() {
                    @Override
                    public void show(ThrowPlayback playback) {
                        showing = playback;
                        draw(playback, 0);
                        pulses.start();
                    }

                    @Override
                    public void showTime(double seconds) {
                        draw(showing, seconds);
                    }
                });
        this.pane = new RollPane(view, controller::roll);
        pane.showStatus(controller.status());
        controller.setOnStatusChanged(status -> {
            pane.showStatus(status);
            // Only playback needs the pulse; once the Dice rest (or nothing will move) let JavaFX idle.
            if (!(status instanceof RollStatus.Rolling)) {
                pulses.stop();
            }
            onStatusChanged.accept(status);
        });
    }

    /**
     * Starts Jolt on the physics worker and the GL context on the render thread, then wires the Roll UI to them.
     * Both start eagerly, so a missing native or no OpenGL 3.3 fails here, at start-up.
     *
     * @param clock the playback clock
     * @param pulses what advances the clock while a Throw plays
     * @param faceValues the Face Value generator's source (ADR-0001)
     * @param releases the Release generator (ADR-0003)
     * @return the running skeleton
     * @throws io.github.evildarkarchon.andysdice.physics.JoltStartupException if Jolt can't start
     * @throws io.github.evildarkarchon.andysdice.render.GlUnavailableException if there is no OpenGL 3.3 core
     */
    static Skeleton start(PlaybackClock clock, Pulses pulses, RandomGenerator faceValues,
            RandomGenerator releases) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(pulses, "pulses");
        PhysicsWorker worker = PhysicsWorker.start();
        DiceScene scene = new DiceScene();
        scene.setCamera(CAMERA);
        FrameView view = new FrameView();
        RenderThread renderer;
        try {
            renderer = RenderThread.start(view.transport(), scene);
        } catch (RuntimeException e) {
            worker.close();
            throw e;
        }
        view.setOnTargetSizeChanged(renderer::resize);
        // The first-run Tray is empty; draw it once so the view isn't blank until the first Roll.
        renderer.requestFrame();
        return new Skeleton(worker, renderer, scene, view, clock, pulses, new FaceValueGenerator(faceValues),
                releases);
    }

    /**
     * Returns the window content.
     *
     * @return the pane holding the box, the Tray and the result strip
     */
    RollPane pane() {
        return pane;
    }

    /**
     * Returns the roll controller.
     *
     * @return the controller Enter rolls through
     */
    RollController controller() {
        return controller;
    }

    /**
     * Returns the view the Tray is rendered into.
     *
     * @return the frame view
     */
    FrameView view() {
        return view;
    }

    /**
     * Returns what the OpenGL driver reported about the render thread's context, for the log.
     *
     * @return the context's renderer, vendor and version
     */
    GlDriver driver() {
        return renderer.driver();
    }

    /**
     * Sets who else is told each time the Roll's status changes, after the result strip has shown it. The
     * smoke test watches for the end of its Roll this way.
     *
     * @param listener the listener; it replaces any earlier one
     */
    void setOnStatusChanged(Consumer<RollStatus> listener) {
        onStatusChanged = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Returns the Throw on the Tray.
     *
     * @return the last Throw shown, or {@code null} before the first
     */
    ThrowPlayback showing() {
        return showing;
    }

    /** Draws the Throw at a moment of its playback, interpolated, at the next frame. */
    private void draw(ThrowPlayback playback, double seconds) {
        scene.setDice(playback.diceAt(seconds));
        renderer.requestFrame();
    }

    /** Stops the render thread and the physics worker. */
    @Override
    public void close() {
        renderer.close();
        worker.close();
    }

    /** A camera over the near end (towards -z) at a distance in mm, pitched down at the Tray's middle. */
    private static DiceScene.Camera camera(double distance, double pitchDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        return new DiceScene.Camera(new Vector3d(0, distance * Math.sin(pitch), -distance * Math.cos(pitch)),
                new Vector3d(0, 0, 0), 32);
    }
}
