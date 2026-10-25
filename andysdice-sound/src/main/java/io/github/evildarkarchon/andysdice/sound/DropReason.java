package io.github.evildarkarchon.andysdice.sound;

/** Why the mixer let a contact event pass silently (ADR-0012 "Shaping lives in the sound module"). */
public enum DropReason {
    /** Its impulse was below the cull threshold, a fraction of its category's full-scale impulse. */
    CULLED,
    /** It came after its Die was at rest. */
    AFTER_REST,
    /** A louder event of the same body pair lay within the merge window, or one had just started. */
    MERGED,
    /** The window cap was full of louder impacts. */
    CAPPED,
    /** Its moment had already been heard by the time it was due (the clock jumped past it). */
    LATE
}
