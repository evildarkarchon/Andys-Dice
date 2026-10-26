package io.github.evildarkarchon.andysdice.app;

import javafx.scene.Node;

/**
 * The Tray as the Roll UI mounts it: a node for Layout C's main area, and where the controller draws each Roll.
 * This is the seam ADR-0011 puts the stand-in tray behind. In the app it is the GL renderer's {@code FrameView},
 * fed through ADR-0002's frame transport by the render thread ({@link Skeleton}); in UI tests it is a stand-in that
 * draws nothing and reports Throw phases and rest on the injected playback clock, so no GL is needed.
 *
 * <p>It is the controller's Tray port ({@link RollController.TrayView}: showing and drawing a Roll, whether it is
 * still moving, and the look it is drawn in) plus the node Layout C lays out.
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
}
