package andysdice.prototype.tray;

/**
 * PROTOTYPE. Every knob the prototype exposes. Written on the FX thread, read by the physics thread and
 * openglfx's render thread, hence the volatiles; a torn read just means one frame mixes two settings,
 * which is harmless here.
 *
 * <p>The switcher's three {@link Look}s are coherent packages (surface + tray + camera + light). Every
 * dimension also has its own key, so a look can be remixed ("the tray from A under the light of B").
 *
 * <p>The numeral and material knobs from the earlier prototypes are pinned to ADR-0008 and ADR-0009.
 */
final class Params {
    /** The switcher: three whole presentations, radically different on purpose. */
    enum Look {
        A("Felt tray: green felt, walnut walls, fixed 3/4 camera, studio softboxes"),
        B("Leather hex tray under a lamp: padded walls, settle-in camera, one warm spot"),
        C("Open wooden board by a window: low lip, follow camera, long daylight shadows");

        final String label;

        Look(String label) {
            this.label = label;
        }
    }

    /**
     * The tray floor. Restitution, friction and angular damping go to physics: a felt floor swallows
     * bounces, bare wood clatters.
     */
    enum Surface {
        GREEN_FELT("green felt", 0.95f, 0.15f, 0.5f, 0.6f),
        NAVY_FELT("navy felt", 0.95f, 0.15f, 0.5f, 0.6f),
        LEATHER("brown leather", 0.55f, 0.25f, 0.55f, 0.45f),
        WOOD("bare walnut", 0.38f, 0.45f, 0.35f, 0.2f);

        final String label;
        final float roughness, restitution, friction, angularDamping;

        Surface(String label, float roughness, float restitution, float friction, float angularDamping) {
            this.label = label;
            this.roughness = roughness;
            this.restitution = restitution;
            this.friction = friction;
            this.angularDamping = angularDamping;
        }
    }

    /** The tray's outline seen from above. */
    enum Shape {
        RECT("rectangle", 4), HEX("hexagon", 6), ROUND("oval", 32);

        final String label;
        final int sides;

        Shape(String label, int sides) {
            this.label = label;
            this.sides = sides;
        }
    }

    /** What the walls are made of, and so their profile and bounce. */
    enum WallMaterial {
        WOOD("walnut, square top", 0.5f),
        PADDED("padded leather, rounded top", 0.2f);

        final String label;
        final float restitution;

        WallMaterial(String label, float restitution) {
            this.label = label;
            this.restitution = restitution;
        }
    }

    /** Inner tray size in mm (half extents along X and Z). */
    enum Size {
        S("small 220 x 150 mm", 110, 75), M("medium 300 x 200 mm", 150, 100), L("large 400 x 260 mm", 200, 130);

        final String label;
        final float halfX, halfZ;

        Size(String label, float halfX, float halfZ) {
            this.label = label;
            this.halfX = halfX;
            this.halfZ = halfZ;
        }
    }

    enum Camera {
        FIXED("fixed 3/4 view of the whole tray (drag to move it)"),
        FOLLOW("follows the moving dice, frames them all at rest"),
        SETTLE("fixed while dice tumble, then eases in on the resting dice"),
        TOP("near top-down, whole tray");

        final String label;

        Camera(String label) {
            this.label = label;
        }
    }

    /** How a rerolled Die leaves its resting place before its Reroll Throw (ADR-0006). */
    enum Pickup {
        LIFT("lifts out along an arc to the throw point"),
        POP("shrinks away where it lay, re-enters with the throw"),
        GLOW_LIFT("glows for a beat (which dice?), then lifts");

        final String label;

        Pickup(String label) {
            this.label = label;
        }
    }

    enum Rig {
        STUDIO("studio: two softboxes, neutral grey room"),
        LAMP("lamp: one warm spot overhead, dark room"),
        WINDOW("window: low cool daylight from the side, blue sky fill");

        final String label;

        Rig(String label) {
            this.label = label;
        }
    }

    enum Shadows {
        PCF("PCF: fixed 5x5 filter, same softness everywhere"),
        PCSS("PCSS: contact-hardening, sharp at the die, soft further out");

        final String label;

        Shadows(String label) {
            this.label = label;
        }
    }

    /** How a translucent Dice Set casts its shadow (ADR-0009 shipped translucency; this is open). */
    enum TransShadow {
        FULL("full, uncoloured (as in the materials prototype)"),
        TINTED("lighter, tinted by the body's colour");

        final String label;

        TransShadow(String label) {
            this.label = label;
        }
    }

