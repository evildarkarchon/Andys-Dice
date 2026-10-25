package io.github.evildarkarchon.andysdice.core.simulation;

/** What a body in a Throw is, as a contact event names it (ADR-0003 as amended by ADR-0012). */
public enum BodyKind {
    /** One of the Dice being thrown. */
    DIE,
    /** A Frozen Die: lying still, struck but never moved. */
    FROZEN_DIE,
    /** The Tray's floor. */
    FLOOR,
    /** One of the Tray's walls. */
    WALL
}
