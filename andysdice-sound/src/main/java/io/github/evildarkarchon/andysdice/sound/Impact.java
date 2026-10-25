package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;

/**
 * One impact the mixer started: a contact event made audible.
 *
 * @param soundThrow the Throw it belongs to
 * @param event the contact event it plays
 * @param frame the output frame, counted from the mixer's first block, at which it starts
 * @param category the category its slice came from
 * @param slice the index of its slice within the category
 * @param gain its linear gain, impulse and gain jitter included
 * @param semitones its pitch shift: the Die Kind's offset plus the pitch jitter
 * @param left the equal-power left channel gain from its pan
 * @param right the equal-power right channel gain from its pan
 */
public record Impact(SoundThrow soundThrow, ContactEvent event, long frame, SoundCategory category, int slice,
        double gain, double semitones, double left, double right) {
}
