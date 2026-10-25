package io.github.evildarkarchon.andysdice.core.tray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parsing one {@code .tray} file (ADR-0010), under ADR-0009's rules for {@code .diceset} files: a flat, versioned
 * {@code java.util.Properties} file in UTF-8 whose name is its identity, whose unknown keys are ignored and whose
 * invalid values fall back to their defaults, and which is rejected when it is of a newer (or no) format.
 */
class TrayFileTest {

    @Test
    void readsEveryKeyOfAFile() throws Exception {
        Tray tray = TrayFile.parse("99-test", String.join("\n",
                "# a comment",
                "format = 1",
                "name = Test tray",
                "outline = oval",
                "walls.profile = padded",
                "walls.material = leather",
                "walls.color = #102030",
                "walls.height = 42.5",
                "floor.surface = wood",
                "floor.color = #a0b0c0"));

        assertThat(tray.identity()).isEqualTo("99-test");
        assertThat(tray.name()).isEqualTo("Test tray");
        assertThat(tray.outline()).isEqualTo(TrayOutline.OVAL);
        assertThat(tray.walls()).isEqualTo(new Tray.Walls(WallProfile.PADDED, WallMaterial.LEATHER,
                new TrayColour(0x10, 0x20, 0x30), 42.5));
        assertThat(tray.floor()).isEqualTo(new Tray.Floor(FloorSurface.WOOD, new TrayColour(0xa0, 0xb0, 0xc0)));
    }

    @Test
    void aFileWithOnlyItsFormatIsTheDefaultLook() throws Exception {
        Tray tray = TrayFile.parse("plain", "format = 1\n");

        // The default Tray's look (spec Appendix C): green felt in a square-topped walnut rectangle, 30 mm walls,
        // in the tray prototype's colours (TrayTextures: felt (0.030, 0.105, 0.052), walnut (0.30, 0.17, 0.09),
        // linear) written as sRGB.
        assertThat(tray.name()).as("no name: the identity stands in").isEqualTo("plain");
        assertThat(tray.outline()).isEqualTo(TrayOutline.RECTANGLE);
        assertThat(tray.walls()).isEqualTo(new Tray.Walls(WallProfile.SQUARE, WallMaterial.WOOD,
                new TrayColour(0x95, 0x73, 0x55), 30));
        assertThat(tray.floor()).isEqualTo(new Tray.Floor(FloorSurface.FELT, new TrayColour(0x30, 0x5b, 0x40)));
    }

    @Test
    void anUnknownKeyIsIgnored() throws Exception {
        Tray tray = TrayFile.parse("x", "format = 1\noutline = hexagon\nlid = glass\nwalls.colour = #000000\n");

        assertThat(tray).isEqualTo(TrayFile.parse("x", "format = 1\noutline = hexagon\n"));
    }

    @Test
    void anInvalidValueFallsBackToItsDefaultAndKeepsTheRest() throws Exception {
        Tray tray = TrayFile.parse("x", String.join("\n",
                "format = 1",
                "outline = triangle",       // not a shape a Tray can have
                "walls.profile = ",         // empty
                "walls.material = glass",
                "walls.color = #12345",     // five digits
                "walls.height = 15",        // below the 30 mm minimum (ADR-0010)
                "floor.surface = sand",
                "floor.color = green",
                "name = Kept"));

        Tray defaults = TrayFile.parse("x", "format = 1\n");
        assertThat(tray.outline()).isEqualTo(defaults.outline());
        assertThat(tray.walls()).isEqualTo(defaults.walls());
        assertThat(tray.floor()).isEqualTo(defaults.floor());
        assertThat(tray.name()).as("the valid key survives").isEqualTo("Kept");
    }

    @Test
    void wallHeightsOutsideTheirRangeOrNotNumbersFallBack() throws Exception {
        assertThat(height("30")).isEqualTo(30);
        assertThat(height("29.9")).isEqualTo(30);
        assertThat(height(String.valueOf(Tray.MAX_WALL_HEIGHT_MM))).isEqualTo(Tray.MAX_WALL_HEIGHT_MM);
        assertThat(height(String.valueOf(Tray.MAX_WALL_HEIGHT_MM + 1))).isEqualTo(30);
        assertThat(height("NaN")).isEqualTo(30);
        assertThat(height("Infinity")).isEqualTo(30);
        assertThat(height("tall")).isEqualTo(30);
        assertThat(height("55")).isEqualTo(55);
    }

    private static double height(String value) throws Exception {
        return TrayFile.parse("h", "format = 1\nwalls.height = " + value + "\n").walls().heightMm();
    }

    @Test
    void valuesAreTrimmedAndNamesOfKindsTakeEitherCase() throws Exception {
        Tray tray = TrayFile.parse("x",
                "format = 1  \noutline = HexaGon \t\nwalls.color = #AbCdEf   \nfloor.surface = Leather\nname =  Spaced  \n");

        assertThat(tray.outline()).isEqualTo(TrayOutline.HEXAGON);
        assertThat(tray.walls().colour()).isEqualTo(new TrayColour(0xab, 0xcd, 0xef));
        assertThat(tray.floor().surface()).isEqualTo(FloorSurface.LEATHER);
        assertThat(tray.name()).isEqualTo("Spaced");
    }

    @Test
    void aNewerFormatIsRejected() {
        assertThatThrownBy(() -> TrayFile.parse("future", "format = 2\noutline = oval\n"))
                .isInstanceOf(TrayFormatException.class).hasMessageContaining("future").hasMessageContaining("2");
    }

    @Test
    void aMissingOrMalformedFormatIsRejected() {
        assertThatThrownBy(() -> TrayFile.parse("none", "outline = oval\n"))
                .isInstanceOf(TrayFormatException.class).hasMessageContaining("none");
        assertThatThrownBy(() -> TrayFile.parse("word", "format = one\n"))
                .isInstanceOf(TrayFormatException.class);
        assertThatThrownBy(() -> TrayFile.parse("zero", "format = 0\n"))
                .isInstanceOf(TrayFormatException.class);
    }

    @Test
    void aFilesNameWithoutItsExtensionIsItsIdentity(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("07-my-tray.tray");
        // UTF-8, not Properties' default ISO-8859-1 for streams.
        Files.writeString(file, "format = 1\nname = Ünïcode Ø\n", StandardCharsets.UTF_8);

        Tray tray = TrayFile.read(file);

        assertThat(tray.identity()).isEqualTo("07-my-tray");
        assertThat(tray.name()).isEqualTo("Ünïcode Ø");
    }

    @Test
    void coloursConvertFromSrgbToLinear() {
        // The sRGB transfer function's known points: 0 -> 0, 255 -> 1, 188 -> ~0.5029, 10 -> 10/255/12.92.
        TrayColour c = new TrayColour(0, 188, 255);
        assertThat(c.linearRed()).isZero();
        assertThat(c.linearGreen()).isCloseTo(0.5029, within(1e-3));
        assertThat(c.linearBlue()).isEqualTo(1.0);
        assertThat(new TrayColour(10, 0, 0).linearRed()).isCloseTo(10 / 255.0 / 12.92, within(1e-9));
        assertThat(TrayColour.parse("#0a0B0c")).contains(new TrayColour(10, 11, 12));
        assertThat(TrayColour.parse("0a0b0c")).isEmpty();
        assertThatThrownBy(() -> new TrayColour(256, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
