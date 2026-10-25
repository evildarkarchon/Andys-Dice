package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.Objects;

/**
 * A Frozen Die: one left lying still during a Reroll Throw or a Cocked Rethrow, which the thrown Dice can
 * strike but never move (ADR-0006).
 *
 * @param body the Die's solid
 * @param pose where it lies, typically its resting pose from the previous Throw's Recording
 */
public record FrozenDie(DieBody body, Pose pose) {

    /**
     * Checks both parts are present.
     *
     * @throws NullPointerException if a part is {@code null}
     */
    public FrozenDie {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(pose, "pose");
    }
}
