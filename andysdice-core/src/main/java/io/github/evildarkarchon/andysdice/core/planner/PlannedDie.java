package io.github.evildarkarchon.andysdice.core.planner;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.util.Objects;

/**
 * One physical Die in the Tray for a planned Roll. A roll of a Dice Group is one Die, except a Percentile Roll,
 * which is two: the percentile d10 for the tens and a d10 for the units, always thrown together.
 *
 * @param kind the Die Kind of its solid
 * @param roll which roll it shows: the index of that roll's Face Value Chain in {@link RollPlan.Planned#chains()},
 *     in the evaluator's order (Dice Group order, then position in the group)
 */
public record PlannedDie(DieKind kind, int roll) {

    /**
     * Checks the parts.
     *
     * @throws IllegalArgumentException if {@code roll} is negative
     */
    public PlannedDie {
        Objects.requireNonNull(kind, "kind");
        if (roll < 0) {
            throw new IllegalArgumentException("a roll index is at least 0: " + roll);
        }
    }
}
