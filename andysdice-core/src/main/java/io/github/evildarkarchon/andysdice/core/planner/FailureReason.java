package io.github.evildarkarchon.andysdice.core.planner;

/** Why a Roll is a Failed Roll (spec §3.2 gives the message the Player sees for each). */
public enum FailureReason {
    /**
     * The Dice wouldn't settle: a Throw's third Cocked Rethrow still left a Cocked Die, or a Throw had no Attempt
     * at rest at all, every one having escaped or timed out (ADR-0013).
     */
    WOULDNT_SETTLE,
    /** Too many rerolls: the Roll needed more than {@link ThrowPlanner#MAX_REROLL_ROUNDS} reroll rounds (ADR-0006). */
    TOO_MANY_REROLLS
}
