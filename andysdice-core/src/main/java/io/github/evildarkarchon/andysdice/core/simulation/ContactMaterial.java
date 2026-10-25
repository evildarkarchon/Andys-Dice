package io.github.evildarkarchon.andysdice.core.simulation;

/**
 * How a Die's contact with something behaves: the friction and restitution of that pair of surfaces.
 *
 * <p>These are the <em>combined</em> values for the contact, not one surface's own, so the seam doesn't
 * depend on how an engine combines two materials (ADR-0013 leaves that to the build).
 *
 * @param friction the contact's friction coefficient, at least 0
 * @param restitution the contact's restitution (bounce), from 0 to 1
 */
public record ContactMaterial(float friction, float restitution) {

    /**
     * Checks the values are physical.
     *
     * @throws IllegalArgumentException if the friction is negative or the restitution is outside 0 to 1
     */
    public ContactMaterial {
        if (!(friction >= 0) || !Float.isFinite(friction)) {
            throw new IllegalArgumentException("Friction must be finite and not negative: " + friction);
        }
        if (!(restitution >= 0 && restitution <= 1)) {
            throw new IllegalArgumentException("Restitution must be from 0 to 1: " + restitution);
        }
    }
}
