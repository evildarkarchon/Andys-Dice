package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.Objects;

/**
 * One contact a thrown Die made during an Attempt (ADR-0003, as amended by ADR-0012), for sound.
 *
 * @param time the simulated time of the contact, in seconds from the Release
 * @param die the thrown Die that made the contact; always of kind {@link BodyKind#DIE}, since only thrown
 *     Dice move
 * @param other what it struck: another thrown Die, a Frozen Die, the floor or a wall
 * @param impulse the summed normal impulse over the contact's points, in kg·cm/s; never negative
 * @param point where the bodies touched, in world centimetres
 */
public record ContactEvent(float time, ContactBody die, ContactBody other, float impulse, Vector3 point) {

    /**
     * Checks the event is one an Attempt could have produced.
     *
     * @throws IllegalArgumentException if the time or impulse is negative or not finite, or the first body is
     *     not a thrown Die
     */
    public ContactEvent {
        Objects.requireNonNull(die, "die");
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(point, "point");
        if (!(time >= 0) || !Float.isFinite(time)) {
            throw new IllegalArgumentException("A contact's time must be finite and not negative: " + time);
        }
        if (!(impulse >= 0) || !Float.isFinite(impulse)) {
            throw new IllegalArgumentException("A contact's impulse must be finite and not negative: " + impulse);
        }
        if (die.kind() != BodyKind.DIE) {
            throw new IllegalArgumentException("A contact's first body must be a thrown Die, not " + die);
        }
        if (!point.isFinite()) {
            throw new IllegalArgumentException("A contact's point must be finite: " + point);
        }
    }
}
