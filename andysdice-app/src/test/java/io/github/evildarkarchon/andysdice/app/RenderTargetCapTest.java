package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.render.FrameView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The render-target cap below High (ADR-0014, spec §4): the Tray's render target follows its physical pixels up
 * to about 2.07 Mpx (1920 × 1080), keeping the viewport's aspect ratio, and JavaFX scales the frame up. Pure.
 */
class RenderTargetCapTest {

    @Test
    void theCapIsFullHd() {
        assertThat(RenderTargetCap.MAX_PIXELS).isEqualTo(1920 * 1080);
    }

    @Test
    void aTargetUnderTheCapIsLeftAlone() {
        assertThat(RenderTargetCap.fit(940, 682)).isEqualTo(new RenderTargetCap.Size(940, 682));
        assertThat(RenderTargetCap.fit(1920, 1080)).isEqualTo(new RenderTargetCap.Size(1920, 1080));
        assertThat(RenderTargetCap.fit(1, 1)).isEqualTo(new RenderTargetCap.Size(1, 1));
    }

    @Test
    void aMaximisedTrayOnA4kScreenAt150PercentIsCappedKeepingItsAspect() {
        // The ADR's example: about 4.6 Mpx of Tray, here 3330 × 1380 physical px.
        RenderTargetCap.Size capped = RenderTargetCap.fit(3330, 1380);

        assertThat((long) capped.width() * capped.height()).isLessThanOrEqualTo(RenderTargetCap.MAX_PIXELS)
                .isGreaterThan(RenderTargetCap.MAX_PIXELS - 3330);
        assertThat(capped.width() / (double) capped.height()).isCloseTo(3330 / 1380.0,
                org.assertj.core.api.Assertions.within(0.002));
        // sqrt(2073600 / 4595400) = 0.67174…: 2236.89 × 927.0004, rounded down so the product stays under the cap.
        assertThat(capped).isEqualTo(new RenderTargetCap.Size(2236, 927));
    }

    @Test
    void a16By9TargetOverTheCapComesDownToFullHd() {
        assertThat(RenderTargetCap.fit(3840, 2160)).isEqualTo(new RenderTargetCap.Size(1920, 1080));
    }

    @Test
    void theCappingListenerPassesOnTheFittedSize() {
        List<String> told = new ArrayList<>();
        FrameView.SizeListener listener = RenderTargetCap.capping((w, h) -> told.add(w + "x" + h));

        listener.sizeChanged(800, 600);
        listener.sizeChanged(3840, 2160);

        assertThat(told).containsExactly("800x600", "1920x1080");
    }
}
