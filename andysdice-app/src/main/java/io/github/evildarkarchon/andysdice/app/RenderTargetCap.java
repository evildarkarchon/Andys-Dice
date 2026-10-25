package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.render.FrameView;
import java.util.Objects;

/**
 * The render-target cap below the High Quality Preset (ADR-0014, spec §4): the Tray's render target follows the
 * view's physical pixels, but never past about 2.07 million of them (1920 × 1080), keeping the viewport's aspect
 * ratio; JavaFX then scales the smaller frame up to fill the view. Render scale, a renderer lever, applies after
 * the cap. Without it a maximised Tray on a 4K screen at 150% (about 4.6 Mpx) would blow the GPU budget twice
 * over.
 *
 * <p>The Settings tab's Quality Preset (High lifts the cap) arrives later; until then the cap always applies.
 * Pure functions; safe on any thread.
 */
final class RenderTargetCap {

    /** The most pixels a render target has below High: 1920 × 1080. */
    static final long MAX_PIXELS = 1920L * 1080;

    /**
     * A render target size in physical pixels.
     *
     * @param width the width, at least 1
     * @param height the height, at least 1
     */
    record Size(int width, int height) {

        /** Checks both sides are at least 1. */
        Size {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("a render target is at least 1 x 1, not " + width + " x " + height);
            }
        }
    }

    private RenderTargetCap() {}

    /**
     * Fits a wanted render target under the cap.
     *
     * @param width the view's width in physical pixels, at least 1
     * @param height the view's height in physical pixels, at least 1
     * @return the same size if it is under the cap; otherwise the largest whole-pixel size of the same aspect
     *     ratio (to the pixel) whose area is under it
     */
    static Size fit(int width, int height) {
        long pixels = (long) width * height;
        if (pixels <= MAX_PIXELS) {
            return new Size(width, height);
        }
        double scale = Math.sqrt(MAX_PIXELS / (double) pixels);
        int w = Math.max(1, (int) Math.floor(width * scale));
        int h = Math.max(1, (int) Math.floor(height * scale));
        // Flooring both sides keeps the area under the cap; this only guards rounding in the square root.
        while ((long) w * h > MAX_PIXELS) {
            if (w >= h) {
                w--;
            } else {
                h--;
            }
        }
        return new Size(w, h);
    }

    /**
     * Wraps a size listener (normally {@code RenderThread::resize}) so it is told the capped size of whatever a
     * {@link FrameView} reports. The view reports again whenever its layout size or its window's scale changes,
     * a move to a monitor with another scale included, so the cap follows it.
     *
     * @param target told each capped size
     * @return a listener to give {@link FrameView#setOnTargetSizeChanged}
     */
    static FrameView.SizeListener capping(FrameView.SizeListener target) {
        Objects.requireNonNull(target, "target");
        return (width, height) -> {
            Size capped = fit(width, height);
            target.sizeChanged(capped.width(), capped.height());
        };
    }
}
