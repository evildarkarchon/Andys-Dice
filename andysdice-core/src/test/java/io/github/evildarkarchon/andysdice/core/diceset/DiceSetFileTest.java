package io.github.evildarkarchon.andysdice.core.diceset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parsing one {@code .diceset} file (ADR-0009): a flat, versioned {@code java.util.Properties} file whose name is
 * its identity, whose unknown keys are ignored and whose invalid values fall back to their defaults, and which is
 * rejected when it is of a newer (or no) format.
 */
class DiceSetFileTest {

    @Test
    void readsEveryKeyOfAFile() throws Exception {
        DiceSet set = DiceSetFile.parse("99-test", String.join("\n",
                "# a comment",
                "format = 1",
                "name = Test / gold",
                "base = #9a1422",
                "roughness = 0.16",
                "metallic = 0.5",
                "swirl.color = #e6e9f2",
                "swirl.scale = 11",
                "swirl.warp = 1.8",
                "swirl.bands = 3.5",
                "swirl.amount = 0.45",
                "swirl.softness = 0.14",
                "flakes.color = #1c1a18",
                "flakes.density = 0.55",
                "flakes.size = 0.35",
                "flakes.metallic = 1",
                "pearl = 2.0",
                "transmission = 0.7",
                "transmission.color = #ff0000",
                "ior = 1.6",
                "cloudiness = 0.3",
                "ink = #e2b45a",
                "ink.metallic = 1",
                "ink.roughness = 0.35"));

        assertThat(set.identity()).isEqualTo("99-test");
        assertThat(set.name()).isEqualTo("Test / gold");
        assertThat(set.base()).isEqualTo(new SrgbColour(0x9a, 0x14, 0x22));
        assertThat(set.roughness()).isEqualTo(0.16);
        assertThat(set.metallic()).isEqualTo(0.5);
        assertThat(set.swirl()).isEqualTo(new DiceSet.Swirl(new SrgbColour(0xe6, 0xe9, 0xf2), 11, 1.8, 3.5, 0.45, 0.14));
        assertThat(set.flakes()).isEqualTo(new DiceSet.Flakes(new SrgbColour(0x1c, 0x1a, 0x18), 0.55, 0.35, 1));
        assertThat(set.pearl()).isEqualTo(2.0);
        assertThat(set.transmission())
                .isEqualTo(new DiceSet.Transmission(0.7, new SrgbColour(0xff, 0, 0), 1.6, 0.3));
        assertThat(set.ink()).isEqualTo(new DiceSet.Ink(new SrgbColour(0xe2, 0xb4, 0x5a), 1, 0.35));
    }

    @Test
    void aFileWithOnlyItsFormatHasEveryDefault() throws Exception {
        DiceSet set = DiceSetFile.parse("plain", "format = 1\n");

        // ADR-0009's defaults, as the materials prototype's DiceSet.parse() had them.
        assertThat(set.name()).as("no name: the identity stands in").isEqualTo("plain");
        assertThat(set.base()).isEqualTo(new SrgbColour(0x20, 0x20, 0x20));
        assertThat(set.roughness()).isEqualTo(0.2);
        assertThat(set.metallic()).isZero();
        assertThat(set.swirl()).isEqualTo(new DiceSet.Swirl(new SrgbColour(0xff, 0xff, 0xff), 8, 1, 0, 0, 0.08));
        assertThat(set.flakes()).isEqualTo(new DiceSet.Flakes(new SrgbColour(0xff, 0xff, 0xff), 0, 0.4, 0));
        assertThat(set.pearl()).isZero();
        assertThat(set.transmission())
                .isEqualTo(new DiceSet.Transmission(0, new SrgbColour(0xff, 0xff, 0xff), 1.54, 0));
        assertThat(set.ink()).isEqualTo(new DiceSet.Ink(new SrgbColour(0xf0, 0xf0, 0xe8), 0, 0.6));
        assertThat(set.hasSwirl()).isFalse();
        assertThat(set.translucent()).isFalse();
    }

