package andysdice.prototype.tray;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * PROTOTYPE. The seven Die Kinds of the standard set, with face tables copied from "How are real D&D
 * dice shaped and numbered?" (issue #5, research branch {@code research/real-dice-geometry}, §3).
 *
 * <p>Each kind lists one outward normal per face, in the research's own (z-up) frame, and the label
 * printed on that face. Sizes are the research's suggested model sizes (§2.3), as inradii in mm.
 */
enum DieKind {
    D4("d4", 4.5f),
    D6("d6", 8f),
    D8("d8", 7.75f),
    D10("d10", 8f),
    D_PERCENT("d%", 8f),
    D12("d12", 9.5f),
    D20("d20", 10f);

    final String label;
    /** Face-to-face half size in mm (the inradius of the rounded die). */
    final float inradius;

    DieKind(String label, float inradius) {
        this.label = label;
        this.inradius = inradius;
    }

    /** One face of the table: its label and outward normal. */
    record FaceSpec(String label, Vector3f normal) {
    }

    /** d4 corner labels (top-read, right-handed; research §3.2). Index = label - 1. */
    static final Vector3f[] D4_CORNERS = {
            new Vector3f(1, 1, 1), new Vector3f(-1, 1, -1), new Vector3f(1, -1, -1), new Vector3f(-1, -1, 1)};

    /**
     * The faces of this kind. For the d4 each face is named by the corner label it lacks (the label
     * that is up when that face is on the table); its numerals come from {@link #D4_CORNERS}.
     */
    List<FaceSpec> faces() {
        List<FaceSpec> out = new ArrayList<>();
        switch (this) {
            case D4 -> {
                for (int k = 0; k < 4; k++) out.add(new FaceSpec(Integer.toString(k + 1), new Vector3f(D4_CORNERS[k]).negate()));
            }
            case D6 -> {
                // §3.3: 6 = +z, 1 = -z, 3 = +y, 4 = -y, 2 = -x, 5 = +x (right-handed).
                out.add(new FaceSpec("1", new Vector3f(0, 0, -1)));
                out.add(new FaceSpec("2", new Vector3f(-1, 0, 0)));
                out.add(new FaceSpec("3", new Vector3f(0, 1, 0)));
                out.add(new FaceSpec("4", new Vector3f(0, -1, 0)));
                out.add(new FaceSpec("5", new Vector3f(1, 0, 0)));
                out.add(new FaceSpec("6", new Vector3f(0, 0, 1)));
            }
            case D8 -> {
                float[][] t = {{-1, -1, -1}, {1, -1, 1}, {1, -1, -1}, {-1, -1, 1}, {1, 1, -1}, {-1, 1, 1}, {-1, 1, -1}, {1, 1, 1}};
                for (int i = 0; i < 8; i++) out.add(new FaceSpec(Integer.toString(i + 1), v(t[i])));
            }
            case D10, D_PERCENT -> {
                // §3.5, faces 0..9; the sphere-variant trapezohedron with its odd apex at +z.
                float[][] t = {
                        {-0.6015f, 0.4370f, -0.6687f}, {0.6015f, 0.4370f, 0.6687f}, {0.2298f, -0.7071f, -0.6687f},
                        {-0.7435f, 0f, 0.6687f}, {0.2298f, 0.7071f, -0.6687f}, {-0.2298f, -0.7071f, 0.6687f},
                        {0.7435f, 0f, -0.6687f}, {-0.2298f, 0.7071f, 0.6687f}, {-0.6015f, -0.4370f, -0.6687f},
                        {0.6015f, -0.4370f, 0.6687f}};
                for (int i = 0; i < 10; i++) {
                    // Percentile layout assumed to be the d10 layout x10 (research §3.5, unconfirmed).
                    String label = this == D10 ? Integer.toString(i) : i + "0";
                    out.add(new FaceSpec(label, v(t[i])));
                }
            }
            case D12 -> {
                float[][] t = {
                        {0f, 0f, -1f}, {0.4702f, -0.7608f, -0.4472f}, {0.5783f, 0.6823f, 0.4472f}, {0.8689f, 0.2121f, -0.4472f},
                        {-0.8276f, 0.3391f, -0.4472f}, {0.0668f, 0.8919f, -0.4472f}, {-0.0668f, -0.8919f, 0.4472f},
                        {0.8276f, -0.3391f, 0.4472f}, {-0.8689f, -0.2121f, 0.4472f}, {-0.5783f, -0.6823f, -0.4472f},
                        {-0.4702f, 0.7608f, 0.4472f}, {0f, 0f, 1f}};
                for (int i = 0; i < 12; i++) out.add(new FaceSpec(Integer.toString(i + 1), v(t[i])));
            }
            case D20 -> {
                float[][] t = {
                        {0.5774f, 0.5774f, 0.5774f}, {0f, -0.9342f, -0.3568f}, {-0.5774f, 0.5774f, 0.5774f},
                        {0.3568f, 0f, -0.9342f}, {0.9342f, -0.3568f, 0f}, {-0.5774f, 0.5774f, -0.5774f},
                        {0.3568f, 0f, 0.9342f}, {-0.9342f, -0.3568f, 0f}, {0f, 0.9342f, -0.3568f},
                        {-0.5774f, -0.5774f, 0.5774f}, {0.5774f, 0.5774f, -0.5774f}, {0f, -0.9342f, 0.3568f},
                        {0.9342f, 0.3568f, 0f}, {-0.3568f, 0f, -0.9342f}, {0.5774f, -0.5774f, 0.5774f},
                        {-0.9342f, 0.3568f, 0f}, {-0.3568f, 0f, 0.9342f}, {0.5774f, -0.5774f, -0.5774f},
                        {0f, 0.9342f, 0.3568f}, {-0.5774f, -0.5774f, -0.5774f}};
                for (int i = 0; i < 20; i++) out.add(new FaceSpec(Integer.toString(i + 1), v(t[i])));
            }
        }
        return out;
    }

    /**
     * Whether a single numeral carries the 6/9 mark (research §4): the d6, d8, d10 and d12 mark their
     * 6 and 9, the d20 marks only 6 and 9 (not 16 or 19). The percentile die's 60/90 carry no mark in
     * the one catalogue photo, and the d4 has neither.
     */
    boolean marks(String numeral) {
        if (this == D4 || this == D_PERCENT) return false;
        return numeral.equals("6") || numeral.equals("9");
    }

    private static Vector3f v(float[] a) {
        return new Vector3f(a[0], a[1], a[2]);
    }
}
