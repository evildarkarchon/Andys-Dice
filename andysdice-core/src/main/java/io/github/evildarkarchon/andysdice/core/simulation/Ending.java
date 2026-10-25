package io.github.evildarkarchon.andysdice.core.simulation;

/** How an Attempt ended: always exactly one of four ways (ADR-0003). */
public enum Ending {
    /** Every Die came to rest lying flat. The only ending a Throw can accept without more work. */
    AT_REST,
    /** Every Die came to rest, but at least one is a Cocked Die. */
    COCKED,
    /** A Die left the Tray over its walls. */
    ESCAPED,
    /** The Dice were still moving after {@link Simulator#TIME_CAP_SECONDS} of simulated time. */
    TIMED_OUT
}
