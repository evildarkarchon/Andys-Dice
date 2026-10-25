package io.github.evildarkarchon.andysdice.core.tray;

/**
 * What a Tray's walls are made of (ADR-0010): the {@code walls.material} key, written in lower case. It picks the
 * walls' texture and their physics material: wood clatters, padded leather gives back less (spec Appendix C's "wall
 * bounce"). The numbers are the tray prototype's ({@code Params.WallMaterial}).
 */
public enum WallMaterial {
    /** Walnut: Appendix C's wall bounce 0.5. */
    WOOD(0.5f, 0.5f),
    /** Leather over padding: Appendix C's wall bounce 0.2. */
    LEATHER(0.5f, 0.2f);

    private final float friction;
    private final float restitution;

    WallMaterial(float friction, float restitution) {
        this.friction = friction;
        this.restitution = restitution;
    }

    /**
     * Returns the wall's own friction, as the prototypes tuned it against a Die of friction
     * {@link Tray#REFERENCE_DIE_FRICTION}.
     *
     * @return the friction coefficient
     */
    public float friction() {
        return friction;
    }

    /**
     * Returns the wall's own restitution: Appendix C's wall bounce.
     *
     * @return 0..1
     */
    public float restitution() {
        return restitution;
    }
}
