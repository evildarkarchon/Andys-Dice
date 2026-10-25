package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import java.util.List;
import java.util.Objects;

/**
 * One Throw to be heard: the accepted Attempt's Recording, placed on the playback clock (ADR-0012). Reroll
 * Throws and a queued Roll are just later Throws on the same clock.
 *
 * @param recording the accepted Attempt's Recording; only its contacts make sound
 * @param kinds the Die Kind of each recorded Die, in the Recording's order (for each kind's pitch offset)
 * @param start the playback time, in seconds, at which the Recording's Release plays
 * @param tray what the Tray's floor and walls sound like
 */
public record SoundThrow(Recording recording, List<DieKind> kinds, double start, TraySounds tray) {

    /**
     * Copies the kinds and checks there is one per recorded Die.
     *
     * @throws IllegalArgumentException if the kinds don't match the Recording's Dice or the start isn't finite
     */
    public SoundThrow {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(tray, "tray");
        kinds = List.copyOf(kinds);
        if (kinds.size() != recording.dice()) {
            throw new IllegalArgumentException(kinds.size() + " Die Kinds for a Recording of "
                    + recording.dice() + " Dice");
        }
        if (!Double.isFinite(start)) {
            throw new IllegalArgumentException("A Throw starts at a finite playback time: " + start);
        }
    }
}
