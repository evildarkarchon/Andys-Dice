package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The Dice Set uber-shader (ticket #42, ADR-0009) on a real GL 3.3 core context: it builds and draws every opaque
 * look of spec Appendix B, and each look's own feature reaches the pixels. Asserts invariants (what turning a
 * feature off changes, how a metal's light is spread), never golden images.
 */
@Tag("gl")
class DiceSetLookTest {

    private static final int SIZE = 128;

    private static CaptureTransport capture;
    private static DiceScene scene;
    private static RenderThread renderer;

    /** Spec Appendix B's opaque looks, each with a built-in Dice Set that has it. */
    enum Look {
        SOLID("02-ivory"),
        SWIRL("04-vortex-blue"),
        SPECKLE("06-speckled-stone"),
        PEARL("07-pearl-violet"),
        METAL("08-steel");

        final String identity;

        Look(String identity) {
            this.identity = identity;
        }

        DiceSet diceSet() {
            return DiceSetCatalogue.builtIn().find(identity).orElseThrow();
        }
    }

    @BeforeAll
    static void startRenderThread() {
        capture = new CaptureTransport();
        scene = new DiceScene();
        renderer = RenderThread.start(capture, scene);
        renderer.resize(SIZE, SIZE);
    }

    @AfterAll
    static void stopRenderThread() {
        if (renderer != null) {
            renderer.close();
        }
    }

    @Test
    void theUberShaderCompilesAndLinks() throws Exception {
        int program = renderer.submit(() -> {
            int p = Shaders.program("die.vert", "die.frag");
            glDeleteProgram(p);
            return p;
        }).get(10, TimeUnit.SECONDS);

        assertThat(program).isPositive();
    }

    @ParameterizedTest
    @EnumSource(Look.class)
    void everyLookDrawsADieWithoutAGlError(Look look) throws Exception {
        renderer.submit(() -> {
            // Start clean, so an error found below is this frame's.
            while (glGetError() != GL_NO_ERROR) {
                // Drained: earlier work's errors aren't this test's.
            }
            return null;
        }).get(10, TimeUnit.SECONDS);

        Still still = draw(look.diceSet(), DieKind.D20);

        assertThat(still.argb(SIZE / 2, SIZE / 2)).as("the die covers the centre")
                .isNotEqualTo(DiceScene.BACKGROUND_ARGB);
        int error = renderer.submit(() -> glGetError()).get(10, TimeUnit.SECONDS);
        assertThat(error).as("GL error after drawing %s", look).isEqualTo(GL_NO_ERROR);
    }

    @Test
    void everyBuiltInDiceSetLooksDifferent() throws Exception {
        List<DiceSet> sets = DiceSetCatalogue.builtIn().diceSets();
        int[][] stills = new int[sets.size()][];
        for (int i = 0; i < sets.size(); i++) {
            stills[i] = draw(sets.get(i), DieKind.D12).pixels();
        }
        for (int i = 0; i < sets.size(); i++) {
            for (int j = i + 1; j < sets.size(); j++) {
                assertThat(Arrays.equals(stills[i], stills[j]))
                        .as("%s and %s", sets.get(i).identity(), sets.get(j).identity()).isFalse();
            }
        }
    }

    @Test
    void theSwirlReachesThePixels() throws Exception {
        DiceSet vortex = Look.SWIRL.diceSet();
        DiceSet noSwirl = withSwirlAmount(vortex, 0);

        // The second resin covers a good part of the body; without it, the body is the base colour throughout.
        assertThat(changedFraction(draw(vortex, DieKind.D20), draw(noSwirl, DieKind.D20))).isGreaterThan(0.15);
    }

    @Test
    void speckleReachesThePixels() throws Exception {
        DiceSet stone = Look.SPECKLE.diceSet();
        DiceSet noFlakes = new DiceSet(stone.identity(), stone.name(), stone.base(), stone.roughness(),
                stone.metallic(), stone.swirl(),
                new DiceSet.Flakes(stone.flakes().colour(), 0, stone.flakes().size(), stone.flakes().metallic()),
                stone.pearl(), stone.transmission(), stone.ink());

        assertThat(changedFraction(draw(stone, DieKind.D20), draw(noFlakes, DieKind.D20))).isGreaterThan(0.05);
    }

    @Test
    void pearlReachesThePixels() throws Exception {
        DiceSet pearl = Look.PEARL.diceSet();
        DiceSet noPearl = new DiceSet(pearl.identity(), pearl.name(), pearl.base(), pearl.roughness(),
                pearl.metallic(), pearl.swirl(), pearl.flakes(), 0, pearl.transmission(), pearl.ink());

        // Pearl only adds light, a subtle sheen where the swirl is (about +4 of 255 on average on the dev GPU).
        double withPearl = Arrays.stream(luminances(draw(pearl, DieKind.D20))).average().orElseThrow();
        double without = Arrays.stream(luminances(draw(noPearl, DieKind.D20))).average().orElseThrow();
        assertThat(withPearl).as("mean luminance; %s without pearl", without).isGreaterThan(without + 2);
    }

    @Test
    void aSwirlIsBakedOnceOnFirstUseAndASolidNever() throws Exception {
        DiceSet vortex = Look.SWIRL.diceSet();
        DiceSet.Swirl s = vortex.swirl();
        // A field no other test draws (its warp), so the count's changes are this test's alone.
        DiceSet fresh = new DiceSet("fresh", "Fresh", vortex.base(), vortex.roughness(), vortex.metallic(),
                new DiceSet.Swirl(s.colour(), s.scale(), 3.21, s.bands(), s.amount(), s.softness()),
                vortex.flakes(), vortex.pearl(), vortex.transmission(), vortex.ink());
        // The same field in other colours and amounts: only the noise decides a bake.
        DiceSet recoloured = new DiceSet("recoloured", "Recoloured", vortex.base(), vortex.roughness(),
                vortex.metallic(), new DiceSet.Swirl(vortex.ink().colour(), s.scale(), 3.21, s.bands(), 0.7, 0.2),
                vortex.flakes(), vortex.pearl(), vortex.transmission(), vortex.ink());

        int before = bakedAfterDrawing(Look.SOLID.diceSet());
        assertThat(bakedAfterDrawing(fresh)).isEqualTo(before + 1);
        assertThat(bakedAfterDrawing(fresh)).isEqualTo(before + 1);
        assertThat(bakedAfterDrawing(recoloured)).isEqualTo(before + 1);
        assertThat(bakedAfterDrawing(Look.SOLID.diceSet())).isEqualTo(before + 1);
    }

    private static int bakedAfterDrawing(DiceSet set) throws Exception {
        draw(set, DieKind.D8);
        return renderer.submit(() -> scene.bakedSwirls()).get(10, TimeUnit.SECONDS);
    }

    @Test
    void twoDiceOfASwirledSetDoNotMatch() throws Exception {
        DiceSet vortex = Look.SWIRL.diceSet();
        // Two d6s side by side, posed alike: only their offsets into the swirl differ.
        Still still = capture.take(renderer, () -> {
            scene.setDiceSet(vortex);
            scene.setCamera(new DiceScene.Camera(new Vector3d(0, 30, 60), new Vector3d(), 32));
            scene.setDice(List.of(new DiceScene.Die(DieKind.D6, new Vector3d(-10, 0, 0), new Quaterniond()),
                    new DiceScene.Die(DieKind.D6, new Vector3d(10, 0, 0), new Quaterniond())));
        }).get(10, TimeUnit.SECONDS);

        int differing = 0;
        int covered = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE / 2; x++) {
                int left = still.argb(x, y);
                int right = still.argb(x + SIZE / 2, y);
                if (left == DiceScene.BACKGROUND_ARGB || right == DiceScene.BACKGROUND_ARGB) {
                    continue;
                }
                covered++;
                if (distance(left, right) > 24) {
                    differing++;
                }
            }
        }
        assertThat(covered).isGreaterThan(SIZE * SIZE / 20);
        assertThat(differing).as("pixels of %d that differ between the two Dice", covered)
                .isGreaterThan(covered / 10);
    }

    @Test
    void metalDiceReflectRatherThanLookLikeGreyPlastic() throws Exception {
        DiceSet steel = Look.METAL.diceSet();
        DiceSet greyPlastic = new DiceSet("grey-plastic", "Grey plastic", steel.base(), steel.roughness(), 0,
                steel.swirl(), steel.flakes(), steel.pearl(), steel.transmission(), steel.ink());

        double[] metal = luminances(draw(steel, DieKind.D20));
        double[] plastic = luminances(draw(greyPlastic, DieKind.D20));

        // A metal has no diffuse light: each face shows only what it mirrors. Faces that mirror the dim room go
        // dark (on the dev GPU a quarter of the Die sat near 27 of 255, where the plastic's sat near 116), while
        // those that mirror a softbox are as bright as the evenly lit plastic's brightest (226 against 221).
        assertThat(percentile(metal, 0.25)).as("metal's dim faces; plastic's lower quartile %s",
                percentile(plastic, 0.25)).isLessThan(0.5 * percentile(plastic, 0.25));
        assertThat(percentile(metal, 0.98)).as("metal's reflections; plastic's brightest %s",
                percentile(plastic, 0.98)).isGreaterThan(0.9 * percentile(plastic, 0.98));
    }

    /** Draws one Die of a kind in a Dice Set, filling most of the frame, and captures it. */
    private static Still draw(DiceSet set, DieKind kind) throws Exception {
        return capture.take(renderer, () -> {
            scene.setDiceSet(set);
            scene.setCamera(new DiceScene.Camera(new Vector3d(0, 25, -40), new Vector3d(), 32));
            scene.setDice(List.of(new DiceScene.Die(kind, new Vector3d(),
                    new Quaterniond().rotationXYZ(0.4, 0.7, 0.2))));
        }).get(10, TimeUnit.SECONDS);
    }

    private static DiceSet withSwirlAmount(DiceSet set, double amount) {
        DiceSet.Swirl s = set.swirl();
        return new DiceSet(set.identity(), set.name(), set.base(), set.roughness(), set.metallic(),
                new DiceSet.Swirl(s.colour(), s.scale(), s.warp(), s.bands(), amount, s.softness()),
                set.flakes(), set.pearl(), set.transmission(), set.ink());
    }

    /** The share of the Die's pixels (in either still) whose colour differs clearly between the two. */
    private static double changedFraction(Still a, Still b) {
        int covered = 0;
        int changed = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int p = a.argb(x, y);
                int q = b.argb(x, y);
                if (p == DiceScene.BACKGROUND_ARGB && q == DiceScene.BACKGROUND_ARGB) {
                    continue;
                }
                covered++;
                if (distance(p, q) > 24) {
                    changed++;
                }
            }
        }
        assertThat(covered).isGreaterThan(SIZE * SIZE / 10);
        return changed / (double) covered;
    }

    /** The largest channel difference between two colours, 0..255. */
    private static int distance(int p, int q) {
        int d = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            d = Math.max(d, Math.abs(((p >> shift) & 0xff) - ((q >> shift) & 0xff)));
        }
        return d;
    }

    /** The Die's pixels' luminances (Rec. 709 weights on the stored bytes), 0..255, sorted. */
    private static double[] luminances(Still still) {
        return Arrays.stream(still.pixels()).filter(argb -> argb != DiceScene.BACKGROUND_ARGB)
                .mapToDouble(argb -> 0.2126 * ((argb >> 16) & 0xff) + 0.7152 * ((argb >> 8) & 0xff)
                        + 0.0722 * (argb & 0xff))
                .sorted().toArray();
    }

    private static double percentile(double[] sorted, double p) {
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
    }
}
