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
import io.github.evildarkarchon.andysdice.render.camera.TrayCamera;
import io.github.evildarkarchon.andysdice.sound.SoundOutput;
import java.util.Objects;
import javafx.application.Platform;

/**
 * The walking skeleton wired together (spec slice S3): the physics worker with Jolt, the render thread drawing
 * the Dice scene into a {@link FrameView}, the playback clock, and the roll controller between them, under the
 * Layout C {@link RollUi} (#48). The controller and the UI are wired by a {@link RollSession}, the part the
 * headless UI tests run with a stand-in Tray; this class adds the {@link GlTray} and the physics worker behind it.
 *
 * <p>The clock and what advances it are handed in, so the app drives it from JavaFX's pulse and tests advance
 * it by hand. The app opens the window around {@link #pane()}. Any Roll Expression rolls: the controller plans
 * the whole chain of Throws on the physics worker, and the GL Tray draws every Die of it through the
 * {@link DiceScene} (#38), seen through the settle-in {@link TrayCamera} on the same clock, which the Player can
 * orbit and zoom (#46).
 *
 * <p>Thread contract: started, used and closed on the JavaFX Application Thread.
 */
final class Skeleton implements AutoCloseable {

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
    private final GlTray tray;
    private final RollSession session;
    private final Generators generators;

    private Skeleton(PhysicsWorker worker, GlTray tray, PlaybackClock clock, Pulses pulses, Generators generators,
            FailureGuard failures, Notices notices) {
        this.worker = worker;
        this.tray = tray;
        this.generators = generators;
        // The GL Tray behind the Tray seam. A pre-simulation job that throws ends the session (spec §3.2 "Runtime
        // failure"), so the worker reports it.
        this.session = new RollSession(clock, pulses, failures.reporting(worker::submit), Platform::runLater,
                generators, TRAY, tray, notices);
        // A double-click resets the camera only while no Roll is under way (Pre-simulating or Playing); orbit and
        // zoom work at any time.
        tray.attachGestures(() -> !session.controller().state().underWay());
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
        GlTray tray;
        try {
            // Physics and rendering both use the chosen Tray: the controller throws into it, the scene draws it.
            tray = GlTray.start(clock, failures, notices, TRAY, LIGHT_RIG);
        } catch (RuntimeException e) {
            worker.close();
            throw e;
        }
        return new Skeleton(worker, tray, clock, pulses, generators, failures, notices);
    }

    /**
     * Applies a Quality Preset (ADR-0014) on the GL Tray: every renderer lever, in one message to the scene, and the
     * render-target cap, which High lifts (the target is resized from the view's last size). The Settings tab calls
     * this at start-up with the saved preset and whenever the Player picks another; nothing else does.
     *
     * @param preset the Quality Preset
     */
    void setQualityPreset(QualityPreset preset) {
        tray.setQualityPreset(preset);
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
     * Returns the GL Tray the Roll session drives, for what the app, the bench and the first-motion timer attach to
     * it: the driver report, the Quality Preset, sound panning, the frame-presented callback and the GPU bench.
     *
     * @return the GL Tray
     */
    GlTray glTray() {
        return tray;
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
        return tray.view();
    }

    /**
     * Returns the render thread, for {@code --bench}'s GPU bench, which draws its own scenes on it.
     *
     * @return the app's one render thread
     */
    RenderThread renderer() {
        return tray.renderer();
    }

    /**
     * Returns the scene the render thread draws, for {@code --bench}'s GPU bench.
     *
     * @return the Dice scene
     */
    DiceScene scene() {
        return tray.scene();
    }

    /**
     * Returns what the OpenGL driver reported about the render thread's context, for the log.
     *
     * @return the context's renderer, vendor and version
     */
    GlDriver driver() {
        return tray.driver();
    }

    /**
     * Returns the Roll on the Tray.
     *
     * @return the chain of Throws last shown, or {@code null} before the first
     */
    ChainPlayback showing() {
        return tray.showing();
    }

    /**
     * Returns the settle-in camera.
     *
     * @return the camera the Tray is drawn from
     */
    TrayCamera camera() {
        return tray.camera();
    }

    /**
     * Makes the Rolls heard (#59): every Throw's contacts go to the sound output as the chain starts to play, in the
     * Tray's sound, panned by where each appears from the camera the Tray is drawn from.
     *
     * @param output the app's sound output
     */
    void attachSound(SoundOutput output) {
        Objects.requireNonNull(output, "output");
        // Each Roll is heard in the Tray its chain carries (#67), the one it was thrown into.
        Clatter.attach(session.controller(), output);
        tray.panTo(output::setProjection);
    }

    /** Stops the GL Tray's render thread, then the physics worker. */
    @Override
    public void close() {
        tray.close();
        worker.close();
    }
}
