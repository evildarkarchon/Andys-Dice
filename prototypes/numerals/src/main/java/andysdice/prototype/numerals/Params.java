package andysdice.prototype.numerals;

/**
 * PROTOTYPE. Every knob the prototype exposes, one per sub-question of the ticket. Written on the FX
 * thread, read by the build thread and openglfx's render thread, hence the volatiles; a torn read just
 * means one frame mixes two settings, which is harmless here.
 *
 * <p>Knobs that change the geometry or the atlas bump {@link #structureVersion} and trigger a rebuild.
 * Depth, ink fill, look and technique are shader uniforms and apply instantly.
 */
final class Params {
    /** The ticket's technique question, one per switcher variant. */
    enum Technique {
        A("Normal map + one-step parallax (bake-off baseline)"),
        B("Parallax occlusion mapping + self-shadow"),
        C("Real recessed geometry (dense displaced mesh)");

        final String label;

        Technique(String label) {
            this.label = label;
        }
    }

    /** Which corner each d12/d20 numeral's top points at; only the faces at the top are confirmed. */
    enum OrientRule {
        AWAY_FROM_TOP("baseline toward the neighbour nearest the 20/12 (tops flow away from it)"),
        LOWEST("baseline toward the lowest-numbered neighbour (bake-off rule)"),
        HIGHEST("baseline toward the highest-numbered neighbour"),
        /**
         * The research reads the Chessex d12 photo as "an underlined 6/9 below the 12", which suggests
         * the 9 reads upright with the 12, its top at the shared edge rather than a corner.
         */
        TOWARD_TOP("tops point at the edge toward the 20/12 (d20's 2/8/14 keep the photo's rule)");

        final String label;

        OrientRule(String label) {
            this.label = label;
        }
    }

    /** How the percentile die's two-digit numerals run on the kite. */
    enum PercentDir {
        PERP_OUT("perpendicular to the equator, reading apex to equator (Chessex, per photo)"),
        PERP_IN("perpendicular to the equator, reading equator to apex"),
        PARALLEL("parallel to the equator, like the d10 (Dice & Games)");

        final String label;

        PercentDir(String label) {
            this.label = label;
        }
    }

    /** Edge radius candidates in mm; research suggests 0.5-1.0 (issue #5, §2.4). Shared with Jolt (ADR-0003). */
    static final float[] EDGE_RADII = {0.5f, 0.75f, 1.0f};
    /** Engraving depth candidates in mm; research guesses 0.3-0.5 (§4). */
    static final float[] DEPTHS = {0.3f, 0.4f, 0.5f};
    /** How full of ink the recess is: a thin film on the floor, half full, nearly to the brim. */
    static final float[] FILLS = {0.1f, 0.5f, 0.85f};
    static final String[] FILL_NAMES = {"film", "half", "brim"};
    /** Numeral height as a fraction of the largest that fits every face of the kind. */
    static final float[] SIZES = {0.70f, 0.78f, 0.85f, 0.92f, 1.0f};
    /** Stroke weight in cap heights. */
    static final float[] WEIGHTS = {0.13f, 0.16f, 0.19f};
    static final String[] WEIGHT_NAMES = {"regular", "bold", "heavy"};

    volatile Technique technique = Technique.B;
    volatile int edgeIndex = 1;
    volatile int depthIndex = 1;
    volatile int fillIndex = 0;
    volatile int sizeIndex = 3;
    volatile int weightIndex = 1;
    volatile StrokeFont.Style style = StrokeFont.Style.CHESSEX;
    volatile StrokeFont.Mark mark = StrokeFont.Mark.UNDERLINE;
    volatile OrientRule orient = OrientRule.TOWARD_TOP;
    volatile PercentDir percentDir = PercentDir.PERP_OUT;
    volatile int structureVersion = 0;

    /** An immutable snapshot of the structural knobs, handed to a build. */
    record Structure(int version, float edgeRadius, StrokeFont.Style style, float weight, float size,
                     StrokeFont.Mark mark, OrientRule orient, PercentDir percentDir) {
    }

    Structure structure() {
        return new Structure(structureVersion, EDGE_RADII[edgeIndex], style, WEIGHTS[weightIndex], SIZES[sizeIndex],
                mark, orient, percentDir);
    }

    /** Visible recess depth: the engraving depth less the part the ink fills. */
    float visibleDepth() {
        return DEPTHS[depthIndex] * (1 - FILLS[fillIndex]);
    }

    void changed() {
        structureVersion++;
    }

    static <E extends Enum<E>> E next(E e) {
        E[] all = e.getDeclaringClass().getEnumConstants();
        return all[(e.ordinal() + 1) % all.length];
    }
}
