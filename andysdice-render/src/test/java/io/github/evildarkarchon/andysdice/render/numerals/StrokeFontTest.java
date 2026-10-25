package io.github.evildarkarchon.andysdice.render.numerals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The procedural stroke font (ADR-0008), checked without GL: a Chessex-style face, bold (0.16 cap heights) and
 * condensed, a running-track 0, a hooked 7, a 1 with an upstroke, and a 6/9 told apart only by an underline.
 * Glyph space is y-down with the cap height spanning y = 0..1.
 */
class StrokeFontTest {

    private static final double EPS = 0.01;
    private static final StrokeFont FONT = StrokeFont.CHESSEX;

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"})
    void everyDigitSpansTheCapHeightInsideItsCondensedBox(String digit) {
        Rectangle2D b = FONT.text(digit, false).getBounds2D();

        // Within a few hundredths: the 6's hook starts just under the cap line (the 9's just over the baseline),
        // and the 7's slanted stem ends in a butt cap whose far corner pokes a little below it.
        assertThat(b.getMinY()).as("top").isCloseTo(0, within(0.045));
        assertThat(b.getMaxY()).as("baseline").isCloseTo(1, within(0.045));
        assertThat(b.getMinX()).isGreaterThanOrEqualTo(-EPS);
        // Condensed: no digit is wider than 0.56 cap heights (ADR-0008, Chessex style).
        assertThat(b.getMaxX()).isLessThanOrEqualTo(0.56 + EPS);
        assertThat(b.getWidth()).isGreaterThan(0.25);
    }

    @Test
    void strokesAreBoldAtSixteenHundredthsOfTheCapHeight() {
        // The 1's stem crosses the line y = 0.75 as one run exactly one stroke weight wide.
        double run = horizontalRun(FONT.text("1", false), 0.75);

        assertThat(run).isCloseTo(0.16, within(0.003));
    }

    @Test
    void theZeroIsARunningTrackWithStraightSides() {
        Area zero = FONT.text("0", false);
        double half = 0.16 / 2;

        assertThat(zero.contains(0.28, 0.5)).as("open counter").isFalse();
        // Straight sides: the left stroke's centre line stays at x = half weight from 0.35 down to 0.65.
        for (double y = 0.35; y <= 0.65; y += 0.05) {
            assertThat(zero.contains(half, y)).as("left side at %s", y).isTrue();
            assertThat(zero.contains(0.56 - half, y)).as("right side at %s", y).isTrue();
        }
    }

    @Test
    void theSevenHasAHookOnItsBar() {
        Area seven = FONT.text("7", false);

        // The hook drops from the left end of the bar.
        assertThat(seven.contains(0.08, 0.2)).isTrue();
        assertThat(seven.contains(0.08, 0.5)).as("the hook is short").isFalse();
    }

    @Test
    void theOneHasAnUpstroke() {
        Area one = FONT.text("1", false);
        Rectangle2D b = one.getBounds2D();

        // The flag reaches out to the left of the stem, below its top.
        assertThat(one.contains(b.getMinX() + 0.04, 0.3)).isTrue();
        assertThat(one.contains(b.getMinX() + 0.04, 0.7)).as("no foot").isFalse();
    }

    @Test
    void multiDigitNumeralsAreTrackedApart() {
        Rectangle2D twenty = FONT.text("20", false).getBounds2D();

        assertThat(twenty.getWidth()).isCloseTo(0.56 + 0.06 + 0.56, within(EPS));
        assertThat(FONT.text("20", false).contains(0.56 + 0.03, 0.5)).as("gap between glyphs").isFalse();
    }

    @Test
    void sixAndNineDifferOnlyByTheirUnderline() {
        Area plainSix = FONT.text("6", false);
        Area plainNine = FONT.text("9", false);
        Area underlinedSix = FONT.text("6", true);
        Area underlinedNine = FONT.text("9", true);

        // Without the mark, a 9 is a 6 turned upside down: the reason dice mark them.
        assertThat(sameShape(turned(plainSix), plainNine)).isTrue();
        // With it, they are no longer each other's turn.
        assertThat(sameShape(turned(underlinedSix), underlinedNine)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"6", "9"})
    void theUnderlineIsASeparateBarBelowTheBaseline(String digit) {
        Rectangle2D plain = FONT.text(digit, false).getBounds2D();
        Area marked = FONT.text(digit, true);
        Rectangle2D b = marked.getBounds2D();

        assertThat(b.getMaxY()).as("below the baseline").isGreaterThan(1 + 0.1);
        // A clear gap under the glyph, so the bar reads as a mark, not part of the digit.
        assertThat(horizontalRun(marked, 1.02)).isZero();
        // It runs under most of the glyph.
        assertThat(horizontalRun(marked, b.getMaxY() - 0.03)).isGreaterThan(0.75 * plain.getWidth());
    }

    @Test
    void onlyDigitsAreDrawn() {
        assertThatThrownBy(() -> FONT.text("1a", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FONT.text("", false)).isInstanceOf(IllegalArgumentException.class);
    }

    /** A glyph turned 180 degrees about the middle of a 0.56-wide glyph box. */
    private static Area turned(Area glyph) {
        Area out = new Area(glyph);
        out.transform(AffineTransform.getRotateInstance(Math.PI, 0.28, 0.5));
        return out;
    }

    /** Whether two outlines cover the same area, give or take the curves' flattening. */
    private static boolean sameShape(Area a, Area b) {
        Area xor = new Area(a);
        xor.exclusiveOr(b);
        return areaOf(xor) < 1e-3;
    }

    /** The area of a shape, by sampling a fine grid over its bounds. */
    private static double areaOf(Area a) {
        Rectangle2D b = a.getBounds2D();
        double step = 0.004;
        int inside = 0;
        for (double y = b.getMinY(); y < b.getMaxY(); y += step) {
            for (double x = b.getMinX(); x < b.getMaxX(); x += step) {
                if (a.contains(x, y)) {
                    inside++;
                }
            }
        }
        return inside * step * step;
    }

    /** How much of the horizontal line at {@code y} lies inside the outline. */
    private static double horizontalRun(Area a, double y) {
        double step = 0.0005;
        int inside = 0;
        for (double x = -0.2; x < 1.5; x += step) {
            if (a.contains(x, y)) {
                inside++;
            }
        }
        return inside * step;
    }
}
