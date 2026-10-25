package io.github.evildarkarchon.andysdice.render;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * One captured frame: opaque ARGB pixels, rows top-down (already flipped from GL's bottom-up order). Made by
 * {@link CaptureTransport} for the gallery and for tests.
 *
 * @param width the width in pixels
 * @param height the height in pixels
 * @param pixels {@code width × height} ARGB values, row by row from the top; not copied, so don't change it
 */
record Still(int width, int height, int[] pixels) {

    /** Checks that the pixel array matches the size. */
    Still {
        Objects.requireNonNull(pixels, "pixels");
        if (pixels.length != width * height) {
            throw new IllegalArgumentException(pixels.length + " pixels for " + width + "x" + height);
        }
    }

    /**
     * One pixel.
     *
     * @param x the column, 0 at the left
     * @param y the row, 0 at the top
     * @return its ARGB value
     */
    int argb(int x, int y) {
        Objects.checkIndex(x, width);
        Objects.checkIndex(y, height);
        return pixels[y * width + x];
    }

    /**
     * The still as an image, for writing to a file.
     *
     * @return a new {@code TYPE_INT_ARGB} image
     */
    BufferedImage toImage() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }
}
