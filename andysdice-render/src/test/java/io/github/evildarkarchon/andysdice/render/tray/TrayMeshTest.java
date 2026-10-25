package io.github.evildarkarchon.andysdice.render.tray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.core.tray.TrayFile;
import java.util.stream.Stream;
import org.joml.Vector3f;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The meshes a Tray is drawn with (ADR-0010): a floor filling the outline, walls whose inner faces stand on the same
 * outline physics collides with and rise exactly to the wall top (the escape volume is the visible walls), and the
 * table around them. World space is y-up and in mm.
 */
class TrayMeshTest {

    private static final float MM_PER_CM = 10;

    static Stream<Arguments> trays() {
        return BuiltInTrays.all().stream().map(tray -> Arguments.of(Named.of(tray.name(), tray)));
    }

    @Test
    void theFloorOfARectangleIsTheWholeFootprintAtYZero() {
        TrayMesh.Part floor = TrayMesh.of(BuiltInTrays.defaultTray()).floor();

        assertThat(area(floor)).isCloseTo(300f * 200f, within(0.5f));
        for (int v = 0; v < floor.vertexCount(); v++) {
            assertThat(floor.position(v).y).isZero();
            assertThat(floor.normal(v)).isEqualTo(new Vector3f(0, 1, 0));
        }
    }

    @Test
    void theFloorOfTheHexagonIsTheHexagon() {
        TrayMesh.Part floor = TrayMesh.of(BuiltInTrays.find("02-leather-hexagon").orElseThrow()).floor();

        // Two trapezoids, each 100 mm deep between a 300 mm middle and a 165 mm flat (0.55 of the half-width).
        assertThat(area(floor)).isCloseTo(2 * (300f + 165f) / 2 * 100f, within(0.5f));
    }

    @Test
    void theFloorsTextureRunsOnAtOneTilePer100Mm() {
        TrayMesh.Part floor = TrayMesh.of(BuiltInTrays.defaultTray()).floor();

        for (int v = 0; v < floor.vertexCount(); v++) {
            Vector3f p = floor.position(v);
            assertThat(floor.uv(v)[0]).isCloseTo(p.x / 100, within(1e-5f));
            assertThat(floor.uv(v)[1]).isCloseTo(p.z / 100, within(1e-5f));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trays")
    void theWallsRiseFromTheFloorExactlyToThePhysicsWallTop(Tray tray) {
        TrayMesh.Part walls = TrayMesh.of(tray).walls();
        float top = tray.geometry().wallHeight() * MM_PER_CM;

        float highest = Float.NEGATIVE_INFINITY;
        float lowest = Float.POSITIVE_INFINITY;
        for (int v = 0; v < walls.vertexCount(); v++) {
            highest = Math.max(highest, walls.position(v).y);
            lowest = Math.min(lowest, walls.position(v).y);
        }
        assertThat(highest).isCloseTo(top, within(1e-3f));
        assertThat(lowest).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trays")
    void theWallsInnerFacesStandOnTheOutlineAndNothingOfThemIsInside(Tray tray) {
        TrayGeometry geometry = tray.geometry();
        TrayMesh.Part walls = TrayMesh.of(tray).walls();

        float deepestOut = 0;
        int onTheOutline = 0;
        for (int v = 0; v < walls.vertexCount(); v++) {
            Vector3f p = walls.position(v);
            float depth = geometry.depthInside(p.x / MM_PER_CM, p.z / MM_PER_CM) * MM_PER_CM;
            assertThat(depth).as("vertex %d at %s", v, p).isLessThanOrEqualTo(1e-3f);
            deepestOut = Math.min(deepestOut, depth);
            if (depth > -1e-3f) {
                onTheOutline++;
            }
        }
        // Every corner has its inner face's foot and top on the outline; the outer face is a wall's thickness out.
        assertThat(onTheOutline).isGreaterThanOrEqualTo(2 * geometry.wallCount());
        assertThat(deepestOut).isLessThanOrEqualTo(-Tray.WALL_THICKNESS_MM + 1e-3f);
    }

    @Test
    void paddedWallsAreRoundedOverTheTopAndSquareOnesFlat() throws Exception {
        Tray padded = TrayFile.parse("p", "format = 1\nwalls.profile = padded\n");
        Tray square = TrayFile.parse("s", "format = 1\nwalls.profile = square\n");

        assertThat(slantedNormals(TrayMesh.of(padded).walls())).isPositive();
        assertThat(slantedNormals(TrayMesh.of(square).walls())).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trays")
    void everyPartIsWellFormed(Tray tray) {
        TrayMesh mesh = TrayMesh.of(tray);
        for (TrayMesh.Part part : new TrayMesh.Part[] {mesh.floor(), mesh.walls(), mesh.table()}) {
            assertThat(part.indices()).isNotEmpty();
            assertThat(java.util.Arrays.stream(part.indices()).boxed().toList())
                    .allSatisfy(i -> assertThat(i).isBetween(0, part.vertexCount() - 1));
            assertThat(part.indices().length % 3).isZero();
            for (int v = 0; v < part.vertexCount(); v++) {
                assertThat(part.normal(v).length()).isCloseTo(1f, within(1e-4f));
            }
        }
    }

    @Test
    void theTableLiesJustUnderTheFloorAndReachesFarBeyondTheWalls() {
        TrayMesh.Part table = TrayMesh.of(BuiltInTrays.defaultTray()).table();

        for (int v = 0; v < table.vertexCount(); v++) {
            assertThat(table.position(v).y).isNegative().isGreaterThan(-1f);
        }
        assertThat(area(table)).isGreaterThan(1000f * 1000f);
    }

    /** The summed area of a part's triangles, in mm². */
    private static float area(TrayMesh.Part part) {
        float sum = 0;
        int[] indices = part.indices();
        for (int t = 0; t < indices.length; t += 3) {
            Vector3f a = part.position(indices[t]);
            Vector3f ab = part.position(indices[t + 1]).sub(a);
            Vector3f ac = part.position(indices[t + 2]).sub(a);
            sum += ab.cross(ac).length() / 2;
        }
        return sum;
    }

    /** How many vertices have a normal neither horizontal nor vertical: the curve of a padded roll. */
    private static int slantedNormals(TrayMesh.Part part) {
        int count = 0;
        for (int v = 0; v < part.vertexCount(); v++) {
            float y = Math.abs(part.normal(v).y);
            if (y > 1e-3f && y < 1 - 1e-3f) {
                count++;
            }
        }
        return count;
    }
}
