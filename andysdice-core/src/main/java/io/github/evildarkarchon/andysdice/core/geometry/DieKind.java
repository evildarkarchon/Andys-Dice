package io.github.evildarkarchon.andysdice.core.geometry;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.joml.Vector3dc;

/**
 * The seven Die Kinds of the standard 7-die set. Each has one sharp {@link Solid} built from its face table
 * (the real-dice-geometry research, spec Appendix D) at its inradius (spec Appendix A), and a 0.75 mm edge
 * radius (spec §4, ADR-0008). Physics and rendering both use these definitions.
 *
 * <p>Face tables, and so the solids, are in the research's z-up model frame; a Die's pose rotates them into the
 * y-up world.
 */
public enum DieKind {
    /** The tetrahedral d4, read from the top: its value is the corner pointing up. */
    D4(4.5, FaceTables.D4),
    /** The numbered cube. */
    D6(8, FaceTables.D6),
    /** The octahedron. */
    D8(7.75, FaceTables.D8),
    /** The sphere-variant pentagonal trapezohedron, numbered 0–9. */
    D10(8, FaceTables.D10),
    /** The d10's own solid, numbered 00–90 in tens. */
    PERCENTILE_D10(8, FaceTables.D10),
    /** The dodecahedron. */
    D12(9.5, FaceTables.D12),
    /** The icosahedron. */
    D20(10, FaceTables.D20);

    /** Every Die Kind's edges and corners are rounded at this radius, in mm (spec §4: a contract). */
    public static final double EDGE_RADIUS_MM = 0.75;

    private final double inradius;
    private final List<Vector3dc> faceNormals;

    DieKind(double inradius, List<Vector3dc> faceNormals) {
        this.inradius = inradius;
        this.faceNormals = faceNormals;
    }

    /**
     * The inradius of the Die's solid in mm: half its face-to-face distance (spec Appendix A).
     *
     * @return the distance from the Die's centre to each face plane, in mm
     */
    public double inradius() {
        return inradius;
    }

    /**
     * The radius to which the Die's edges and corners are rounded.
     *
     * @return {@link #EDGE_RADIUS_MM}, in mm
     */
    public double edgeRadius() {
        return EDGE_RADIUS_MM;
    }

    /**
     * How many faces the Die has.
     *
     * @return 4, 6, 8, 10, 12 or 20
     */
    public int faceCount() {
        return faceNormals.size();
    }

    /**
     * The Numeral the face table puts on a face: {@code "1"}..N on the d4, d6, d8, d12 and d20, {@code "0"}..
     * {@code "9"} on the d10, and {@code "00"}, {@code "10"}..{@code "90"} on the percentile d10. On the d4, face
     * {@code i} is named by the corner it lacks, the corner that points up when that face lies on the table; its
     * own three Numerals are those of its corners.
     *
     * <p>This is the solid's fixed numbering. Which Numeral a landed Die shows is a matter for the Numbering
     * Rotation, not this table.
     *
     * @param face a face index, {@code 0 <= face < faceCount()}
     * @return the face's Numeral as printed
     * @throws IndexOutOfBoundsException if {@code face} is out of range
     */
    public String numeral(int face) {
        Objects.checkIndex(face, faceCount());
        return switch (this) {
            case D10 -> Integer.toString(face);
            case PERCENTILE_D10 -> face + "0";
            default -> Integer.toString(face + 1);
        };
    }

    /**
     * The Die's sharp solid at its full inradius, in mm, in the z-up model frame. The percentile d10 returns the
     * very instance the d10 does.
     *
     * @return the shared, immutable solid of this Die Kind
     */
    public Solid solid() {
        return Solids.BY_KIND.get(this);
    }

    /**
     * Builds every solid once, on first use. A holder class, because an enum's constructor may not read the
     * enum's other constants, and the percentile d10 must share the d10's instance.
     */
    private static final class Solids {
        private static final Map<DieKind, Solid> BY_KIND = build();

        /** Builds one solid per Die Kind, in declaration order, so the d10's exists before the percentile's. */
        private static Map<DieKind, Solid> build() {
            Map<DieKind, Solid> out = new EnumMap<>(DieKind.class);
            for (DieKind kind : values()) {
                out.put(kind, kind == PERCENTILE_D10
                        ? out.get(D10)
                        : Solid.ofHalfSpaces(kind.faceNormals, kind.inradius));
            }
            return out;
        }
    }
}
