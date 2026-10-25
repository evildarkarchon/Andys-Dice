package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.simulation.Vector3;

/**
 * Where a world point appears across the screen, for panning (ADR-0012 "Output"). The app hands the mixer the
 * current camera's projection whenever the camera moves, so an orbited camera still pans correctly.
 *
 * <p>Implementations are called on the audio thread, so they must be immutable snapshots.
 */
@FunctionalInterface
public interface ScreenProjection {

    /** Puts every point in the middle of the screen: no panning. */
    ScreenProjection CENTRE = point -> 0;

    /**
     * Returns the screen-space x of a point.
     *
     * @param point a point in world centimetres
     * @return -1 at the left edge of the view, 1 at the right; values beyond are clamped
     */
    double screenX(Vector3 point);
}
