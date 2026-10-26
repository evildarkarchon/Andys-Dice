package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.FrameView;
import io.github.evildarkarchon.andysdice.render.GlDriver;
import io.github.evildarkarchon.andysdice.render.GpuBench;
import io.github.evildarkarchon.andysdice.render.LightRig;
import io.github.evildarkarchon.andysdice.render.QualityPreset;
import io.github.evildarkarchon.andysdice.render.RenderThread;
import io.github.evildarkarchon.andysdice.render.camera.Framing;
import io.github.evildarkarchon.andysdice.render.camera.TrayCamera;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Node;

/**
 * The GL Tray: the app's adapter behind the Tray seam ({@link TraySurface}), the sibling of the stand-in tray the
 * headless UI tests mount. It is the GL renderer's {@link FrameView}, fed by the render thread through ADR-0002's
 * frame transport, drawing every Die of each Roll through the {@link DiceScene} (#38), seen through the settle-in
 * {@link TrayCamera} on the playback clock, which the Player can orbit and zoom (#46).
 *
 * <p>It owns everything that touches GL: starting and closing the render thread and watching for its death, the
 * Dice scene, the frame view, the camera with its gestures and aspect fitting, the render-target cap and the
 * slow-GPU watch (ADR-0014), the Quality Preset, and where sound is panned from. To the bench and the first-motion
 * timer it offers narrow hooks instead of those internals: {@link #onFramePresented}, {@link #driver()} and
 * {@link #gpuBench()}.
 *
 * <p>Thread contract: started, driven and closed on the JavaFX Application Thread, like the controller that drives
 * it; {@link #gpuBench()} is the exception.
 */
final class GlTray implements TraySurface, AutoCloseable {

    /**
     * Reduced motion follows the system (spec §3.2), read as each pose is worked out, so a change applies at once.
     * The camera and the reroll pickup both read it.
     */
    private static final BooleanSupplier REDUCED_MOTION = () -> Platform.getPreferences().isReducedMotion();

    private final RenderThread renderer;
    private final DiceScene scene;
    private final FrameView view;
    private final TrayCamera camera;
    private final RenderTargetCap.Capping capping;
    private final SlowGpuWatch slowGpu;
    // Read by the slow-GPU watch when it judges a Throw; set on the JavaFX thread.
    private volatile QualityPreset quality = QualityPreset.DEFAULT;
    private ChainPlayback showing;
    // Null until panTo: then told each camera the Tray is drawn from, for panning (#59).
    private Consumer<? super CameraPanning> panning;
    // Set by attachGestures: a second attach would stack a second set of handlers on the view.
    private boolean gesturesAttached;

    /** The Throw of a chain in motion at a frame, as the slow-GPU watch tells Throws apart (by chain identity). */
    private record ThrowInMotion(ChainPlayback chain, int throwIndex) {}

    private GlTray(RenderThread renderer, DiceScene scene, FrameView view, TrayCamera camera,
            RenderTargetCap.Capping capping, Notices notices) {
        this.renderer = renderer;
        this.scene = scene;
        this.view = view;
        this.camera = camera;
        this.capping = capping;
        // Every frame's GPU time, measured on the render thread, judged per Throw (ADR-0014). It never changes the
        // preset; it only reads it to suggest the one below.
        this.slowGpu = new SlowGpuWatch(notices::post, () -> quality);
        renderer.setOnGpuTime(slowGpu::frame);
        // Every framing fits the view's shape.
        view.widthProperty().addListener((property, was, now) -> fitAspect());
        view.heightProperty().addListener((property, was, now) -> fitAspect());
    }

