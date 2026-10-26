package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The Roll UI wired to the roll controller: Layout C ({@link RollUi}) around a Tray, with Enter and the Roll button
 * rolling through the controller and every status shown in the result strip. It is the part of the app the
 * headless UI tests run for real; {@link Skeleton} builds one over the physics worker and the GL Tray, and
 * {@code HeadlessUiTest} over the fake simulator and the stand-in tray.
 *
 * <p>The controller itself asks for JavaFX's pulse, and holds the Queued Roll; this class only wires the view to it.
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread.
 */
final class RollSession {

    private final RollController controller;
    private final RollUi ui;
    private final Notices notices;
    private final RollEntry entry;
    private final FastForward fastForward;
    /** The Tray seam: where the Roll is drawn, and the look it is drawn in (#57). */
    private final TraySurface tray;
    /**
     * Everything else that follows the Roll's status (the Roll History, #55; the smoke test; the bench's first-motion
     * timer), told after the strip.
     */
    private final List<Consumer<RollStatus>> statusListeners = new ArrayList<>();
    private ChainShown onChainShown = (chain, start) -> {};

    /** Told each time a Roll's chain of Throws starts to play on the Tray. */
    @FunctionalInterface
    interface ChainShown {
        /**
         * A chain has been put on the Tray and plays from now.
         *
         * @param chain the chain of Throws
         * @param start the playback clock's time at its first Release
         */
        void shown(ChainPlayback chain, double start);
    }

    /**
     * Wires a Roll UI and its controller.
     *
     * @param clock the playback clock, advanced by {@code pulses} in the app and by hand in tests
     * @param pulses what advances the clock while a Roll plays; the controller starts and stops it
     * @param physics where pre-simulation runs
     * @param fxThread runs pre-simulation results on the JavaFX thread ({@code Platform::runLater})
     * @param generators the Face Value and Release generators (sound variation is for the mixer)
     * @param throwInto the chosen Tray (ADR-0010), whose shape and materials every Throw is simulated in
     * @param tray where the Roll is drawn, and the node Layout C shows it in
     */
    RollSession(PlaybackClock clock, Pulses pulses, RollController.PhysicsJobs physics, Executor fxThread,
            Generators generators, Tray throwInto, TraySurface tray) {
        this(clock, pulses, physics, fxThread, generators, throwInto, tray, new Notices());
    }

    /**
     * Wires a Roll UI and its controller around the app's one Notice service, made before the session so that what
     * start-up posts (a settings file set aside, #54) shows behind the same bell.
     *
     * @param clock the playback clock
     * @param pulses what advances the clock while a Roll plays
     * @param physics where pre-simulation runs
     * @param fxThread runs pre-simulation results on the JavaFX thread
     * @param generators the Face Value and Release generators
     * @param throwInto the chosen Tray
     * @param tray where the Roll is drawn
     * @param notices the app's Notices, which the bell shows
     */
    RollSession(PlaybackClock clock, Pulses pulses, RollController.PhysicsJobs physics, Executor fxThread,
            Generators generators, Tray throwInto, TraySurface tray, Notices notices) {
        this.notices = Objects.requireNonNull(notices, "notices");
        Objects.requireNonNull(pulses, "pulses");
        Objects.requireNonNull(generators, "generators");
        this.tray = Objects.requireNonNull(tray, "tray");
        controller = new RollController(clock, pulses, physics, fxThread,
                new FaceValueGenerator(generators.faceValues()), generators.releases(), throwInto,
                new RollController.TrayView() {
                    @Override
                    public void show(ChainPlayback chain) {
                        tray.show(chain);
                        // The controller starts the chain at the clock's time now (#59: its sound is placed there).
                        onChainShown.shown(chain, clock.time());
                    }

                    @Override
                    public void showTime(double seconds) {
                        tray.showTime(seconds);
                    }

                    @Override
                    public boolean moving() {
                        return tray.moving();
                    }
                });
        ui = new RollUi(tray.node());
        NoticeBell.attach(ui.bell(), notices);
        // Enter and the Roll button go through entry (#50): errors on Enter, then roll or queue.
        entry = new RollEntry(ui, controller);
        // #53's panel ends the session: a Queued Roll must not start behind it once its beat is up, nor may a Roll
        // in motion (or still pre-simulating) finish behind it: it was never shown, so it is lost rather than
        // revealed or kept in the Roll History (ADR-0007).
        ui.addFailureListener(controller::abandon);
        // Ctrl+1 to Ctrl+4, Ctrl+L and Ctrl+R (#62); Ctrl+S is the Saved tab's.
        KeyboardMap.install(ui);
        ui.showStatus(controller.status());
        controller.setOnStatusChanged(status -> {
            ui.showStatus(status);
            for (Consumer<RollStatus> listener : statusListeners) {
                listener.accept(status);
            }
        });
        // Shift held alone speeds the clock up (#51); everything that plays follows the clock.
        fastForward = new FastForward(clock);
        fastForward.install(ui);
    }

