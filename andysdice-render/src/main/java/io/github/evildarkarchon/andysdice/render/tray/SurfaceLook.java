package io.github.evildarkarchon.andysdice.render.tray;

import io.github.evildarkarchon.andysdice.core.tray.FloorSurface;
import io.github.evildarkarchon.andysdice.core.tray.WallMaterial;

/**
 * How one of the Tray's surfaces shades, beside its texture and colour: the tray prototype's {@code surface(tex,
 * roughness, sheen, bumpMm)} numbers ({@code GlRenderer}), which the Tray files don't carry because they follow
 * from the surface kind.
 *
 * @param roughness the surface's roughness, 0..1
 * @param sheen how bright felt's grazing-angle rim is (0 for none)
 * @param bumpMm the depth of the texture's height channel, in mm
 */
public record SurfaceLook(float roughness, float sheen, float bumpMm) {

    /** The table around the Tray: dark, worn wood. */
    public static final SurfaceLook TABLE = new SurfaceLook(0.5f, 0, 0.02f);

    /**
     * Returns how a floor surface shades.
     *
     * @param surface the floor's surface
     * @return its look
     */
    public static SurfaceLook of(FloorSurface surface) {
        return switch (surface) {
            case FELT -> new SurfaceLook(0.95f, 1.6f, 0.12f);
            case LEATHER -> new SurfaceLook(0.55f, 0.3f, 0.07f);
            case WOOD -> new SurfaceLook(0.38f, 0, 0.03f);
        };
    }

    /**
     * Returns how a wall material shades. Leather walls shade as a leather floor; wooden ones are a little rougher
     * and flatter than a wooden floor.
     *
     * @param material the walls' material
     * @return its look
     */
    public static SurfaceLook of(WallMaterial material) {
        return switch (material) {
            case WOOD -> new SurfaceLook(0.42f, 0, 0.02f);
            case LEATHER -> of(FloorSurface.LEATHER);
        };
    }
}
