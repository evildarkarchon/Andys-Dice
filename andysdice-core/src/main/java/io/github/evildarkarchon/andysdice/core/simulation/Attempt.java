package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.Objects;
import java.util.Set;

/**
 * The outcome of one Attempt: how it ended, what happened, and which Dice ended cocked.
 *
 * @param ending how the Attempt ended
 * @param recording the motion and contacts up to the end; for an Attempt that didn't come to rest, up to the
 *     moment it stopped
 * @param cockedDice the indices of the Cocked Dice; non-empty exactly when the ending is {@link Ending#COCKED}
 */
public record Attempt(Ending ending, Recording recording, Set<Integer> cockedDice) {

    /**
     * Checks the Cocked Dice agree with the ending and the Recording.
     *
     * @throws IllegalArgumentException if Cocked Dice are named for an ending other than cocked, none are named
     *     for a cocked ending, or an index isn't one of the recorded Dice
     */
    public Attempt {
        Objects.requireNonNull(ending, "ending");
        Objects.requireNonNull(recording, "recording");
        cockedDice = Set.copyOf(cockedDice);
        if (cockedDice.isEmpty() == (ending == Ending.COCKED)) {
            throw new IllegalArgumentException("An Attempt ending " + ending + " can't have Cocked Dice "
                    + cockedDice);
        }
        for (int die : cockedDice) {
            if (die < 0 || die >= recording.dice()) {
                throw new IllegalArgumentException("Cocked Die " + die + " isn't one of the "
                        + recording.dice() + " recorded Dice");
            }
        }
    }
}
