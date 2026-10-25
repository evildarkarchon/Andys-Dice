package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

/**
 * The UI contrast contract (spec §3.2 "Accessibility", §4): WCAG 2.2 AA on the one fixed dark theme, 4.5:1 for
 * text and 3:1 for graphics (icons, focus rings, control outlines), computed in pure Java from the palette
 * constants. The rendered Tray and Dice are outside it. No toolkit is started: {@link Color} is plain data.
 */
class ContrastTest {

    @Test
    void theRatioFollowsWcagRelativeLuminance() {
        // Worked values: black on white is the maximum, a colour on itself the minimum, and #767676 on white is
        // the well-known lightest grey that passes 4.5:1 (4.54:1).
        assertThat(Contrast.ratio(Color.BLACK, Color.WHITE)).isCloseTo(21, within(1e-9));
        assertThat(Contrast.ratio(Color.WHITE, Color.BLACK)).isCloseTo(21, within(1e-9));
        assertThat(Contrast.ratio(Color.web("#3a6ea5"), Color.web("#3a6ea5"))).isCloseTo(1, within(1e-9));
        assertThat(Contrast.ratio(Color.web("#767676"), Color.WHITE)).isCloseTo(4.54, within(0.005));
        // Pure red on white is 4.0:1, so it fails text and passes graphics.
        assertThat(Contrast.ratio(Color.RED, Color.WHITE)).isCloseTo(4.0, within(0.005));
    }

    @Test
    void theKindsCarryTheAaMinimums() {
        assertThat(Contrast.Kind.TEXT.minimum()).isEqualTo(4.5);
        assertThat(Contrast.Kind.GRAPHIC.minimum()).isEqualTo(3.0);
    }

    @Test
    void everyPairThePaletteDeclaresMeetsItsMinimum() {
        assertThat(Palette.PAIRS).isNotEmpty();
        for (Contrast.Pair pair : Palette.PAIRS) {
            assertThat(Contrast.ratio(pair.foreground(), pair.background()))
                    .as("%s (%s)", pair.what(), pair.kind())
                    .isGreaterThanOrEqualTo(pair.kind().minimum());
        }
    }

    @Test
    void thePaletteCoversTheTextAndGraphicsTheShellDraws() {
        // Text in both weights on every surface it sits on, and the graphics that must stand out.
        Color[] surfaces = {Palette.BACKGROUND, Palette.SURFACE, Palette.RAISED, Palette.FIELD};
        for (Color surface : surfaces) {
            assertThat(Contrast.ratio(Palette.TEXT, surface)).isGreaterThanOrEqualTo(4.5);
            assertThat(Contrast.ratio(Palette.TEXT_MUTED, surface)).isGreaterThanOrEqualTo(4.5);
            assertThat(Contrast.ratio(Palette.ICON, surface)).isGreaterThanOrEqualTo(3);
        }
        for (Color surface : new Color[] {Palette.BACKGROUND, Palette.SURFACE, Palette.FIELD}) {
            assertThat(Contrast.ratio(Palette.FOCUS, surface)).isGreaterThanOrEqualTo(3);
            assertThat(Contrast.ratio(Palette.OUTLINE, surface)).isGreaterThanOrEqualTo(3);
        }
        assertThat(Contrast.ratio(Palette.ON_ACCENT, Palette.ACCENT)).isGreaterThanOrEqualTo(4.5);
        assertThat(Contrast.ratio(Palette.ACCENT, Palette.SURFACE)).isGreaterThanOrEqualTo(3);
        assertThat(Contrast.ratio(Palette.ON_ERROR, Palette.ERROR)).isGreaterThanOrEqualTo(4.5);
    }

    @Test
    void theThemeIsDark() {
        // One fixed dark theme: light text on dark surfaces, whatever the system's scheme.
        assertThat(Contrast.luminance(Palette.TEXT)).isGreaterThan(Contrast.luminance(Palette.SURFACE));
        assertThat(Contrast.luminance(Palette.BACKGROUND)).isLessThan(0.05);
    }
}
