package io.github.evildarkarchon.andysdice.core.geometry;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The research's face tables: one outward normal per face, in face order, copied verbatim from
 * {@code research/real-dice-geometry} ({@code f902743:docs/research/real-dice-geometry.md} §3, spec
 * Appendix D). Every frame is z-up and right-handed, as the research gives it; a Die's pose rotates it into
 * the y-up world.
 *
 * <p>Face {@code i} carries the {@code i}-th Numeral of its Die Kind: 1..N on the d4, d6, d8, d12 and d20, 0..9
 * on the d10 (and its tens on the percentile d10). The values keep the research's four decimals; the solid
 * builder normalises them.
 */
final class FaceTables {

    /**
     * d4 corner labels, research §3.2 (top-read, right-handed): corner {@code k} carries label {@code k + 1}.
     * Face {@code k} is the one without that corner, so its outward normal is the corner's direction negated.
     */
    static final List<Vector3dc> D4_CORNERS = vectors(new double[][] {
        {1, 1, 1}, {-1, 1, -1}, {1, -1, -1}, {-1, -1, 1},
    });

    /** d4 faces 1..4: each the negated direction of the corner it lacks. */
    static final List<Vector3dc> D4 = D4_CORNERS.stream()
            .<Vector3dc>map(corner -> corner.negate(new Vector3d()))
            .toList();

    /** d6 faces 1..6, research §3.3: 6 = +z, 1 = −z, 3 = +y, 4 = −y, 2 = −x, 5 = +x (right-handed). */
    static final List<Vector3dc> D6 = vectors(new double[][] {
        {0, 0, -1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {0, 0, 1},
    });

    /** d8 faces 1..8, research §3.4 (Chessex d8_2): evens 8, 6, 4, 2 on one half; opposites sum to 9. */
    static final List<Vector3dc> D8 = vectors(new double[][] {
        {-0.5774, -0.5774, -0.5774},
        {+0.5774, -0.5774, +0.5774},
        {+0.5774, -0.5774, -0.5774},
        {-0.5774, -0.5774, +0.5774},
        {+0.5774, +0.5774, -0.5774},
        {-0.5774, +0.5774, +0.5774},
        {-0.5774, +0.5774, -0.5774},
        {+0.5774, +0.5774, +0.5774},
    });

    /**
     * d10 faces 0..9, research §3.5 (Chessex d10_1), on the §2.2 sphere-variant pentagonal trapezohedron with
     * its odd apex at +z. The percentile d10 uses the same faces.
     */
    static final List<Vector3dc> D10 = vectors(new double[][] {
        {-0.6015, +0.4370, -0.6687},
        {+0.6015, +0.4370, +0.6687},
        {+0.2298, -0.7071, -0.6687},
        {-0.7435, +0.0000, +0.6687},
        {+0.2298, +0.7071, -0.6687},
        {-0.2298, -0.7071, +0.6687},
        {+0.7435, +0.0000, -0.6687},
        {-0.2298, +0.7071, +0.6687},
        {-0.6015, -0.4370, -0.6687},
        {+0.6015, -0.4370, +0.6687},
    });

    /** d12 faces 1..12, research §3.6 (Chessex d12_3): 12 = +z, 1 = −z; opposites sum to 13. */
    static final List<Vector3dc> D12 = vectors(new double[][] {
        {+0.0000, +0.0000, -1.0000},
        {+0.4702, -0.7608, -0.4472},
        {+0.5783, +0.6823, +0.4472},
        {+0.8689, +0.2121, -0.4472},
        {-0.8276, +0.3391, -0.4472},
        {+0.0668, +0.8919, -0.4472},
        {-0.0668, -0.8919, +0.4472},
        {+0.8276, -0.3391, +0.4472},
        {-0.8689, -0.2121, +0.4472},
        {-0.5783, -0.6823, -0.4472},
        {-0.4702, +0.7608, +0.4472},
        {+0.0000, +0.0000, +1.0000},
    });

    /** d20 faces 1..20, research §3.7 (Chessex d20_4): opposites sum to 21, the 8 directly below the 20. */
    static final List<Vector3dc> D20 = vectors(new double[][] {
        {+0.5774, +0.5774, +0.5774},
        {+0.0000, -0.9342, -0.3568},
        {-0.5774, +0.5774, +0.5774},
        {+0.3568, +0.0000, -0.9342},
        {+0.9342, -0.3568, +0.0000},
        {-0.5774, +0.5774, -0.5774},
        {+0.3568, +0.0000, +0.9342},
        {-0.9342, -0.3568, +0.0000},
        {+0.0000, +0.9342, -0.3568},
        {-0.5774, -0.5774, +0.5774},
        {+0.5774, +0.5774, -0.5774},
        {+0.0000, -0.9342, +0.3568},
        {+0.9342, +0.3568, +0.0000},
        {-0.3568, +0.0000, -0.9342},
        {+0.5774, -0.5774, +0.5774},
        {-0.9342, +0.3568, +0.0000},
        {-0.3568, +0.0000, +0.9342},
        {+0.5774, -0.5774, -0.5774},
        {+0.0000, +0.9342, +0.3568},
        {-0.5774, -0.5774, -0.5774},
    });

    private FaceTables() {
    }

    /** Wraps each {x, y, z} row as an immutable-by-convention vector, keeping the rows' order. */
    private static List<Vector3dc> vectors(double[][] rows) {
        List<Vector3dc> out = new ArrayList<>(rows.length);
        for (double[] row : rows) {
            out.add(new Vector3d(row[0], row[1], row[2]));
        }
        return List.copyOf(out);
    }
}
