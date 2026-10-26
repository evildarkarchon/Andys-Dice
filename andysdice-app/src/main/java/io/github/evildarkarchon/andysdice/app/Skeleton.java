package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.physics.PhysicsWorker;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.FrameView;
import io.github.evildarkarchon.andysdice.render.GlDriver;
import io.github.evildarkarchon.andysdice.render.LightRig;
import io.github.evildarkarchon.andysdice.render.QualityPreset;
import io.github.evildarkarchon.andysdice.render.RenderThread;
import io.github.evildarkarchon.andysdice.render.camera.Framing;
import io.github.evildarkarchon.andysdice.render.camera.TrayCamera;
import io.github.evildarkarchon.andysdice.sound.SoundOutput;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
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
     * Reduced motion follows the system (spec §3.2), read as each pose is worked out, so a change applies at once.
     * The camera and the reroll pickup both read it.
     */
    private static final BooleanSupplier REDUCED_MOTION = () -> Platform.getPreferences().isReducedMotion();

    /**
     * The Tray the scene starts with, before the Look tab (#57) shows the Player's choice: the default built-in,
     * green felt in walnut (ADR-0010).
     */
    static final Tray TRAY = BuiltInTrays.defaultTray();

    /**
     * The Light Rig the scene starts with, before the Look tab (#57) shows the Player's choice: the default, Studio
     * (ADR-0010).
     */
    static final LightRig LIGHT_RIG = LightRig.DEFAULT;

    private final PhysicsWorker worker;
    private final RenderThread renderer;
    private final DiceScene scene;
    private final FrameView view;
    private final TrayCamera camera;
    private final RollSession session;
    private final Generators generators;
    private final RenderTargetCap.Capping capping;
    private final SlowGpuWatch slowGpu;
    // Read by the slow-GPU watch when it judges a Throw; set on the JavaFX thread.
    private volatile QualityPreset quality = QualityPreset.DEFAULT;
    private ChainPlayback showing;
    // Null until attachSound: then told each camera the Tray is drawn from, for panning (#59).
    private SoundOutput sound;

    /** The Throw of a chain in motion at a frame, as the slow-GPU watch tells Throws apart (by chain identity). */
    private record ThrowInMotion(ChainPlayback chain, int throwIndex) {}

    private Skeleton(PhysicsWorker worker, RenderThread renderer, DiceScene scene, FrameView view,
            TrayCamera camera, RenderTargetCap.Capping capping, PlaybackClock clock, Pulses pulses,
            Generators generators, FailureGuard failures, Notices notices) {
        this.worker = worker;
        this.renderer = renderer;
        this.scene = scene;
        this.view = view;
        this.camera = camera;
        this.capping = capping;
        this.generators = generators;
        // Every frame's GPU time, measured on the render thread, judged per Throw (ADR-0014). It never changes the
        // preset; it only reads it to suggest the one below.
        this.slowGpu = new SlowGpuWatch(notices::post, () -> quality);
        renderer.setOnGpuTime(slowGpu::frame);
        // The GL Tray behind the Tray seam: the frame view, fed by the render thread through the transport. A
        // pre-simulation job that throws ends the session (spec §3.2 "Runtime failure"), so the worker reports it.
        this.session = new RollSession(clock, pulses, failures.reporting(worker::submit), Platform::runLater,
                generators, TRAY,
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
                    public void showLook(Look look) {
                        // The scene takes each part on the render thread at its next frame; the camera reframes
                        // for the new Tray's walls at once.
                        scene.setLook(look.diceSet(), look.tray(), look.lightRig());
                        camera.setFraming(Framing.of(look.tray().geometry()));
                        showCamera();
                    }

                    @Override
                    public boolean moving() {
                        // Once the last Throw is at rest the camera is still settling in on the Dice (and while
                        // the next Roll pre-simulates, the last one's settle-in may still be easing).
                        return camera.moving();
                    }
                }, notices);
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
        // No panel to show a failure in: an unattached guard only logs it.
        return start(clock, pulses, generators, new FailureGuard(null));
    }

    /**
     * Starts Jolt and the GL context as {@link #start(PlaybackClock, Pulses, Generators)} does, reporting what
     * ends the session to {@code failures}: a pre-simulation job that throws, and the render thread's death,
     * which is also how a lost GL context ends it (spec §3.2 "Runtime failure").
     *
     * @param clock the playback clock
     * @param pulses what advances the clock while a Throw plays
     * @param generators the Face Value, Release and sound variation generators, each seeded on its own
     * @param failures where failures that end the session go
     * @return the running skeleton
     * @throws io.github.evildarkarchon.andysdice.physics.JoltStartupException if Jolt can't start
     * @throws io.github.evildarkarchon.andysdice.render.GlUnavailableException if there is no OpenGL 3.3 core
     */
    static Skeleton start(PlaybackClock clock, Pulses pulses, Generators generators, FailureGuard failures) {
        return start(clock, pulses, generators, failures, new Notices());
    }

    /**
     * As {@link #start(PlaybackClock, Pulses, Generators, FailureGuard)}, around the app's one Notice service, made
     * before the skeleton so that what start-up posts (a settings file set aside, #54) shows behind the same bell.
     *
     * @param clock the playback clock
     * @param pulses what advances the clock while a Throw plays
     * @param generators the Face Value, Release and sound variation generators, each seeded on its own
     * @param failures where failures that end the session go
     * @param notices the app's Notices
     * @return the running skeleton
     * @throws io.github.evildarkarchon.andysdice.physics.JoltStartupException if Jolt can't start
     * @throws io.github.evildarkarchon.andysdice.render.GlUnavailableException if there is no OpenGL 3.3 core
     */
    static Skeleton start(PlaybackClock clock, Pulses pulses, Generators generators, FailureGuard failures,
            Notices notices) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(notices, "notices");
        Objects.requireNonNull(pulses, "pulses");
        Objects.requireNonNull(generators, "generators");
        Objects.requireNonNull(failures, "failures");
        PhysicsWorker worker = PhysicsWorker.start();
        DiceScene scene = new DiceScene();
        // Physics and rendering both use the chosen Tray: the controller throws into it, the scene draws it.
        scene.setTray(TRAY);
        scene.setLightRig(LIGHT_RIG);
        // The default until the Settings tab applies the Player's saved preset (#60).
        scene.setQualityPreset(QualityPreset.DEFAULT);
        // Reduced motion follows the system (spec §3.2), read as each pose is worked out, so a change applies at once.
        TrayCamera camera = new TrayCamera(clock, Framing.of(TRAY.geometry()), REDUCED_MOTION);
        scene.setCamera(camera.camera());
        FrameView view = new FrameView();
        RenderThread renderer;
        try {
            renderer = RenderThread.start(view.transport(), scene);
        } catch (RuntimeException e) {
            worker.close();
            throw e;
        }
        // A render thread that ends before close() is dead, whatever killed it; nothing restarts it (ADR-0002).
        failures.watch(FailureGuard.RENDER_THREAD, renderer.stopped());
        // The view reports its physical size (and again after a move to a monitor with another scale); the render
        // target follows it under the cap below High (ADR-0014), and JavaFX scales a capped frame up.
        RenderTargetCap.Capping capping = RenderTargetCap.capping(renderer::resize, QualityPreset.DEFAULT);
        view.setOnTargetSizeChanged(capping);
        // The first-run Tray is empty; draw it once so the view isn't blank until the first Roll.
        renderer.requestFrame();
        return new Skeleton(worker, renderer, scene, view, camera, capping, clock, pulses, generators, failures,
                notices);
    }

    /**
     * Applies a Quality Preset (ADR-0014): every renderer lever, in one message to the scene, and the render-target
     * cap, which High lifts (the target is resized from the view's last size). The Settings tab calls this at
     * start-up with the saved preset and whenever the Player picks another; nothing else does.
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
     * Returns the app's generators.
     *
     * @return the generators the skeleton was started with; sound variation is the sound output's (#59)
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
     * Returns the Roll UI wired to its controller, for the pieces the app adds around it (the History tab, #55).
     *
     * @return the session
     */
    RollSession session() {
        return session;
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
     * Fills the Look tab (#57) and draws the Tray in the stored look: the Dice Set, Tray and Light Rig picked
     * there reach the scene, the camera and the next Roll's physics.
     *
     * @param catalogue the Dice Sets and Trays read at start-up, and the Player Files skipped
     * @param settings the live settings holding the choices
     * @return the Look tab's content
     */
    LookTab attachLook(LookCatalogue catalogue, SettingsStore settings) {
        return session.attachLook(catalogue, settings);
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
     * Returns the render thread, for {@code --bench}'s GPU bench, which draws its own scenes on it.
     *
     * @return the app's one render thread
     */
    RenderThread renderer() {
        return renderer;
    }

    /**
     * Returns the scene the render thread draws, for {@code --bench}'s GPU bench.
     *
     * @return the Dice scene
     */
    DiceScene scene() {
        return scene;
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
        panSound();
        renderer.requestFrame();
    }

    /** Draws the Tray again from where the camera is now, at the next frame. */
    private void showCamera() {
        scene.setCamera(camera.camera());
        panSound();
        renderer.requestFrame();
    }

    /**
     * Makes the Rolls heard (#59): every Throw's contacts go to the sound output as the chain starts to play, in the
     * Tray's sound, panned by where each appears from the camera the Tray is drawn from.
     *
     * @param output the app's sound output
     */
    void attachSound(SoundOutput output) {
        sound = Objects.requireNonNull(output, "output");
        // Each Roll is heard in the Tray its chain carries (#67), the one it was thrown into.
        Clatter.attach(session, output);
        panSound();
    }

    /** Tells the sound output how the camera the Tray is drawn from now maps points across the screen. */
    private void panSound() {
        if (sound != null) {
            // Before the first layout, the first-run window's shape.
            double aspect = viewAspect().orElse(MainWindow.DEFAULT_WIDTH / MainWindow.DEFAULT_HEIGHT);
            sound.setProjection(CameraPanning.of(camera.camera(), aspect));
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

    /** Stops the render thread and the physics worker. */
    @Override
    public void close() {
        renderer.close();
        worker.close();
    }
}
