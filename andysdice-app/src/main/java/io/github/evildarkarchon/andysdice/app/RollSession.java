package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The Roll UI wired to the roll controller: Layout C ({@link RollUi}) around a Tray, with Enter and the Roll button
 * rolling through the controller and every status shown in the result strip. It is the part of the app the
 * headless UI tests run for real; {@link Skeleton} builds one over the physics worker and the GL Tray, and
 * {@code HeadlessUiTest} over the fake simulator and the stand-in tray.
 *
 * <p>It also asks for JavaFX's pulse only while a Roll plays: from the moment the Tray is given a chain until the
 * status leaves {@link RollStatus.Rolling} and the Tray reports it has stopped {@link TraySurface#moving moving},
 * so an idle app doesn't pulse at the monitor's refresh rate.
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread.
 */
final class RollSession {

    private final RollController controller;
    private final RollUi ui;
    private final Notices notices = new Notices();
    private final RollQueue queue;
    private final RollEntry entry;
    private final FastForward fastForward;
    private Consumer<RollStatus> onStatusChanged = status -> {};

    /**
     * Wires a Roll UI and its controller.
     *
     * @param clock the playback clock, advanced by {@code pulses} in the app and by hand in tests
     * @param pulses what advances the clock while a Roll plays
     * @param physics where pre-simulation runs
     * @param fxThread runs pre-simulation results on the JavaFX thread ({@code Platform::runLater})
     * @param generators the Face Value and Release generators (sound variation is for the mixer)
     * @param throwInto the chosen Tray (ADR-0010), whose shape and materials every Throw is simulated in
     * @param tray where the Roll is drawn, and the node Layout C shows it in
     */
    RollSession(PlaybackClock clock, Pulses pulses, RollController.PhysicsJobs physics, Executor fxThread,
            Generators generators, Tray throwInto, TraySurface tray) {
        Objects.requireNonNull(pulses, "pulses");
        Objects.requireNonNull(generators, "generators");
        Objects.requireNonNull(tray, "tray");
        controller = new RollController(clock, physics, fxThread, new FaceValueGenerator(generators.faceValues()),
                generators.releases(), throwInto, new RollController.TrayView() {
                    @Override
                    public void show(ChainPlayback chain) {
                        tray.show(chain);
                        pulses.start();
                    }

                    @Override
                    public void showTime(double seconds) {
                        tray.showTime(seconds);
                    }
                });
        ui = new RollUi(tray.node());
        NoticeBell.attach(ui.bell(), notices);
        // Enter and the Roll button go through entry (#50): errors on Enter, then roll or queue.
        queue = new RollQueue(controller, clock);
        entry = new RollEntry(ui, queue);
        ui.showStatus(controller.status());
        controller.setOnStatusChanged(status -> {
            ui.showStatus(status);
            // Before the pulse check: a reveal (or failure) starts a Queued Roll's beat.
            queue.statusChanged(status);
            if (queue.waiting() && !controller.underWay()) {
                // The beat is timed on the clock, so the pulse must run through it even with nothing moving (a
                // Roll that failed in pre-simulation may never have started it).
                pulses.start();
            } else if (!(status instanceof RollStatus.Rolling) && !tray.moving()) {
                // Only playback needs the pulse; once the Dice rest (or nothing will move) and the Tray has
                // stopped moving (the camera settled in), let JavaFX idle.
                pulses.stop();
            }
            onStatusChanged.accept(status);
        });
        // After the controller's own listener: once the Roll is over, the Tray may still be moving on the clock
        // (the camera's settle-in after the last Throw), so the pulse stops only when that is done too, and no
        // Queued Roll is waiting out its beat.
        clock.addListener(c -> {
            if (!controller.underWay() && !tray.moving() && !queue.waiting()) {
                pulses.stop();
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
        queue.roll(expression);
    }

    /**
     * Sets who else is told each time the Roll's status changes, after the result strip has shown it.
     *
     * @param listener the listener; it replaces any earlier one
     */
    void setOnStatusChanged(Consumer<RollStatus> listener) {
        onStatusChanged = Objects.requireNonNull(listener, "listener");
    }
}
