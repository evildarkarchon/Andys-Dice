package io.github.evildarkarchon.andysdice.core.tray;

/**
 * The cross-section of a Tray's walls (ADR-0010): the {@code walls.profile} key, written in lower case. It only
 * changes how the walls look; physics collides with their straight inner face either way, up to the wall top.
 */
public enum WallProfile {
    /** A flat top, as on a wooden tray. */
    SQUARE,
    /** A rounded, half-round roll over the top, as on a padded leather tray. */
    PADDED
}
