package io.github.evildarkarchon.andysdice.app;

import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.fromFx;
import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.onFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.render.FrameView;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Display scaling (spec §3.2 "Window"): the UI is laid out in logical pixels, while the real Tray's render target
 * follows its physical pixels under the render-target cap, and follows a change of the window's scale, as when it
 * moves to a monitor with another one. The Tray here is the real {@link FrameView} inside Layout C, with no render
 * thread behind it: what it reports goes through the same {@link RenderTargetCap#capping} the app wires to the
 * renderer. No GL.
 */
class TrayScalingTest {

    private Stage stage;
    private RollUi ui;
    private FrameView view;
    private final List<int[]> told = new ArrayList<>();

    @BeforeAll
    static void startToolkit() {
        HeadlessUiTest.startToolkit();
    }

    @BeforeEach
    void open() throws Exception {
        onFx(() -> {
            view = new FrameView();
            ui = new RollUi(view);
            view.setOnTargetSizeChanged(RenderTargetCap.capping((w, h) -> told.add(new int[] {w, h})));
            stage = new Stage();
            stage.setScene(new Scene(ui, 1280, 800));
            stage.show();
            layOut();
        });
    }

    @AfterEach
    void close() throws Exception {
        onFx(stage::close);
    }

    @Test
    void atScaleOneTheRenderTargetIsTheTraysLogicalSize() throws Exception {
        int[] size = fromFx(() -> told.getLast());

        assertThat(size[0]).isEqualTo((int) Math.round(fromFx(view::getWidth)));
        assertThat(size[1]).isEqualTo((int) Math.round(fromFx(view::getHeight)));
        assertThat(size[0]).isEqualTo(1280 - 340);
    }

    @Test
    void aMoveToA150PercentMonitorScalesTheRenderTargetButNotTheLayout() throws Exception {
        double width = fromFx(view::getWidth);
        double height = fromFx(view::getHeight);
        double sidebar = fromFx(() -> ui.sidebar().getWidth());

        // What JavaFX does when the window lands on a monitor with another output scale: it sets the render scale.
        onFx(() -> {
            stage.setRenderScaleX(1.5);
            stage.setRenderScaleY(1.5);
            layOut();
        });

        // Logical sizes stay put, give or take JavaFX snapping edges to the new physical pixel grid (1/1.5 px).
        double scaledWidth = fromFx(view::getWidth);
        double scaledHeight = fromFx(view::getHeight);
        assertThat(scaledWidth).isCloseTo(width, within(1 / 1.5));
        assertThat(scaledHeight).isCloseTo(height, within(1 / 1.5));
        assertThat(fromFx(() -> ui.sidebar().getWidth())).isEqualTo(sidebar).isEqualTo(340);
        // The render target is those logical pixels at 1.5 physical each.
        int[] size = fromFx(() -> told.getLast());
        assertThat(size[0]).isEqualTo((int) Math.round(scaledWidth * 1.5));
        assertThat(size[1]).isEqualTo((int) Math.round(scaledHeight * 1.5));
        assertThat(size[0]).isGreaterThan((int) width);
    }

    @Test
    void aBigScaleIsCappedKeepingTheTraysAspect() throws Exception {
        onFx(() -> {
            stage.setRenderScaleX(3);
            stage.setRenderScaleY(3);
            layOut();
        });
        double width = fromFx(view::getWidth);
        double height = fromFx(view::getHeight);

        int[] size = fromFx(() -> told.getLast());
        assertThat((long) size[0] * size[1]).isLessThanOrEqualTo(RenderTargetCap.MAX_PIXELS);
        RenderTargetCap.Size expected = RenderTargetCap.fit((int) Math.round(width * 3), (int) Math.round(height * 3));
        assertThat(size).containsExactly(expected.width(), expected.height());
        assertThat(size[0] / (double) size[1]).isCloseTo(width / height,
                within(0.01));
    }

    /** Runs CSS and layout now, rather than waiting for the next pulse. */
    private void layOut() {
        ui.applyCss();
        ui.layout();
    }
}
