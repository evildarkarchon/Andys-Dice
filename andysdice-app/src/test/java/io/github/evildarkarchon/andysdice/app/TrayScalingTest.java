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

    /**
     * The Tray view's logical size and the render target it last reported, read together in one JavaFX turn.
     *
     * @param width the view's logical width
     * @param height the view's logical height
     * @param target the last render target size reported, {@code {width, height}} in physical px
     */
    private record Seen(double width, double height, int[] target) {}

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
        });
        settle();
    }

    @AfterEach
    void close() throws Exception {
        onFx(stage::close);
    }

    @Test
    void atScaleOneTheRenderTargetIsTheTraysLogicalSize() throws Exception {
        Seen seen = seen();

        assertThat(seen.target()[0]).isEqualTo((int) Math.round(seen.width()));
        assertThat(seen.target()[1]).isEqualTo((int) Math.round(seen.height()));
        assertThat(seen.target()[0]).isEqualTo(1280 - 340);
    }

    @Test
    void aMoveToA150PercentMonitorScalesTheRenderTargetButNotTheLayout() throws Exception {
        Seen before = seen();
        double sidebar = fromFx(() -> ui.sidebar().getWidth());

        // What JavaFX does when the window lands on a monitor with another output scale: it sets the render scale.
        setRenderScale(1.5);

        // Logical sizes stay put, give or take JavaFX snapping edges to the new physical pixel grid (1/1.5 px each,
        // and the toolbar and result strip above and below the Tray each snap).
        Seen after = seen();
        assertThat(after.width()).isCloseTo(before.width(), within(2 / 1.5));
        assertThat(after.height()).isCloseTo(before.height(), within(2 / 1.5));
        assertThat(fromFx(() -> ui.sidebar().getWidth())).isEqualTo(sidebar).isEqualTo(340);
        // The render target is the Tray's logical pixels as laid out now, at 1.5 physical each.
        assertThat(after.target()[0]).isEqualTo((int) Math.round(after.width() * 1.5));
        assertThat(after.target()[1]).isEqualTo((int) Math.round(after.height() * 1.5));
        assertThat(after.target()[0]).isGreaterThan((int) before.width());
    }

    @Test
    void aBigScaleIsCappedKeepingTheTraysAspect() throws Exception {
        setRenderScale(3);

        Seen seen = seen();
        assertThat((long) seen.target()[0] * seen.target()[1]).isLessThanOrEqualTo(RenderTargetCap.MAX_PIXELS);
        RenderTargetCap.Size expected = RenderTargetCap.fit((int) Math.round(seen.width() * 3),
                (int) Math.round(seen.height() * 3));
        assertThat(seen.target()).containsExactly(expected.width(), expected.height());
        assertThat(seen.target()[0] / (double) seen.target()[1]).isCloseTo(seen.width() / seen.height(),
                within(0.01));
    }

    /** Sets the window's render scale on both axes, then lets the layout settle at it. */
    private void setRenderScale(double scale) throws Exception {
        onFx(() -> {
            stage.setRenderScaleX(scale);
            stage.setRenderScaleY(scale);
        });
        settle();
    }

    /**
     * Lays the window out now, then waits for a real pulse. A change of render scale takes more than one layout to
     * settle: the pulse after it runs CSS and layout again at the new scale, and the snapped insets of the toolbar
     * and result strip can move the Tray's height by a physical pixel (630.67 then 630 logical px at ×1.5). The
     * view reports each size it is laid out at, so a test must read its size and the last report after that
     * settling, in one turn; reading them in separate turns races the pulse.
     */
    private void settle() throws Exception {
        onFx(() -> {
            ui.applyCss();
            ui.layout();
        });
        HeadlessUiTest.pulse(stage.getScene());
    }

    /** The view's size and the last reported render target, read in one JavaFX turn so no pulse falls between. */
    private Seen seen() throws Exception {
        return fromFx(() -> new Seen(view.getWidth(), view.getHeight(), told.getLast()));
    }
}
