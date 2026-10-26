package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * The Roll UI wired to the roll controller: Layout C ({@link RollUi}) around a Tray, with Enter and the Roll button
 * rolling through the controller and every status shown in the result strip. It is the part of the app the
 * headless UI tests run for real; {@link Skeleton} builds one over the physics worker and the GL Tray, and
 * {@code HeadlessUiTest} over the fake simulator and the stand-in tray.
 *
 * <p>The controller itself asks for JavaFX's pulse, and holds the Queued Roll; this class only wires the view to it:
 * the Roll UI and its result strip, the Notice bell, entry, the keyboard map and Fast-forward.
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread.
 */
final class RollSession {

    private final RollController controller;
    private final RollUi ui;
    private final Notices notices;
    private final RollEntry entry;
    private final FastForward fastForward;

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
        Objects.requireNonNull(tray, "tray");
        // The controller talks to the Tray directly; what follows a Roll hears of it from its state snapshots.
        controller = new RollController(clock, pulses, physics, fxThread,
                new FaceValueGenerator(generators.faceValues()), generators.releases(), throwInto, tray);
        ui = new RollUi(tray.node());
        // The strip is the first to hear of each status; the Roll History, the dice sound and the rest follow it.
        ui.showStatus(controller.state().status());
        controller.addStateListener(RollState.statusChanges(ui::showStatus));
        NoticeBell.attach(ui.bell(), notices);
        // Enter and the Roll button go through entry (#50): errors on Enter, then roll or queue.
        entry = new RollEntry(ui, controller);
        // Ctrl+1 to Ctrl+4, Ctrl+L and Ctrl+R (#62); Ctrl+S is the Saved tab's.
        KeyboardMap.install(ui);
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
     * @return the controller Enter and the Roll button roll through, whose {@link RollState} snapshots everything
     *     that follows a Roll listens to ({@link RollController#addStateListener})
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
     * Fills the Look tab (#57) and sets the stored look through the controller. Each pick there is set through the
     * controller too, which draws it on the Tray under the mid-Roll Look rule ({@link RollController#setLook}) and
     * throws the next Roll into its Tray.
     *
     * @param catalogue the Dice Sets and Trays read at start-up, and the Player Files skipped
     * @param settings the live settings holding the choices
     * @return the tab's content, already in the Look tab
     */
    LookTab attachLook(LookCatalogue catalogue, SettingsStore settings) {
        LookTab look = new LookTab(catalogue, settings, controller::setLook);
        ui.lookTab().setContent(look.node());
        return look;
    }
}
