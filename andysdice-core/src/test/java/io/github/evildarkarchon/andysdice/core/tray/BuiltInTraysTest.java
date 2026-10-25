package io.github.evildarkarchon.andysdice.core.tray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.diceset.SrgbColour;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.OutlinePoint;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * The three built-in Trays (spec Appendix C, ADR-0010), read from the {@code .tray} files that ship inside the app,
 * and what physics gets from a Tray: its geometry in the Footprint and the physics materials of its floor and walls.
 */
class BuiltInTraysTest {

    private static final Offset<Float> CLOSE = within(1e-4f);

    @Test
    void theBuiltInsAreAppendixCsThreeTraysInOrderWithGreenFeltFirst() {
        assertThat(BuiltInTrays.all()).extracting(Tray::identity)
                .containsExactly("01-green-felt", "02-leather-hexagon", "03-walnut-board");
        assertThat(BuiltInTrays.all()).extracting(Tray::name)
                .containsExactly("Green felt", "Leather hexagon", "Walnut board");
        assertThat(BuiltInTrays.defaultTray()).isSameAs(BuiltInTrays.all().getFirst());
        assertThat(BuiltInTrays.DEFAULT_IDENTITY).isEqualTo("01-green-felt");
    }

    @Test
    void greenFeltIsARectangleOfSquareTopWalnutWallsOverGreenFelt() {
        Tray tray = BuiltInTrays.defaultTray();

        assertThat(tray.outline()).isEqualTo(TrayOutline.RECTANGLE);
        assertThat(tray.walls()).isEqualTo(new Tray.Walls(WallProfile.SQUARE, WallMaterial.WOOD,
                new SrgbColour(0x95, 0x73, 0x55), 30));
        assertThat(tray.walls().material().restitution()).as("Appendix C's wall bounce").isEqualTo(0.5f);
        assertThat(tray.floor()).isEqualTo(new Tray.Floor(FloorSurface.FELT, new SrgbColour(0x30, 0x5b, 0x40)));
    }

    @Test
    void leatherHexagonIsAHexagonOfPaddedLeatherWallsOverBrownLeather() {
        Tray tray = BuiltInTrays.find("02-leather-hexagon").orElseThrow();

        // The tray prototype's leather, linear (0.060, 0.030, 0.016), on the floor and the padded roll alike.
        assertThat(tray.outline()).isEqualTo(TrayOutline.HEXAGON);
        assertThat(tray.walls()).isEqualTo(new Tray.Walls(WallProfile.PADDED, WallMaterial.LEATHER,
                new SrgbColour(0x45, 0x30, 0x22), 30));
        assertThat(tray.walls().material().restitution()).as("Appendix C's wall bounce").isEqualTo(0.2f);
        assertThat(tray.floor()).isEqualTo(new Tray.Floor(FloorSurface.LEATHER, new SrgbColour(0x45, 0x30, 0x22)));
    }

    @Test
    void walnutBoardIsARectangleOfSquareTopWalnutWallsOverBareWalnut() {
        Tray tray = BuiltInTrays.find("03-walnut-board").orElseThrow();

        assertThat(tray.outline()).isEqualTo(TrayOutline.RECTANGLE);
        assertThat(tray.walls()).isEqualTo(new Tray.Walls(WallProfile.SQUARE, WallMaterial.WOOD,
                new SrgbColour(0x95, 0x73, 0x55), 30));
        assertThat(tray.floor()).isEqualTo(new Tray.Floor(FloorSurface.WOOD, new SrgbColour(0x95, 0x73, 0x55)));
    }

    @Test
    void aMissingTrayFallsBackToTheDefault() {
        assertThat(BuiltInTrays.find("99-gone")).isEmpty();
        assertThat(BuiltInTrays.orDefault("99-gone")).isSameAs(BuiltInTrays.defaultTray());
        assertThat(BuiltInTrays.orDefault("03-walnut-board").name()).isEqualTo("Walnut board");
    }

    @Test
    void aRectangleIsTheWholeFootprintInCentimetres() {
        TrayGeometry geometry = BuiltInTrays.defaultTray().geometry();

        assertThat(geometry.wallHeight()).isEqualTo(3f);
        assertThat(geometry.outline()).containsExactlyInAnyOrder(new OutlinePoint(15, 10), new OutlinePoint(-15, 10),
                new OutlinePoint(-15, -10), new OutlinePoint(15, -10));
    }

    @Test
    void theHexagonIsPointedLeftAndRightWithItsFlatsAt055OfTheHalfWidth() {
        TrayGeometry geometry = BuiltInTrays.find("02-leather-hexagon").orElseThrow().geometry();

        // The tray prototype's TrayGeom.outline(HEX) at the 300 x 200 mm Footprint, in cm.
        assertThat(geometry.outline()).containsExactlyInAnyOrder(new OutlinePoint(15, 0),
                new OutlinePoint(8.25f, 10), new OutlinePoint(-8.25f, 10), new OutlinePoint(-15, 0),
                new OutlinePoint(-8.25f, -10), new OutlinePoint(8.25f, -10));
    }

