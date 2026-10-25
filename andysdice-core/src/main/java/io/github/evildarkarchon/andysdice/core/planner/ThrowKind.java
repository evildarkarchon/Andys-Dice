package io.github.evildarkarchon.andysdice.core.planner;

/** Why a Throw in a Roll's chain is made. */
public enum ThrowKind {
    /** The first Throw: every Die of the Roll. */
    FIRST,
    /**
     * A Reroll Throw (ADR-0006): every Die rerolled in one round, across every Dice Group, with the rest frozen.
     * Each of its Dice shows the next value of its Face Value Chain.
     */
    REROLL,
    /**
     * A Cocked Rethrow (ADR-0013): only the Cocked Dice left by the Throw before it, with the rest frozen. It
     * replaces no Face Value: its Dice show the values the Throw it follows meant them to.
     */
    COCKED_RETHROW
}
