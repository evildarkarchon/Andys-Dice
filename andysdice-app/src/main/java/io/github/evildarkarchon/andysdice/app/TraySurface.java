package io.github.evildarkarchon.andysdice.app;

import javafx.scene.Node;

/**
 * The Tray as the Roll UI mounts it: a node for Layout C's main area, and where the controller draws each Roll.
 * This is the seam ADR-0011 puts the stand-in tray behind. In the app it is the GL renderer's {@code FrameView},
 * fed through ADR-0002's frame transport by the render thread ({@link Skeleton}); in UI tests it is a stand-in that
 * draws nothing and reports Throw phases and rest on the injected playback clock, so no GL is needed.
 *
 * <p>Thread contract: the JavaFX Application Thread, like the controller that drives it.
 */
interface TraySurface extends RollController.TrayView {

    /**
     * Returns what Layout C lays out where the Tray goes.
     *
     * @return the same node every time
     */
    Node node();

    /**
     * Tells whether the Tray still needs the playback clock after the Roll is over, so the pulse keeps running:
     * the GL Tray's camera settling in on the Dice at rest (#46), for one.
     *
     * @return {@code true} while something on the Tray is still moving on the clock; {@code false} by default
     */
    default boolean moving() {
        return false;
    }
}
