package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import javafx.animation.AnimationTimer;

/**
 * What advances the playback clock while something plays. In the app it is JavaFX's pulse ({@link #ofPulse});
 * tests use a stand-in that does nothing and advance the clock by hand.
 *
 * <p>Thread contract: the JavaFX Application Thread.
 */
interface Pulses {

    /** Starts advancing the clock, if it isn't already; the time since it was last stopped is not played. */
    void start();

    /** Stops advancing the clock, so an idle app doesn't run a pulse at the monitor's refresh rate. */
    void stop();

    /**
     * Returns pulses from JavaFX's own: an {@link AnimationTimer} feeding each pulse's timestamp to the pacer.
     *
     * @param pacer the pacer that advances the clock at 60 Hz
     * @return the pulses, stopped
     */
    static Pulses ofPulse(PulsePacer pacer) {
        Objects.requireNonNull(pacer, "pacer");
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                pacer.pulse(now);
            }
        };
        return new Pulses() {
            private boolean running;

            @Override
            public void start() {
                if (!running) {
                    running = true;
                    pacer.restart();
                    timer.start();
                }
            }

            @Override
            public void stop() {
                running = false;
                timer.stop();
            }
        };
    }
}
