package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.Objects;

/**
 * The physics materials of a Throw into one Tray (ADR-0010, ADR-0013): how a Die's contact with the floor,
 * a wall and another Die behaves, and how much the floor's surface damps a Die's spin.
 *
 * @param floor a Die against the floor
 * @param walls a Die against a wall
 * @param dieOnDie a Die against another Die, thrown or frozen (ADR-0013: friction 0.2)
 * @param dieAngularDamping the angular damping of each thrown Die, which stands in for the floor's rolling
 *     resistance (felt damps more than wood); at least 0
 */
public record TrayMaterials(ContactMaterial floor, ContactMaterial walls, ContactMaterial dieOnDie,
        float dieAngularDamping) {

    /**
     * The combined friction of a Die against another Die, thrown or frozen (ADR-0013; spec §4: a tunable). Lower
     * than a Die against the floor, so Dice slide off each other instead of propping each other up cocked.
     */
    public static final float DIE_ON_DIE_FRICTION = 0.2f;

    /**
     * Returns a Tray's materials with die-on-die contacts at {@link #DIE_ON_DIE_FRICTION}. Their bounce is the
     * floor's, as in the flat-rest prototype, where each Die took its Tray surface's restitution and Jolt
     * combines two restitutions by their maximum.
     *
     * @param floor a Die against the floor
     * @param walls a Die against a wall
     * @param dieAngularDamping the angular damping of each thrown Die; at least 0
     * @return the materials
     * @throws IllegalArgumentException if the damping is negative or not finite
     */
    public static TrayMaterials of(ContactMaterial floor, ContactMaterial walls, float dieAngularDamping) {
        return new TrayMaterials(floor, walls, new ContactMaterial(DIE_ON_DIE_FRICTION, floor.restitution()),
                dieAngularDamping);
    }

    /**
     * Checks every material is present and the damping is physical.
     *
     * @throws IllegalArgumentException if the damping is negative or not finite
     */
    public TrayMaterials {
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(walls, "walls");
        Objects.requireNonNull(dieOnDie, "dieOnDie");
        if (!(dieAngularDamping >= 0) || !Float.isFinite(dieAngularDamping)) {
            throw new IllegalArgumentException("Angular damping must be finite and not negative: "
                    + dieAngularDamping);
        }
    }
}