    @Test
    void anOvalTouchesTheFootprintsFourSidesAndStaysInsideIt() throws Exception {
        TrayGeometry geometry = TrayFile.parse("oval", "format = 1\noutline = oval\n").geometry();

        assertThat(geometry.wallCount()).isEqualTo(32);
        assertThat(geometry.outline()).contains(new OutlinePoint(15, 0), new OutlinePoint(-15, 0));
        assertThat(geometry.outline()).allSatisfy(p -> {
            assertThat(Math.abs(p.x())).isLessThanOrEqualTo(15f);
            assertThat(Math.abs(p.z())).isLessThanOrEqualTo(10f);
        });
        assertThat(geometry.outline()).anySatisfy(p -> assertThat(p.z()).isCloseTo(10f, CLOSE));
        // A superellipse, not an ellipse: at 45° round it is further out than the ellipse's 0.707.
        assertThat(geometry.depthInside(15 * 0.75f, 10 * 0.75f)).isPositive();
    }

    @Test
    void aTallerWallIsATallerWallInPhysics() throws Exception {
        assertThat(TrayFile.parse("t", "format = 1\nwalls.height = 45\n").geometry().wallHeight()).isEqualTo(4.5f);
    }

    @Test
    void greenFeltsMaterialsAreTheTrayPrototypesFeltInWalnut() {
        TrayMaterials materials = BuiltInTrays.defaultTray().materials();

        // The combined pair values the flat-rest prototype was measured with (#39's soak): a Die of friction 0.4
        // against felt's 0.5 and the walls' 0.5, Jolt's geometric mean; the floor's restitution, and the wall's own
        // bounce (spec Appendix C).
        assertThat(materials.floor().friction()).isCloseTo(0.4472f, CLOSE);
        assertThat(materials.floor().restitution()).isEqualTo(0.15f);
        assertThat(materials.walls().friction()).isCloseTo(0.4472f, CLOSE);
        assertThat(materials.walls().restitution()).isEqualTo(0.5f);
        assertThat(materials.dieOnDie()).isEqualTo(new ContactMaterial(0.2f, 0.15f));
        assertThat(materials.dieAngularDamping()).isEqualTo(0.6f);
    }

    @Test
    void leatherHexagonsAndWalnutBoardsMaterialsAreTheirSurfaces() {
        TrayMaterials leather = BuiltInTrays.find("02-leather-hexagon").orElseThrow().materials();
        assertThat(leather.floor().friction()).isCloseTo(0.4690f, CLOSE);
        assertThat(leather.floor().restitution()).isEqualTo(0.25f);
        assertThat(leather.walls().friction()).isCloseTo(0.4472f, CLOSE);
        // Spec Appendix C: the padded leather walls bounce 0.2, under leather's floor 0.25 (set per contact).
        assertThat(leather.walls().restitution()).as("padded leather wall bounce").isEqualTo(0.2f);
        assertThat(leather.dieAngularDamping()).isEqualTo(0.45f);

        TrayMaterials walnut = BuiltInTrays.find("03-walnut-board").orElseThrow().materials();
        assertThat(walnut.floor().friction()).isCloseTo(0.3742f, CLOSE);
        assertThat(walnut.floor().restitution()).isEqualTo(0.45f);
        assertThat(walnut.walls().restitution()).isEqualTo(0.5f);
        assertThat(walnut.dieAngularDamping()).isEqualTo(0.2f);
    }

    @Test
    void everyFloorAndWallMaterialSetsItsOwnPhysicsAndDieOnDieFrictionStays02() throws Exception {
        for (FloorSurface surface : FloorSurface.values()) {
            for (WallMaterial wall : WallMaterial.values()) {
                TrayMaterials materials = TrayFile.parse("m", "format = 1\nfloor.surface = " + surface
                        + "\nwalls.material = " + wall + "\n").materials();

                assertThat(materials.floor().restitution()).isEqualTo(surface.restitution());
                // The wall's own bounce, whatever the floor: the combined value is set on each contact.
                assertThat(materials.walls().restitution()).isEqualTo(wall.restitution());
                assertThat(materials.dieAngularDamping()).isEqualTo(surface.spinDamping());
                assertThat(materials.dieOnDie().friction()).isEqualTo(TrayMaterials.DIE_ON_DIE_FRICTION)
                        .isEqualTo(0.2f);
            }
        }
        // Felt swallows bounces, wood clatters; padded leather walls give back less than wooden ones.
        assertThat(FloorSurface.FELT.restitution()).isLessThan(FloorSurface.WOOD.restitution());
        assertThat(WallMaterial.LEATHER.restitution()).isLessThan(WallMaterial.WOOD.restitution());
        assertThat(materialsOf("felt", "leather").walls().restitution())
                .isLessThan(materialsOf("felt", "wood").walls().restitution());
        assertThat(materialsOf("felt", "wood").floor()).isNotEqualTo(materialsOf("wood", "wood").floor());
    }

    private static TrayMaterials materialsOf(String floor, String walls) throws Exception {
        return TrayFile.parse("m", "format = 1\nfloor.surface = " + floor + "\nwalls.material = " + walls + "\n")
                .materials();
    }
}
