package io.github.evildarkarchon.andysdice.render;

import javafx.beans.value.ObservableValue;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/**
 * Where the rendered Tray appears in the JavaFX scene: a region showing the render thread's frames through
 * the public-API {@code PixelBuffer} bridge (ADR-0002), stretched to fill it.
 *
 * <p>It reports its size in physical pixels (layout size times the window's render scale, which follows its
 * output scale, so Windows display scaling is honoured) to a {@link SizeListener}, normally
 * {@link RenderThread#resize}. Until a frame of the new size arrives the last frame is shown stretched, so a
 * resize never waits on the render thread.
 *
 * <p>Wiring:
 * <pre>{@code
 * FrameView view = new FrameView();
 * RenderThread renderer = RenderThread.start(view.transport(), scene);
 * view.setOnTargetSizeChanged(renderer::resize);
 * }</pre>
 *
 * <p>Thread contract: a JavaFX node, so used on the JavaFX Application Thread, except {@link #transport()},
 * which belongs to the render thread, and {@link #framesPresented()}, which any thread may read.
 */
public final class FrameView extends Region {

    /** Receives the render target size a {@link FrameView} needs. */
    @FunctionalInterface
    public interface SizeListener {
        /**
         * The view needs frames of this size.
         *
         * @param width the width in physical pixels, at least 1
         * @param height the height in physical pixels, at least 1
         */
        void sizeChanged(int width, int height);
    }

    private final ImageView imageView = new ImageView();
    private final FramePresenter presenter;
    private final PixelBufferTransport transport;
    private SizeListener sizeListener;
    private int targetWidth;
    private int targetHeight;

    /** Creates an empty view; it shows nothing until the first frame arrives. */
    public FrameView() {
        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        getChildren().add(imageView);
        PixelBufferTransport[] wired = new PixelBufferTransport[1];
        // The presenter calls back into the transport, which needs the presenter: tie the knot with a holder.
        presenter = new FramePresenter(imageView, () -> wired[0].bufferFreed());
        transport = new PixelBufferTransport(presenter);
        wired[0] = transport;

        // Physical size depends on the window's scale too, which changes when the window moves to a monitor with
        // another scale; relayout then, so the new size gets reported. The render scale is the one JavaFX draws the
        // scene at: it follows the output scale on such a move (Window.updateOutputScales sets it first), and
        // unlike the read-only output scale a headless UI test can set it.
        ObservableValue<Window> window = sceneProperty().flatMap(Scene::windowProperty);
        window.flatMap(Window::renderScaleXProperty).addListener((obs, was, now) -> requestLayout());
        window.flatMap(Window::renderScaleYProperty).addListener((obs, was, now) -> requestLayout());
    }

    /**
     * The render-thread end of this view's bridge, to pass to {@link RenderThread#start}.
     *
     * @return the transport that feeds this view
     */
    public FrameTransport transport() {
        return transport;
    }

    /**
     * Sets who is told the render target size this view needs; it is told the current size at once, if the
     * view has been laid out.
     *
     * @param listener the listener, or {@code null} for none
     */
    public void setOnTargetSizeChanged(SizeListener listener) {
        sizeListener = listener;
        if (listener != null && targetWidth > 0) {
            listener.sizeChanged(targetWidth, targetHeight);
        }
    }

    /**
     * The frame on screen.
     *
     * @return the image of the last presented frame, or {@code null} before the first
     */
    public Image image() {
        return imageView.getImage();
    }

    /**
     * How many frames have been put on screen, for tests and timing.
     *
     * @return the count since the view was made
     */
    public long framesPresented() {
        return presenter.framesPresented();
    }

    /**
     * Sets who is told each time a frame is put on screen, for timing: {@code --bench} times Enter to first motion
     * up to the first frame of the Throw shown here (ADR-0014).
     *
     * @param listener run on the JavaFX thread right after each frame is shown and counted in
     *     {@link #framesPresented()}; it must not block. {@code null} for none. It replaces any earlier listener.
     */
    public void setOnFramePresented(Runnable listener) {
        presenter.setOnPresented(listener);
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        imageView.relocate(0, 0);
        imageView.setFitWidth(w);
        imageView.setFitHeight(h);

        Window window = getScene() == null ? null : getScene().getWindow();
        double scaleX = window == null ? 1 : window.getRenderScaleX();
        double scaleY = window == null ? 1 : window.getRenderScaleY();
        int physicalWidth = Math.max(1, (int) Math.round(w * scaleX));
        int physicalHeight = Math.max(1, (int) Math.round(h * scaleY));
        if (w > 0 && h > 0 && (physicalWidth != targetWidth || physicalHeight != targetHeight)) {
            targetWidth = physicalWidth;
            targetHeight = physicalHeight;
            if (sizeListener != null) {
                sizeListener.sizeChanged(physicalWidth, physicalHeight);
            }
        }
    }
}
