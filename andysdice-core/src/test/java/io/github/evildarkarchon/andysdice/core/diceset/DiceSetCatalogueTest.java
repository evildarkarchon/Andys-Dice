package io.github.evildarkarchon.andysdice.core.diceset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The built-in Dice Sets (spec Appendix B) shipped inside the app, and choosing one by identity with Onyx as the
 * fallback (ADR-0009).
 */
class DiceSetCatalogueTest {

    @Test
    void allFourteenBuiltInsShipInAppendixBOrder() {
        List<DiceSet> builtIn = DiceSetCatalogue.builtIn().diceSets();

        // Spec Appendix B, rows 1-14: ten opaque looks, then the four translucent ones (ticket #43).
        assertThat(builtIn).extracting(DiceSet::identity).containsExactly("01-onyx", "02-ivory", "03-crimson",
                "04-vortex-blue", "05-jade-marble", "06-speckled-stone", "07-pearl-violet", "08-steel", "09-gold",
                "10-gunmetal", "11-smoke", "12-ruby", "13-emerald", "14-nebula");
        assertThat(builtIn).extracting(DiceSet::name).containsExactly("Onyx / white", "Ivory / black",
                "Crimson / gold", "Vortex blue / white", "Jade marble / gold", "Speckled stone / black",
                "Pearl violet / silver", "Steel / black", "Polished gold / black", "Gunmetal / gold",
                "Smoke / white", "Ruby / gold", "Emerald / white", "Nebula / white");
        assertThat(builtIn.subList(0, 10)).noneMatch(DiceSet::translucent);
        assertThat(builtIn.subList(10, 14)).allMatch(DiceSet::translucent);
    }

    @Test
    void theTranslucentBuiltInsAreThePrototypesFilesVerbatim() throws Exception {
        // Known values from 2f808fe:prototypes/materials/dicesets/11-14.
        DiceSetCatalogue builtIn = DiceSetCatalogue.builtIn();
        assertThat(builtIn.find("12-ruby").orElseThrow().transmission())
                .isEqualTo(new DiceSet.Transmission(1, new SrgbColour(0xd0, 0x14, 0x2c), 1.58, 0));
        assertThat(builtIn.find("13-emerald").orElseThrow().transmission().cloudiness()).isEqualTo(0.12);
        DiceSet nebula = builtIn.find("14-nebula").orElseThrow();
        assertThat(nebula.hasSwirl()).isTrue();
        assertThat(nebula.flakes()).isEqualTo(new DiceSet.Flakes(new SrgbColour(0xe8, 0xec, 0xff), 0.18, 0.3, 1));

        try (InputStream in = DiceSetCatalogue.class.getResourceAsStream("11-smoke.diceset")) {
            assertThat(in).as("the built-in file is a resource of core").isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()).containsExactly(
                    "# Andy's Dice Dice Set. PROTOTYPE candidate; colours are sRGB hex.",
                    "# Translucent: transmission.color is what white light looks like after 10 mm inside the body.",
                    "format = 1",
                    "name = Smoke / white",
                    "base = #3a3a3c",
                    "roughness = 0.06",
                    "transmission = 1",
                    "transmission.color = #9c9ca0",
                    "ior = 1.54",
                    "ink = #f4f4f0");
        }
    }

    @Test
    void theBuiltInsAreTheMaterialsPrototypesFilesVerbatim() throws Exception {
        // Known values from the prototype's files (2f808fe:prototypes/materials/dicesets), one per look.
        DiceSetCatalogue builtIn = DiceSetCatalogue.builtIn();
        assertThat(builtIn.find("01-onyx").orElseThrow().base()).isEqualTo(new SrgbColour(0x12, 0x12, 0x14));
        assertThat(builtIn.find("04-vortex-blue").orElseThrow().swirl())
                .isEqualTo(new DiceSet.Swirl(new SrgbColour(0xe6, 0xe9, 0xf2), 11, 1.8, 3.5, 0.45, 0.14));
        assertThat(builtIn.find("06-speckled-stone").orElseThrow().flakes())
                .isEqualTo(new DiceSet.Flakes(new SrgbColour(0x1c, 0x1a, 0x18), 0.55, 0.35, 0));
        assertThat(builtIn.find("07-pearl-violet").orElseThrow().pearl()).isEqualTo(2.0);
        DiceSet steel = builtIn.find("08-steel").orElseThrow();
        assertThat(steel.metallic()).isEqualTo(1);
        assertThat(steel.roughness()).isEqualTo(0.28);

        // Shipped as the files themselves, comments and all, not regenerated from the values.
        try (InputStream in = DiceSetCatalogue.class.getResourceAsStream("03-crimson.diceset")) {
            assertThat(in).as("the built-in file is a resource of core").isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()).containsExactly(
                    "# Andy's Dice Dice Set. PROTOTYPE candidate; colours are sRGB hex.",
                    "# Metallic ink: a gold paint, so the ink takes the key light like metal does.",
                    "format = 1",
                    "name = Crimson / gold",
                    "base = #9a1422",
                    "roughness = 0.16",
                    "ink = #e2b45a",
                    "ink.metallic = 1",
                    "ink.roughness = 0.35");
        }
    }

    @Test
    void onyxIsTheDefault() {
        assertThat(DiceSetCatalogue.builtIn().fallback().identity()).isEqualTo("01-onyx");
    }

    @Test
    void choosingADiceSetFindsItByIdentity() {
        assertThat(DiceSetCatalogue.builtIn().choose("09-gold").name()).isEqualTo("Polished gold / black");
    }

    @Test
    void choosingAMissingDiceSetFallsBackToOnyx() {
        DiceSetCatalogue builtIn = DiceSetCatalogue.builtIn();

        assertThat(builtIn.choose("99-gone").identity()).isEqualTo("01-onyx");
        assertThat(builtIn.choose("").identity()).isEqualTo("01-onyx");
        assertThat(builtIn.choose("Polished gold / black").identity()).as("names are not identities")
                .isEqualTo("01-onyx");
        assertThat(builtIn.find("99-gone")).isEmpty();
    }

    @Test
    void aCatalogueNeedsDiceSetsWithDistinctIdentities() throws Exception {
        DiceSet a = DiceSetFile.parse("a", "format = 1\n");
        assertThatThrownBy(() -> new DiceSetCatalogue(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiceSetCatalogue(List.of(a, DiceSetFile.parse("a", "format = 1\npearl = 1\n"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a");
        assertThat(new DiceSetCatalogue(List.of(a)).fallback()).isEqualTo(a);
    }
}