    /**
     * Returns Fast-forward, which speeds the playback clock up while Shift is held alone.
     *
     * @return the session's Fast-forward
     */
    FastForward fastForward() {
        return fastForward;
    }

    /**
     * Returns the roll controller.
     *
     * @return the controller Enter and the Roll button roll through
     */
    RollController controller() {
        return controller;
    }

    /**
     * Returns the window content.
     *
     * @return the Layout C root
     */
    RollUi ui() {
        return ui;
    }

    /**
     * Returns the app's Notice service, whose Notices the bell shows. Post to it from any thread.
     *
     * @return the Notices
     */
    Notices notices() {
        return notices;
    }

    /**
     * Returns entry in the box: the error popup, the Queued Roll and its chip.
     *
     * @return the entry wiring Enter and the Roll button go through
     */
    RollEntry entry() {
        return entry;
    }

    /**
     * Rolls a Roll Expression that already parses, from a Saved Roll or a Roll History entry: at once, or as the
     * Queued Roll while Dice move, exactly as Enter in the box does (spec §3.2 "Entry").
     *
     * @param expression the Roll Expression as written
     */
    void roll(String expression) {
        controller.roll(expression);
    }

    /**
     * Rolls a Saved Roll's expression as {@link #roll(String)} does, recording the Roll with the Saved Roll's name
     * (spec §3.2 "Saved tab", #56): the result strip shows it and the Roll History keeps it.
     *
     * @param expression the Roll Expression as written; it already parses
     * @param savedRollName the Saved Roll's name
     */
    void roll(String expression, String savedRollName) {
        controller.roll(expression, Optional.of(savedRollName));
    }

    /**
     * Fills the Look tab (#57) and draws the Tray in the stored look at once. Each pick there redraws the Tray
     * through the Tray seam and becomes the Tray the next Roll is thrown into.
     *
     * @param catalogue the Dice Sets and Trays read at start-up, and the Player Files skipped
     * @param settings the live settings holding the choices
     * @return the tab's content, already in the Look tab
     */
    LookTab attachLook(LookCatalogue catalogue, SettingsStore settings) {
        LookTab look = new LookTab(catalogue, settings, chosen -> {
            tray.showLook(chosen);
            controller.setTray(chosen.tray());
        });
        ui.lookTab().setContent(look.node());
        return look;
    }

    /**
     * Sets who is told each time a Roll's chain starts to play, after the Tray has been given it: the dice sound
     * (#59, {@link Clatter}).
     *
     * @param listener the listener; it replaces any earlier one
     */
    void setOnChainShown(ChainShown listener) {
        onChainShown = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Adds a listener told each time the Roll's status changes, after the result strip has shown it. Listeners are
     * never replaced, so the app's own pieces (the Roll History joining a revealed Roll, #55) and the smoke test
     * don't displace each other.
     *
     * @param listener told every status, in the order added
     */
    void addStatusListener(Consumer<RollStatus> listener) {
        statusListeners.add(Objects.requireNonNull(listener, "listener"));
    }
}