    /**
     * Starts the GL context on the render thread, eagerly, so no OpenGL 3.3 fails here, at start-up, and draws the
     * first-run Tray, empty, in the starting look.
     *
     * @param clock the playback clock the settle-in camera eases on
     * @param failures told of the render thread's death, which is also how a lost GL context ends the session
     *     (spec §3.2 "Runtime failure")
     * @param notices where the slow-GPU watch posts its Notice
     * @param tray the Tray the scene starts with, before the Look tab (#57) shows the Player's choice
     * @param lightRig the Light Rig the scene starts with, before the Look tab shows the Player's choice
     * @return the running GL Tray
     * @throws io.github.evildarkarchon.andysdice.render.GlUnavailableException if there is no OpenGL 3.3 core
     */
    static GlTray start(PlaybackClock clock, FailureGuard failures, Notices notices, Tray tray, LightRig lightRig) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(failures, "failures");
        Objects.requireNonNull(notices, "notices");
        Objects.requireNonNull(tray, "tray");
        Objects.requireNonNull(lightRig, "lightRig");
        DiceScene scene = new DiceScene();
        scene.setTray(tray);
        scene.setLightRig(lightRig);
        // The default until the Settings tab applies the Player's saved preset (#60).
        scene.setQualityPreset(QualityPreset.DEFAULT);
        // Reduced motion follows the system (spec §3.2), read as each pose is worked out, so a change applies at once.
        TrayCamera camera = new TrayCamera(clock, Framing.of(tray.geometry()), REDUCED_MOTION);
        scene.setCamera(camera.camera());
        FrameView view = new FrameView();
        RenderThread renderer = RenderThread.start(view.transport(), scene);
        // A render thread that ends before close() is dead, whatever killed it; nothing restarts it (ADR-0002).
        failures.watch(FailureGuard.RENDER_THREAD, renderer.stopped());
        // The view reports its physical size (and again after a move to a monitor with another scale); the render
        // target follows it under the cap below High (ADR-0014), and JavaFX scales a capped frame up.
        RenderTargetCap.Capping capping = RenderTargetCap.capping(renderer::resize, QualityPreset.DEFAULT);
        view.setOnTargetSizeChanged(capping);
        // The first-run Tray is empty; draw it once so the view isn't blank until the first Roll.
        renderer.requestFrame();
        return new GlTray(renderer, scene, view, camera, capping, notices);
    }

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
    public void showLook(Look look) {
        // The scene takes each part on the render thread at its next frame; the camera reframes for the new Tray's
        // walls at once.
        scene.setLook(look.diceSet(), look.tray(), look.lightRig());
        camera.setFraming(Framing.of(look.tray().geometry()));
        showCamera();
    }

    @Override
    public void advanced() {
        // The controller tells of each advance after drawing its Dice, so the camera never reaches the render thread
        // ahead of them. Once the last Throw is at rest the controller draws nothing more, but the camera may still
        // be easing. Redrawn even when draw has just handed over this camera with the Dice: coming second, it can't
        // tear, and it keeps no bookkeeping.
        showCamera();
    }

    @Override
    public boolean moving() {
        // Once the last Throw is at rest the camera is still settling in on the Dice (and while the next Roll
        // pre-simulates, the last one's settle-in may still be easing).
        return camera.moving();
    }

    /**
     * Lets the Player orbit and zoom the camera from the Tray at any time, and reset it with a double-click while
     * the Tray is resting. Called once, by whoever knows when a Roll is under way: the GL Tray is started before the
     * Roll session that drives it, so it can't ask the session itself.
     *
     * @param resting tells whether no Roll is under way, when a double-click may reset the camera
     * @throws IllegalStateException if the gestures were already attached; they are attached once
     */
    void attachGestures(BooleanSupplier resting) {
        Objects.requireNonNull(resting, "resting");
        if (gesturesAttached) {
            throw new IllegalStateException("the GL Tray already has its camera gestures");
        }
        gesturesAttached = true;
        CameraGestures.attach(view, camera, resting, this::showCamera);
    }

    /**
     * Applies a Quality Preset (ADR-0014): every renderer lever, in one message to the scene, the render-target cap,
     * which High lifts (the target is resized from the view's last size), and the preset the slow-GPU watch suggests
     * the one below of. The Settings tab calls this at start-up with the saved preset and whenever the Player picks
     * another; nothing else does.
     *
     * @param preset the Quality Preset
     */
    void setQualityPreset(QualityPreset preset) {
        quality = Objects.requireNonNull(preset, "preset");
        scene.setQualityPreset(preset);
        capping.setPreset(preset);
        renderer.requestFrame();
    }

    /**
     * Tells {@code listener} how the camera the Tray is drawn from maps points across the screen: at once, and again
     * on every camera move. Sound pans by it (#59); the GL Tray knows nothing of what listens.
     *
     * @param listener told each new projection, on the JavaFX thread
     * @throws IllegalStateException if a listener was already set; there is one, set once
     */
    void panTo(Consumer<? super CameraPanning> listener) {
        Objects.requireNonNull(listener, "listener");
        if (panning != null) {
            throw new IllegalStateException("the GL Tray already pans to a listener");
        }
        panning = listener;
        pan();
    }

    /**
     * Sets who is told each time a frame is put on screen, for timing Enter to first motion (ADR-0014).
     *
     * @param callback run on the JavaFX thread right after each frame is shown; it must not block. {@code null}
     *     clears it. It replaces any earlier callback.
     */
    void onFramePresented(Runnable callback) {
        view.setOnFramePresented(callback);
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
     * Runs {@code --bench}'s GPU bench (ADR-0014): it draws its own scenes on the render thread and times them.
     * It blocks until the bench is over, so the caller chooses the thread; never the JavaFX thread, which must keep
     * presenting the frames the bench draws.
     *
     * @return the bench's report
     */
    GpuBench.Report gpuBench() {
        return GpuBench.run(renderer, scene);
    }

    /**
     * Returns the Roll on the Tray, for the {@code gl} tests.
     *
     * @return the chain of Throws last shown, or {@code null} before the first
     */
    ChainPlayback showing() {
        return showing;
    }

    /**
     * Returns the settle-in camera, for the {@code gl} tests.
     *
     * @return the camera the Tray is drawn from
     */
    TrayCamera camera() {
        return camera;
    }

    /** Stops the render thread. */
    @Override
    public void close() {
        renderer.close();
    }

    /**
     * Draws every Die of the chain at a moment of its playback, interpolated, at the next frame: the reroll
     * pickup's glow and lift included, as reduced motion has them now.
     */
    private void draw(ChainPlayback chain, double seconds) {
        // The frames measured from here on count toward the Throw in motion now, if any (the slow-GPU Notice).
        int moving = chain.throwInMotion(seconds);
        slowGpu.showing(moving < 0 ? null : new ThrowInMotion(chain, moving));
        // The same frame's camera, in one hand-over with the Dice, so the two never reach the render thread a frame
        // apart.
        scene.setDiceAndCamera(chain.diceAt(seconds, REDUCED_MOTION.getAsBoolean()), camera.camera());
        pan();
        renderer.requestFrame();
    }

    /** Draws the Tray again from where the camera is now, at the next frame. */
    private void showCamera() {
        scene.setCamera(camera.camera());
        pan();
        renderer.requestFrame();
    }

    /** Tells the panning listener how the camera the Tray is drawn from now maps points across the screen. */
    private void pan() {
        if (panning != null) {
            // Before the first layout, the first-run window's shape.
            double aspect = viewAspect().orElse(MainWindow.DEFAULT_WIDTH / MainWindow.DEFAULT_HEIGHT);
            panning.accept(CameraPanning.of(camera.camera(), aspect));
        }
    }

    /** Fits the camera's framings to the view's shape; a view not laid out yet keeps the last shape. */
    private void fitAspect() {
        viewAspect().ifPresent(aspect -> {
            camera.setAspect(aspect);
            showCamera();
        });
    }

    /** Returns the view's width over its height, or nothing while it has no size yet (not laid out). */
    private OptionalDouble viewAspect() {
        return view.getWidth() > 0 && view.getHeight() > 0 ? OptionalDouble.of(view.getWidth() / view.getHeight())
                : OptionalDouble.empty();
    }
}
