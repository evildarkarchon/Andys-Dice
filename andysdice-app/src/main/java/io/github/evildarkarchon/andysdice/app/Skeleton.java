package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.physics.PhysicsWorker;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.FrameView;
import io.github.evildarkarchon.andysdice.render.GlDriver;
import io.github.evildarkarchon.andysdice.render.RenderThread;
import io.github.evildarkarchon.andysdice.render.camera.Framing;
import io.github.evildarkarchon.andysdice.render.camera.TrayCamera;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Node;

/**
 * The walking skeleton wired together (spec slice S3): the physics worker with Jolt, the render thread drawing
 * the Dice scene into a {@link FrameView}, the playback clock, and the roll controller between them, under the
 * Layout C {@link RollUi} (#48). The controller and the UI are wired by a {@link RollSession}, the part the
 * headless UI tests run with a stand-in Tray; this class adds the GL Tray and the physics worker behind it.
 *
 * <p>The clock and what advances it are handed in, so the app drives it from JavaFX's pulse and tests advance
 * it by hand. The app opens the window around {@link #pane()}. Any Roll Expression rolls: the controller plans
 * the whole chain of Throws on the physics worker, and the Tray draws every Die of it through the
 * {@link DiceScene} (#38), seen through the settle-in {@link TrayCamera} on the same clock, which the Player can
 * orbit and zoom (#46).
 *
 * <p>Thread contract: started, used and closed on the JavaFX Application Thread.
 */
final class Skeleton implements AutoCloseable {

    /**
     * The Tray every Roll is thrown into, drawn in and framed by: the default built-in, green felt in walnut
     * (ADR-0010), until the Look tab (#57) lets the Player choose and remembers the choice.
     */
    static final Tray TRAY = BuiltInTrays.defaultTray();

    private final PhysicsWorker worker;
    private final RenderThread renderer;
    private final DiceScene scene;
    private final FrameView view;
    private final TrayCamera camera;
    private final RollSession session;
    private final Generators generators;
    private ChainPlayback showing;

    private Skeleton(PhysicsWorker worker, RenderThread renderer, DiceScene scene, FrameView view,
            TrayCamera camera, PlaybackClock clock, Pulses pulses, Generators generators) {
        this.worker = worker;
        this.renderer = renderer;
        this.scene = scene;
        this.view = view;
        this.camera = camera;
        this.generators = generators;
        // The GL Tray behind the Tray seam: the frame view, fed by the render thread through the transport.
        this.session = new RollSession(clock, pulses, worker::submit, Platform::runLater, generators, TRAY,
                new TraySurface() {
                    @Override
                    public Node node() {
                        return view;
                    }

                    @Override
                    public void show(ChainPlayback chain) {
                        showing = chain;
                        // The controller starts the chain at the clock's current time; so does the camera.
                        camera.play(chain.cameraScript());
                        draw(chain, 0);
                    }

                    @Override
                    public void showTime(double seconds) {
                        draw(showing, seconds);
                    }

                    @Override
                    public boolean moving() {
                        // Once the last Throw is at rest the camera is still settling in on the Dice (and while
                        // the next Roll pre-simulates, the last one's settle-in may still be easing).
                        return camera.moving();
                    }
                });
        // Added after the controller's own listener, so on each advance the Dice are drawn first. Once the last
        // Throw is at rest the controller draws nothing more, but the camera may still be easing.
        clock.addListener(c -> showCamera());
        // Every framing fits the view's shape.
        view.widthProperty().addListener((property, was, now) -> fitAspect());
        view.heightProperty().addListener((property, was, now) -> fitAspect());
        CameraGestures.attach(view, camera, () -> !session.controller().underWay(), this::showCamera);
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
        // Physics and rendering both use the chosen Tray: the controller throws into it, the scene draws it.
        scene.setTray(TRAY);
        // Reduced motion follows the system (spec §3.2), read as each pose is worked out, so a change applies at once.
        TrayCamera camera = new TrayCamera(clock, Framing.of(TRAY.geometry()),
                () -> Platform.getPreferences().isReducedMotion());
        scene.setCamera(camera.camera());
        FrameView view = new FrameView();
        RenderThread renderer;
        try {
            renderer = RenderThread.start(view.transport(), scene);
        } catch (RuntimeException e) {
            worker.close();
            throw e;
        }
        // The view reports its physical size (and again after a move to a monitor with another scale); the render
        // target follows it under the cap below High (ADR-0014), and JavaFX scales a capped frame up.
        view.setOnTargetSizeChanged(RenderTargetCap.capping(renderer::resize));
        // The first-run Tray is empty; draw it once so the view isn't blank until the first Roll.
        renderer.requestFrame();
        return new Skeleton(worker, renderer, scene, view, camera, clock, pulses, generators);
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
     * @return Layout C, holding the sidebar, the toolbar, the Tray and the result strip
     */
    RollUi pane() {
        return session.ui();
    }

    /**
     * Returns the roll controller.
     *
     * @return the controller Enter rolls through
     */
    RollController controller() {
        return session.controller();
    }

    /**
     * Returns the app's Notice service, whose Notices the bell shows. Post to it from any thread.
     *
     * @return the Notices
     */
    Notices notices() {
        return session.notices();
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
        session.setOnStatusChanged(listener);
    }

    /**
     * Returns the Roll on the Tray.
     *
     * @return the chain of Throws last shown, or {@code null} before the first
     */
    ChainPlayback showing() {
        return showing;
    }

    /**
     * Returns the settle-in camera.
     *
     * @return the camera the Tray is drawn from
     */
    TrayCamera camera() {
        return camera;
    }

    /** Draws every Die of the chain at a moment of its playback, interpolated, at the next frame. */
    private void draw(ChainPlayback chain, double seconds) {
        scene.setDice(chain.diceAt(seconds));
        // The same frame's camera, so the Dice and the camera never reach the render thread a frame apart.
        scene.setCamera(camera.camera());
        renderer.requestFrame();
    }

    /** Draws the Tray again from where the camera is now, at the next frame. */
    private void showCamera() {
        scene.setCamera(camera.camera());
        renderer.requestFrame();
    }

    /** Fits the camera's framings to the view's shape; a view not laid out yet keeps the last shape. */
    private void fitAspect() {
        if (view.getWidth() > 0 && view.getHeight() > 0) {
            camera.setAspect(view.getWidth() / view.getHeight());
            showCamera();
        }
    }

    /** Stops the render thread and the physics worker. */
    @Override
    public void close() {
        renderer.close();
        worker.close();
    }
}
