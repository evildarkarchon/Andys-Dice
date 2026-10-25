package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.List;
import java.util.Objects;

/**
 * Everything one Attempt needs: the Dice to throw, how they are released, the Frozen Dice lying in the Tray,
 * and the Tray's geometry and materials. Units are centimetres (ADR-0003).
 *
 * @param dice the Dice to throw; the Recording and contact events index them in this order
 * @param release how they leave the hand
 * @param frozen the Frozen Dice, static where they lie; empty for a first Throw
 * @param tray the Tray's shape
 * @param materials the Tray's physics materials
 */
public record AttemptRequest(List<DieBody> dice, Release release, List<FrozenDie> frozen, TrayGeometry tray,
        TrayMaterials materials) {

    /**
     * Checks the request names at least one Die and, for a stated Release, starts each one.
     *
     * @throws IllegalArgumentException if there are no Dice, or a {@link Release.Given} doesn't have exactly one
     *     start per Die
     */
    public AttemptRequest {
        dice = List.copyOf(dice);
        frozen = List.copyOf(frozen);
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(tray, "tray");
        Objects.requireNonNull(materials, "materials");
        if (dice.isEmpty()) {
            throw new IllegalArgumentException("An Attempt must throw at least one Die");
        }
        if (release instanceof Release.Given(List<DieStart> starts) && starts.size() != dice.size()) {
            throw new IllegalArgumentException(
                    "A given Release has " + starts.size() + " starts for " + dice.size() + " Dice");
        }
    }
}
