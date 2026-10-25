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
        ui.setOnRoll(controller::roll);
        ui.showStatus(controller.status());
        controller.setOnStatusChanged(status -> {
            ui.showStatus(status);
            // Only playback needs the pulse; once the Dice rest (or nothing will move) and the Tray has stopped
            // moving (the camera settled in), let JavaFX idle.
            if (!(status instanceof RollStatus.Rolling) && !tray.moving()) {
                pulses.stop();
            }
            onStatusChanged.accept(status);
        });
        // After the controller's own listener: once the Roll is over, the Tray may still be moving on the clock
        // (the camera's settle-in after the last Throw), so the pulse stops only when that is done too.
        clock.addListener(c -> {
            if (!controller.underWay() && !tray.moving()) {
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
     * Sets who else is told each time the Roll's status changes, after the result strip has shown it.
     *
     * @param listener the listener; it replaces any earlier one
     */
    void setOnStatusChanged(Consumer<RollStatus> listener) {
        onStatusChanged = Objects.requireNonNull(listener, "listener");
    }
}
