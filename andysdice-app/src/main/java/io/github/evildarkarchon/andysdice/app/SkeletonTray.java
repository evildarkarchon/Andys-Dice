package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;

/**
 * The walking skeleton's one Tray: a flat box over the Footprint. Ticket #44 replaces it with the {@code .tray}
 * files and their physics materials.
 */
final class SkeletonTray {

    /** The Footprint (ADR-0010): 300 × 200 mm inside 30 mm walls, in cm. */
    static final TrayGeometry GEOMETRY = TrayGeometry.rectangle(15, 10, 3);

    /**
     * Green felt in walnut, from the tray prototype's numbers (spec Appendix C), as the combined pair values the
     * simulator takes: Jolt's geometric mean of a 0.4 die against felt's 0.5 and the walls' 0.5, felt's 0.15
     * bounce and the walls' 0.5, die-on-die friction 0.2 (ADR-0013), and felt's spin damping.
     */
    static final TrayMaterials MATERIALS = new TrayMaterials(
            new ContactMaterial((float) Math.sqrt(0.4 * 0.5), 0.15f),
            new ContactMaterial((float) Math.sqrt(0.4 * 0.5), 0.5f),
            new ContactMaterial(0.2f, 0.15f), 0.6f);

    private SkeletonTray() {}
}