    /** Whether the volume that defines "escaped" (ADR-0003) is the visible walls, or taller. */
    enum Escape {
        VISIBLE("the visible walls: a die over the wall top has escaped"),
        GLASS("invisible glass walls 200 mm tall: dice bounce off air above low walls");

        final String label;

        Escape(String label) {
            this.label = label;
        }
    }

    /** What gets thrown: a Roll Expression's physical Dice. */
    record Dice(String expression, DieKind... kinds) {
    }

    static final Dice[] DICE = {
            new Dice("1d20", DieKind.D20),
            new Dice("2d20 (adv)", DieKind.D20, DieKind.D20),
            new Dice("4d6", DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D6),
            new Dice("one of each (7-piece set)", DieKind.D4, DieKind.D6, DieKind.D8, DieKind.D10, DieKind.D_PERCENT, DieKind.D12, DieKind.D20),
            new Dice("8d6 (fireball)", DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D6),
            new Dice("20 mixed (the cap)",
                    DieKind.D20, DieKind.D20, DieKind.D12, DieKind.D12, DieKind.D10, DieKind.D10, DieKind.D_PERCENT, DieKind.D8, DieKind.D8,
                    DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D4, DieKind.D4, DieKind.D4, DieKind.D20, DieKind.D8),
    };

    static final float[] WALL_HEIGHTS = {15, 30, 50};
    static final float[] RENDER_SCALES = {1.0f, 0.75f, 0.5f};

    volatile Look look = Look.A;
    volatile Surface surface;
    volatile Shape shape;
    volatile WallMaterial wall;
    volatile int wallHeightIndex;
    volatile Size size;
    volatile Camera camera;
    volatile Pickup pickup;
    volatile Rig rig;
    volatile Shadows shadows;
    volatile boolean contactAo = true;
    volatile TransShadow transShadow;
    volatile Escape escape;
    volatile boolean showEscape = false;
    volatile int renderScaleIndex = 0;
    volatile int diceIndex = 3;

    Params() {
        apply(Look.A);
    }

    /** Sets every dimension a look owns. Dice, Dice Set, render scale and the overlays are left alone. */
    void apply(Look l) {
        look = l;
        switch (l) {
            case A -> {
                surface = Surface.GREEN_FELT;
                shape = Shape.RECT;
                wall = WallMaterial.WOOD;
                wallHeightIndex = 1;
                size = Size.M;
                camera = Camera.FIXED;
                pickup = Pickup.LIFT;
                rig = Rig.STUDIO;
                shadows = Shadows.PCF;
                transShadow = TransShadow.FULL;
                escape = Escape.VISIBLE;
            }
            case B -> {
                surface = Surface.LEATHER;
                shape = Shape.HEX;
                wall = WallMaterial.PADDED;
                wallHeightIndex = 1;
                size = Size.M;
                camera = Camera.SETTLE;
                pickup = Pickup.GLOW_LIFT;
                rig = Rig.LAMP;
                shadows = Shadows.PCSS;
                transShadow = TransShadow.TINTED;
                escape = Escape.GLASS;
            }
            case C -> {
                surface = Surface.WOOD;
                shape = Shape.RECT;
                wall = WallMaterial.WOOD;
                wallHeightIndex = 0;
                size = Size.L;
                camera = Camera.FOLLOW;
                pickup = Pickup.POP;
                rig = Rig.WINDOW;
                shadows = Shadows.PCSS;
                transShadow = TransShadow.TINTED;
                escape = Escape.GLASS;
            }
        }
    }

    float wallHeight() {
        return WALL_HEIGHTS[wallHeightIndex];
    }

    float renderScale() {
        return RENDER_SCALES[renderScaleIndex];
    }

    Dice dice() {
        return DICE[diceIndex];
    }

    /** An immutable snapshot of the numeral structure, handed to a build (ADR-0008's verdict, fixed). */
    record Structure(int version, float edgeRadius, StrokeFont.Style style, float weight, float size,
                     StrokeFont.Mark mark, OrientRule orient, PercentDir percentDir) {
    }

    /** ADR-0008's verdict, as the numerals prototype ended up: 0.75 mm edges, bold Chessex, 92%, underlined 6/9. */
    Structure structure() {
        return new Structure(0, 0.75f, StrokeFont.Style.CHESSEX, 0.16f, 0.92f,
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

    /** ADR-0008: 0.4 mm engraving with a thin film of ink, so about 0.36 mm shows. */
    static final float VISIBLE_DEPTH = 0.4f * (1 - 0.1f);

    static <E extends Enum<E>> E next(E e) {
        E[] all = e.getDeclaringClass().getEnumConstants();
        return all[(e.ordinal() + 1) % all.length];
    }
}
