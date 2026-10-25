package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.List;

/**
 * What the simulator needs to know about one Die: its solid and how to tell whether it lies flat.
 *
 * <p>This is the engine-neutral face of a Die Kind's solid (ADR-0003: physics and rendering share one
 * definition). The simulator builds a rounded convex hull from the sharp vertices, with the edge radius as the
 * rounding, so the body matches the rendered Die; and it asks the Die itself whether a resting pose is cocked,
 * so the cocked test and its tolerance live with the solid, not in each simulator.
 *
 * <p>Implementations must be immutable, so a request can be handed to the physics worker.
 */
public interface DieBody {

    /**
     * Returns the sharp solid's vertices in the Die's model frame.
     *
     * @return the vertices in cm, centred on the Die's centre of mass; at least 4, not all coplanar
     */
    List<Vector3> vertices();

    /**
     * Returns the radius the solid's edges and corners are rounded to (ADR-0008: 0.75 mm).
     *
     * @return the radius in cm
     */
    float edgeRadius();

    /**
     * Tests whether the Die, resting in this orientation, is a Cocked Die: its reading face too far from level
     * to show a Face Value (ADR-0013).
     *
     * @param orientation the rotation from the Die's model frame to the world
     * @return {@code true} if the Die is cocked
     */
    boolean isCocked(Orientation orientation);
}
