package io.github.evildarkarchon.andysdice.core.planner;

import io.github.evildarkarchon.andysdice.core.geometry.NumberingRotation;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One Throw of a planned Roll's chain: which Dice it throws, which lie frozen, the accepted Attempt's Recording,
 * and how each thrown Die's numbering is turned.
 *
 * <p>Playback draws thrown Die {@code thrown.get(i)} at {@code recording.pose(i, sample)} turned by
 * {@code rotations.get(i)} (see {@link NumberingRotation#applyTo}) for the whole Recording. A Frozen Die lies
 * where the last Throw that moved it left it, still turned by that Throw's rotation.
 *
 * @param kind      why the Throw is made
 * @param thrown    the Dice it throws, as indices into {@link RollPlan.Planned#dice()}, in the Recording's order
 * @param frozen    the Frozen Dice lying still during it, as indices into {@link RollPlan.Planned#dice()}, in the
 *                  order the simulator was given them; empty when every Die is thrown (a first Throw, or a Throw
 *                  of a Roll's only Die)
 * @param recording the accepted Attempt's Recording
 * @param rotations each thrown Die's Numbering Rotation for the Face Value it shows after this Throw, in
 *                  {@code thrown}'s order; a Die left cocked gets one too, for the face nearest up
 * @param attempts  how many silent Attempts were simulated to get the accepted one, from 1 to
 *                  {@link ThrowPlanner#ATTEMPTS_PER_THROW}
 * @param cocked    the Dice the accepted Attempt left cocked, as indices into {@link RollPlan.Planned#dice()};
 *                  empty unless a Cocked Rethrow of exactly these Dice follows
 */
public record PlannedThrow(ThrowKind kind, List<Integer> thrown, List<Integer> frozen, Recording recording,
        List<NumberingRotation> rotations, int attempts, Set<Integer> cocked) {

    /**
     * Checks the parts agree with each other.
     *
     * @throws IllegalArgumentException if no Die is thrown, a Die is both thrown and frozen or listed twice, the
     *                                  Recording or rotations don't match the thrown Dice, the attempt count is
     *                                  out of range, or a Cocked Die wasn't thrown
     */
    public PlannedThrow {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(recording, "recording");
        thrown = List.copyOf(thrown);
        frozen = List.copyOf(frozen);
        rotations = List.copyOf(rotations);
        cocked = Set.copyOf(cocked);
        if (thrown.isEmpty()) {
            throw new IllegalArgumentException("a Throw throws at least one Die");
        }
        Set<Integer> all = new HashSet<>(thrown);
        all.addAll(frozen);
        if (all.size() != thrown.size() + frozen.size()) {
            throw new IllegalArgumentException("a Die is listed twice among thrown " + thrown + " and frozen "
                    + frozen);
        }
        if (recording.dice() != thrown.size() || rotations.size() != thrown.size()) {
            throw new IllegalArgumentException(thrown.size() + " thrown Dice need a Recording and rotations of "
                    + "as many, not " + recording.dice() + " and " + rotations.size());
        }
        if (attempts < 1 || attempts > ThrowPlanner.ATTEMPTS_PER_THROW) {
            throw new IllegalArgumentException("a Throw takes 1 to " + ThrowPlanner.ATTEMPTS_PER_THROW
                    + " Attempts, not " + attempts);
        }
        if (!thrown.containsAll(cocked)) {
            throw new IllegalArgumentException("Cocked Dice " + cocked + " weren't all thrown in " + thrown);
        }
    }
}
