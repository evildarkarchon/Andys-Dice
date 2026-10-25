package io.github.evildarkarchon.andysdice.core.tray;

/**
 * What a Tray's floor is covered with (ADR-0010): the {@code floor.surface} key, written in lower case. It picks the
 * floor's texture and its physics material: felt swallows bounces and damps a Die's spin, bare wood clatters. The
 * numbers are the flat-rest prototype's ({@code Params.Surface}), which the soak's gates were measured with.
 */
public enum FloorSurface {
    /** Felt: soft, grippy and dead. */
    FELT(0.5f, 0.15f, 0.6f),
    /** Smooth leather. */
    LEATHER(0.55f, 0.25f, 0.45f),
    /** Bare, finished walnut: slippery and lively. */
    WOOD(0.35f, 0.45f, 0.2f);

    private final float friction;
    private final float restitution;
    private final float spinDamping;

    FloorSurface(float friction, float restitution, float spinDamping) {
        this.friction = friction;
        this.restitution = restitution;
        this.spinDamping = spinDamping;
    }

    /**
     * Returns the surface's own friction, as the prototypes tuned it against a Die of friction
     * {@link Tray#REFERENCE_DIE_FRICTION}.
     *
     * @return the friction coefficient
     */
    public float friction() {
        return friction;
    }

    /**
     * Returns the surface's restitution, which the prototype also gave each Die thrown onto it.
     *
     * @return 0..1
     */
    public float restitution() {
        return restitution;
    }

    /**
     * Returns the angular damping a Die gets on this floor, standing in for its rolling resistance.
     *
     * @return at least 0
     */
    public float spinDamping() {
        return spinDamping;
    }
}
