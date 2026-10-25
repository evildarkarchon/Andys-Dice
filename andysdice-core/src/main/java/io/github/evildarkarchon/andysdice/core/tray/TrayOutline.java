package io.github.evildarkarchon.andysdice.core.tray;

/**
 * The shape of a Tray's inner outline, inside the fixed 300 × 200 mm Footprint (ADR-0010). In a {@code .tray} file
 * it is the {@code outline} key, written as the constant's name in lower case.
 */
public enum TrayOutline {
    /** The whole Footprint. */
    RECTANGLE,
    /** Pointed at the left and right, flat along the near and far walls (the tray prototype's hexagon). */
    HEXAGON,
    /** A rounded rectangle: a superellipse touching the Footprint's four sides, as a many-sided polygon. */
    OVAL
}
