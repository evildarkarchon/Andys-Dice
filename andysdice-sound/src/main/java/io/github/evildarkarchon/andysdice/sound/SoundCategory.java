package io.github.evildarkarchon.andysdice.sound;

/**
 * The six sample categories (spec §3.5, ADR-0012 "Categories"): a Die on each floor kind, on each wall
 * material, and on another Die. A Frozen Die counts as a Die (ADR-0006); the Dice Set's material doesn't
 * change the sound (ADR-0009).
 */
public enum SoundCategory {
    /** A Die on a felt floor. */
    FELT_FLOOR,
    /** A Die on a leather floor. */
    LEATHER_FLOOR,
    /** A Die on a wooden floor. */
    WOOD_FLOOR,
    /** A Die on a wooden wall. */
    WOOD_WALL,
    /** A Die on a leather wall. */
    LEATHER_WALL,
    /** A Die on another Die, thrown or frozen. */
    DIE_ON_DIE;

    /**
     * Tests whether this is a floor category.
     *
     * @return {@code true} for the three floor kinds
     */
    public boolean isFloor() {
        return this == FELT_FLOOR || this == LEATHER_FLOOR || this == WOOD_FLOOR;
    }

    /**
     * Tests whether this is a wall category.
     *
     * @return {@code true} for the two wall materials
     */
    public boolean isWall() {
        return this == WOOD_WALL || this == LEATHER_WALL;
    }
}
