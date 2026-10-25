package io.github.evildarkarchon.andysdice.core.tray;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A colour as a {@code .tray} file writes it: 8-bit sRGB, {@code #rrggbb}, as in a {@code .diceset} file (ADR-0009,
 * ADR-0010). Shaders light in linear RGB, so the linear values are a conversion away.
 *
 * @param red the red channel, 0..255
 * @param green the green channel, 0..255
 * @param blue the blue channel, 0..255
 */
public record TrayColour(int red, int green, int blue) {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    /**
     * Checks the channels.
     *
     * @throws IllegalArgumentException if a channel is outside 0..255
     */
    public TrayColour {
        if ((red | green | blue) < 0 || red > 255 || green > 255 || blue > 255) {
            throw new IllegalArgumentException("sRGB channels are 0..255: " + red + ", " + green + ", " + blue);
        }
    }

    /**
     * Reads {@code #rrggbb}, in either case.
     *
     * @param text the text, already trimmed
     * @return the colour, or empty if the text isn't exactly {@code #} and six hex digits
     */
    public static Optional<TrayColour> parse(String text) {
        if (!HEX.matcher(text).matches()) {
            return Optional.empty();
        }
        int rgb = Integer.parseInt(text.substring(1), 16);
        return Optional.of(new TrayColour((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff));
    }

    /**
     * Returns the red channel in linear light.
     *
     * @return 0..1
     */
    public double linearRed() {
        return linear(red);
    }

    /**
     * Returns the green channel in linear light.
     *
     * @return 0..1
     */
    public double linearGreen() {
        return linear(green);
    }

    /**
     * Returns the blue channel in linear light.
     *
     * @return 0..1
     */
    public double linearBlue() {
        return linear(blue);
    }

    /** The sRGB transfer function, inverted: the exact piecewise curve that {@code srgb.glsl} encodes. */
    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
