package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import javafx.scene.paint.Color;

/**
 * WCAG 2.2 contrast between two colours (success criteria 1.4.3 and 1.4.11), which the Roll UI's palette is held
 * to (spec §3.2 "Accessibility"): relative luminance from linearised sRGB, and the ratio
 * {@code (L1 + 0.05) / (L2 + 0.05)} of the lighter to the darker. Opacity is ignored: every palette colour is
 * opaque.
 *
 * <p>Pure functions over {@link Color} values; safe on any thread, no toolkit needed.
 */
final class Contrast {

    /** What a colour pair draws, and so the AA minimum it must meet. */
    enum Kind {
        /** Text, the faded chips' Face Values included: 4.5:1. */
        TEXT(4.5),
        /** Icons, focus rings, outlines, the ↻ badge and the not-kept slash: 3:1. */
        GRAPHIC(3.0);

        private final double minimum;

        Kind(double minimum) {
            this.minimum = minimum;
        }

        /**
         * Returns the AA minimum ratio for this kind.
         *
         * @return the ratio, at least 1
         */
        double minimum() {
            return minimum;
        }
    }

    /**
     * A foreground drawn on a background somewhere in the UI, named for the test's messages.
     *
     * @param what where the pair is drawn, in a few words
     * @param foreground the text or graphic colour
     * @param background the colour it sits on
     * @param kind which minimum applies
     */
    record Pair(String what, Color foreground, Color background, Kind kind) {

        /** Checks every part is present. */
        Pair {
            Objects.requireNonNull(what, "what");
            Objects.requireNonNull(foreground, "foreground");
            Objects.requireNonNull(background, "background");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private Contrast() {}

    /**
     * Returns the contrast ratio of two colours, the same whichever is the foreground.
     *
     * @param a one colour
     * @param b the other
     * @return the ratio, from 1 (identical luminance) to 21 (black on white)
     */
    static double ratio(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * Returns a colour's WCAG relative luminance.
     *
     * @param colour an sRGB colour; its opacity is ignored
     * @return the luminance, from 0 (black) to 1 (white)
     */
    static double luminance(Color colour) {
        return 0.2126 * linear(colour.getRed()) + 0.7152 * linear(colour.getGreen())
                + 0.0722 * linear(colour.getBlue());
    }

    /** One sRGB channel in [0, 1], linearised as WCAG 2.2 defines it (its 0.04045 threshold). */
    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
