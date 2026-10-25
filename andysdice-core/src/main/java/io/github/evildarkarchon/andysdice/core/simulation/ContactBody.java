package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.Objects;

/**
 * One body of a contact: its kind and, for a Die or a Frozen Die, which one.
 *
 * @param kind what the body is
 * @param index for {@link BodyKind#DIE}, the index into the request's Dice; for {@link BodyKind#FROZEN_DIE},
 *     the index into its Frozen Dice; {@code -1} for the floor and the walls
 */
public record ContactBody(BodyKind kind, int index) {

    private static final ContactBody FLOOR = new ContactBody(BodyKind.FLOOR, -1);
    private static final ContactBody WALL = new ContactBody(BodyKind.WALL, -1);

    /**
     * Checks that a Die has an index and the Tray's parts don't.
     *
     * @throws IllegalArgumentException if the index doesn't fit the kind
     */
    public ContactBody {
        Objects.requireNonNull(kind, "kind");
        boolean isDie = kind == BodyKind.DIE || kind == BodyKind.FROZEN_DIE;
        if (isDie ? index < 0 : index != -1) {
            throw new IllegalArgumentException("A " + kind + " body can't have index " + index);
        }
    }

    /**
     * Returns the thrown Die at {@code index}.
     *
     * @param index the index into the request's Dice
     * @return the body
     */
    public static ContactBody die(int index) {
        return new ContactBody(BodyKind.DIE, index);
    }

    /**
     * Returns the Frozen Die at {@code index}.
     *
     * @param index the index into the request's Frozen Dice
     * @return the body
     */
    public static ContactBody frozenDie(int index) {
        return new ContactBody(BodyKind.FROZEN_DIE, index);
    }

    /**
     * Returns the Tray's floor.
     *
     * @return the body
     */
    public static ContactBody floor() {
        return FLOOR;
    }

    /**
     * Returns a wall of the Tray.
     *
     * @return the body
     */
    public static ContactBody wall() {
        return WALL;
    }
}
