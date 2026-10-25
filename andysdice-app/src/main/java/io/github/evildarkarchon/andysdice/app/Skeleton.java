package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.physics.PhysicsWorker;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.FrameView;
import io.github.evildarkarchon.andysdice.render.RenderThread;
import java.util.Objects;
import javafx.application.Platform;
import org.joml.Vector3d;

/**
 * The walking skeleton wired together (spec slice S3): the physics worker with Jolt, the render thread drawing
 * the Dice scene into a {@link FrameView}, the playback clock, and the roll controller between them, under one
 * {@link RollPane}.
 *
 * <p>The clock and what advances it are handed in, so the app drives it from JavaFX's pulse and tests advance
 * it by hand. The app wires the window around {@link #pane()}; #48's Roll UI shell replaces the pane without
 * changing this wiring's shape. Any Roll Expression rolls: the controller plans the whole chain of Throws on the
 * physics worker, and the Tray draws every Die of it through the {@link DiceScene} (#38).
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
    private final Generators generators;
    private ChainPlayback showing;

    private Skeleton(PhysicsWorker worker, RenderThread renderer, DiceScene scene, FrameView view,
            PlaybackClock clock, Pulses pulses, Generators generators) {
        this.worker = worker;
        this.renderer = renderer;
        this.scene = scene;
        this.view = view;
        this.generators = generators;
        this.controller = new RollController(clock, worker::submit, Platform::runLater,
                new FaceValueGenerator(generators.faceValues()), generators.releases(),
                new RollController.TrayView() {
                    @Override
                    public void show(ChainPlayback chain) {
                        showing = chain;
                        draw(chain, 0);
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
        });
    }

    /**
     * Starts Jolt on the physics worker and the GL context on the render thread, then wires the Roll UI to them.
     * Both start eagerly, so a missing native or no OpenGL 3.3 fails here, at start-up.
     *
     * @param clock the playback clock
     * @param pulses what advances the clock while a Throw plays
     * @param generators the Face Value, Release and sound variation generators, each seeded on its own
     * @return the running skeleton
     * @throws io.github.evildarkarchon.andysdice.physics.JoltStartupException if Jolt can't start
     * @throws io.github.evildarkarchon.andysdice.render.GlUnavailableException if there is no OpenGL 3.3 core
     */
    static Skeleton start(PlaybackClock clock, Pulses pulses, Generators generators) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(pulses, "pulses");
        Objects.requireNonNull(generators, "generators");
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
        return new Skeleton(worker, renderer, scene, view, clock, pulses, generators);
    }

    /**
     * Returns the app's generators.
     *
     * @return the generators the skeleton was started with; sound variation waits for #59's mixer
     */
    Generators generators() {
        return generators;
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
     * Returns the Roll on the Tray.
     *
     * @return the chain of Throws last shown, or {@code null} before the first
     */
    ChainPlayback showing() {
        return showing;
    }

    /** Draws every Die of the chain at a moment of its playback, interpolated, at the next frame. */
    private void draw(ChainPlayback chain, double seconds) {
        scene.setDice(chain.diceAt(seconds));
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
