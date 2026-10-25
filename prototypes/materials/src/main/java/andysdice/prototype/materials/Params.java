package andysdice.prototype.materials;

/**
 * PROTOTYPE. Every knob the prototype exposes. Written on the FX thread, read by the build thread and
 * openglfx's render thread, hence the volatiles; a torn read just means one frame mixes two settings,
 * which is harmless here.
 *
 * <p>The numeral knobs from the numerals prototype are pinned to ADR-0008's verdict (only the ink fill
 * and engraving depth stay switchable, because ink is the interface with the Dice Set). Changing them
 * would bump {@link #structureVersion} and trigger a rebuild, as before.
 */
final class Params {
    /** The ticket's stretch-goal question, one per switcher variant: how is a translucent Dice Set drawn? */
    enum Technique {
        A("Opaque only: translucent sets fall back to solid resin"),
        B("Thickness: analytic path length, absorption + clouds, no refraction"),
        C("Refraction: tray seen through the die, volume march, far-face numerals");

        final String label;

        Technique(String label) {
            this.label = label;
        }
    }

    /** Where the swirl field comes from: evaluated per sample, or baked once per Dice Set. */
    enum SwirlSource {
        SHADER("procedural in the shader (warped fBm, ~130 hashes per sample)"),
        BAKED("baked into a 128^3 3D texture per Dice Set (one fetch per sample)");

        final String label;

        SwirlSource(String label) {
            this.label = label;
        }
    }

    /** Engraving depth candidates in mm (ADR-0008: 0.4). */
    static final float[] DEPTHS = {0.3f, 0.4f, 0.5f};
    /** How full of ink the recess is: a thin film on the floor (ADR-0008), half full, nearly to the brim. */
    static final float[] FILLS = {0.1f, 0.5f, 0.85f};
    static final String[] FILL_NAMES = {"film", "half", "brim"};

    volatile Technique technique = Technique.C;
    volatile SwirlSource swirlSource = SwirlSource.SHADER;
    /** Each Die gets its own offset into the swirl and flake fields, so no two dice in a set match. */
    volatile boolean variation = true;
    volatile int depthIndex = 1;
    volatile int fillIndex = 0;
    volatile int structureVersion = 0;

    /** An immutable snapshot of the structural knobs, handed to a build. */
    record Structure(int version, float edgeRadius, StrokeFont.Style style, float weight, float size,
                     StrokeFont.Mark mark, OrientRule orient, PercentDir percentDir) {
    }

    /** ADR-0008's verdict, as the numerals prototype ended up: 0.75 mm edges, bold Chessex, 92%, underlined 6/9. */
    Structure structure() {
        return new Structure(structureVersion, 0.75f, StrokeFont.Style.CHESSEX, 0.16f, 0.92f,
                StrokeFont.Mark.UNDERLINE, OrientRule.TOWARD_TOP, PercentDir.PERP_OUT);
    }

    /** Which corner each d12/d20 numeral's top points at (ADR-0008 picked TOWARD_TOP). */
    enum OrientRule {
        AWAY_FROM_TOP, LOWEST, HIGHEST, TOWARD_TOP
    }

    /** How the percentile die's two-digit numerals run on the kite (ADR-0008 picked PERP_OUT). */
    enum PercentDir {
        PERP_OUT, PERP_IN, PARALLEL
    }

    /** Visible recess depth: the engraving depth less the part the ink fills. */
    float visibleDepth() {
        return DEPTHS[depthIndex] * (1 - FILLS[fillIndex]);
    }

    static <E extends Enum<E>> E next(E e) {
        E[] all = e.getDeclaringClass().getEnumConstants();
        return all[(e.ordinal() + 1) % all.length];
    }
}