    @Test
    void anUnknownKeyIsIgnored() throws Exception {
        DiceSet set = DiceSetFile.parse("x", "format = 1\nroughness = 0.5\nsparkle = 11\nswirl.colour = #000000\n");

        assertThat(set).isEqualTo(DiceSetFile.parse("x", "format = 1\nroughness = 0.5\n"));
    }

    @Test
    void anInvalidValueFallsBackToItsDefaultAndKeepsTheRest() throws Exception {
        DiceSet set = DiceSetFile.parse("x", String.join("\n",
                "format = 1",
                "base = #12345",          // five digits
                "roughness = smooth",     // not a number
                "metallic = 1.5",         // out of [0, 1]
                "pearl = NaN",
                "swirl.scale = 0.1",      // below 0.5
                "ink = red",
                "ink.roughness = 0.35"));

        assertThat(set.base()).isEqualTo(new SrgbColour(0x20, 0x20, 0x20));
        assertThat(set.roughness()).isEqualTo(0.2);
        assertThat(set.metallic()).isZero();
        assertThat(set.pearl()).isZero();
        assertThat(set.swirl().scale()).isEqualTo(8);
        assertThat(set.ink().colour()).isEqualTo(new SrgbColour(0xf0, 0xf0, 0xe8));
        assertThat(set.ink().roughness()).as("the valid key survives").isEqualTo(0.35);
    }

    @Test
    void valuesAreTrimmedAndColoursTakeEitherCase() throws Exception {
        DiceSet set = DiceSetFile.parse("x", "format = 1  \nbase = #AbCdEf   \nroughness = 0.5\t\nname =  Spaced  \n");

        assertThat(set.base()).isEqualTo(new SrgbColour(0xab, 0xcd, 0xef));
        assertThat(set.roughness()).isEqualTo(0.5);
        assertThat(set.name()).isEqualTo("Spaced");
    }

    @Test
    void aNewerFormatIsRejected() {
        assertThatThrownBy(() -> DiceSetFile.parse("future", "format = 2\nbase = #ffffff\n"))
                .isInstanceOf(DiceSetFormatException.class).hasMessageContaining("future").hasMessageContaining("2");
    }

    @Test
    void aMissingOrMalformedFormatIsRejected() {
        assertThatThrownBy(() -> DiceSetFile.parse("none", "base = #ffffff\n"))
                .isInstanceOf(DiceSetFormatException.class).hasMessageContaining("none");
        assertThatThrownBy(() -> DiceSetFile.parse("word", "format = one\n"))
                .isInstanceOf(DiceSetFormatException.class);
        assertThatThrownBy(() -> DiceSetFile.parse("zero", "format = 0\n"))
                .isInstanceOf(DiceSetFormatException.class);
    }

    @Test
    void aFilesNameWithoutItsExtensionIsItsIdentity(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("07-my-set.diceset");
        // UTF-8, not Properties' default ISO-8859-1 for streams.
        Files.writeString(file, "format = 1\nname = Ünïcode Ø\n", StandardCharsets.UTF_8);

        DiceSet set = DiceSetFile.read(file);

        assertThat(set.identity()).isEqualTo("07-my-set");
        assertThat(set.name()).isEqualTo("Ünïcode Ø");
    }

    @Test
    void coloursConvertFromSrgbToLinear() {
        // The sRGB transfer function's known points: 0 -> 0, 255 -> 1, 188 -> ~0.5029, 10 -> 10/255/12.92.
        SrgbColour c = new SrgbColour(0, 188, 255);
        assertThat(c.linearRed()).isZero();
        assertThat(c.linearGreen()).isCloseTo(0.5029, within(1e-3));
        assertThat(c.linearBlue()).isEqualTo(1.0);
        assertThat(new SrgbColour(10, 0, 0).linearRed()).isCloseTo(10 / 255.0 / 12.92, within(1e-9));
        assertThat(SrgbColour.parse("#0a0B0c")).contains(new SrgbColour(10, 11, 12));
        assertThat(SrgbColour.parse("0a0b0c")).isEmpty();
        assertThatThrownBy(() -> new SrgbColour(256, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
