package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.core.tray.TrayColour;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The chosen Tray drawn in the Dice scene on a real GL 3.3 core context (ADR-0005, ADR-0010): its floor, walls and the
 * table around it, in the Tray file's colours. Asserts invariants (what covers which pixel, which channel leads),
 * never golden images.
 */
@Tag("gl")
class TraySceneTest {

    private static final int SIZE = 96;

    /**
     * Straight down on the Tray from 800 mm with a 32° lens: the floor fills the middle, the right-hand wall's top
     * (x 150 to 164 mm, 30 mm up) lands near column 82, and the table beyond it near column 92.
     */
    private static final DiceScene.Camera TOP_DOWN =
            new DiceScene.Camera(new Vector3d(0, 800, 0.01), new Vector3d(0, 0, 0), 32);
    private static final int WALL_COLUMN = 82;
    private static final int TABLE_COLUMN = 93;

    private static CaptureTransport capture;
    private static DiceScene scene;
    private static RenderThread renderer;

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

    static Stream<Arguments> trays() {
        return BuiltInTrays.all().stream().map(tray -> Arguments.of(Named.of(tray.name(), tray)));
    }

    @Test
    void theTrayShaderCompilesAndLinks() throws Exception {
        int program = renderer.submit(() -> {
            int p = Shaders.program("tray.vert", "tray.frag");
            glDeleteProgram(p);
            return p;
        }).get(10, TimeUnit.SECONDS);

        assertThat(program).isPositive();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trays")
    void theFloorWallsAndTableAreDrawnInTheTraysColours(Tray tray) throws Exception {
        Still still = capture.take(renderer, () -> {
            scene.setTray(tray);
            scene.setDice(List.of());
            scene.setCamera(TOP_DOWN);
        }).get(30, TimeUnit.SECONDS);

        int floor = still.argb(SIZE / 2, SIZE / 2);
        assertThat(floor).as("the floor covers the middle with no Dice").isNotEqualTo(DiceScene.BACKGROUND_ARGB);
        assertLeadingChannelsMatch(floor, tray.floor().colour());
        int wall = still.argb(WALL_COLUMN, SIZE / 2);
        assertThat(wall).isNotEqualTo(DiceScene.BACKGROUND_ARGB);
        assertLeadingChannelsMatch(wall, tray.walls().colour());
        assertThat(still.argb(TABLE_COLUMN, SIZE / 2)).as("the table beyond the walls")
                .isNotEqualTo(DiceScene.BACKGROUND_ARGB);
    }

    @Test
    void theFeltFloorIsGreenAndItsWalnutWallsAreNot() throws Exception {
        Still still = capture.take(renderer, () -> {
            scene.setTray(BuiltInTrays.defaultTray());
            scene.setDice(List.of());
            scene.setCamera(TOP_DOWN);
        }).get(30, TimeUnit.SECONDS);

        int floor = still.argb(SIZE / 2, SIZE / 2);
        int wall = still.argb(WALL_COLUMN, SIZE / 2);
        assertThat(green(floor)).isGreaterThan(red(floor)).isGreaterThan(blue(floor));
        assertThat(red(wall)).isGreaterThan(green(wall)).isGreaterThan(blue(wall));
    }

    @Test
    void diceAreDrawnOnTheTray() throws Exception {
        Still empty = capture.take(renderer, () -> {
            scene.setTray(BuiltInTrays.defaultTray());
            scene.setDice(List.of());
            scene.setCamera(TOP_DOWN);
        }).get(30, TimeUnit.SECONDS);
        // A d20 resting in the middle, 11 mm up: it, not the felt, covers the centre.
        Still withDie = capture.take(renderer, () -> scene.setDice(List.of(
                new DiceScene.Die(DieKind.D20, new Vector3d(0, 11, 0), new Quaterniond()))))
                .get(30, TimeUnit.SECONDS);

        assertThat(withDie.argb(SIZE / 2, SIZE / 2)).isNotEqualTo(empty.argb(SIZE / 2, SIZE / 2));
        assertThat(withDie.argb(SIZE / 4, SIZE / 2)).as("the felt beside it is untouched")
                .isEqualTo(empty.argb(SIZE / 4, SIZE / 2));
    }

    /** The pixel's channels rank as the colour's do, wherever the colour's two leading channels differ clearly. */
    private static void assertLeadingChannelsMatch(int argb, TrayColour colour) {
        int[] drawn = {red(argb), green(argb), blue(argb)};
        int[] given = {colour.red(), colour.green(), colour.blue()};
        int brightest = 0;
        for (int c = 1; c < 3; c++) {
            if (given[c] > given[brightest]) {
                brightest = c;
            }
        }
        for (int c = 0; c < 3; c++) {
            if (c != brightest && given[brightest] - given[c] > 20) {
                assertThat(drawn[brightest]).as("channel %d leads %d in %08x for %s", brightest, c, argb, colour)
                        .isGreaterThan(drawn[c]);
            }
        }
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xff;
    }

    private static int green(int argb) {
        return (argb >> 8) & 0xff;
    }

    private static int blue(int argb) {
        return argb & 0xff;
    }
}
