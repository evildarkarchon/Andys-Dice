package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;

/**
 * Told what the mixer does with each contact event, on the audio thread, while a block renders. Tests and
 * diagnostics use it; it must return quickly and must not call back into the mixer.
 */
public interface ImpactObserver {

    /** An observer that ignores everything. */
    ImpactObserver NONE = new ImpactObserver() {
    };

    /**
     * An impact has started.
     *
     * @param impact what started, and where in the output
     */
    default void started(Impact impact) {
    }

    /**
     * An event was dropped and will never sound.
     *
     * @param soundThrow the Throw it belongs to
     * @param event the event
     * @param reason why
     */
    default void dropped(SoundThrow soundThrow, ContactEvent event, DropReason reason) {
    }
}
