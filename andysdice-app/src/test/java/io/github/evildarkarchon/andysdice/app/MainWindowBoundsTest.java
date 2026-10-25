package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

/**
 * The window's first-run bounds (spec §3.2 "Window", §4): 1280 × 800 logical px, shrunk to at most 90% of the
 * primary screen's usable area, centred on it, and never below the 960 × 600 minimum. Pure geometry: no toolkit.
 */
class MainWindowBoundsTest {

    @Test
    void aScreenWithRoomGetsTheDefaultSizeCentred() {
        Rectangle2D usable = new Rectangle2D(0, 0, 1920, 1040);

        assertThat(MainWindow.defaultBounds(usable)).isEqualTo(new Rectangle2D(320, 120, 1280, 800));
    }

    @Test
    void theDefaultIsShrunkToNinetyPercentOfASmallerScreen() {
        // 1366 × 728 usable (a 1366 × 768 laptop under a 40 px taskbar): 90% is 1229.4 × 655.2.
        Rectangle2D usable = new Rectangle2D(0, 0, 1366, 728);

        Rectangle2D bounds = MainWindow.defaultBounds(usable);

        assertThat(bounds.getWidth()).isEqualTo(1229.4);
        assertThat(bounds.getHeight()).isEqualTo(655.2);
        assertThat(bounds.getMinX()).isCloseTo(68.3, within(1e-9));
        assertThat(bounds.getMinY()).isCloseTo(36.4, within(1e-9));
    }

    @Test
    void justFittingIsNotEnough() {
        // 1280 × 800 would fit 1300 × 860 exactly, but not inside its 90% (1170 × 774).
        Rectangle2D bounds = MainWindow.defaultBounds(new Rectangle2D(0, 0, 1300, 860));

        assertThat(bounds.getWidth()).isEqualTo(1170);
        assertThat(bounds.getHeight()).isEqualTo(774);
    }

    @Test
    void itIsCentredOnTheUsableAreaWhereverThatIs() {
        // A primary screen whose usable area starts right of and below the origin (a taskbar at the top left).
        Rectangle2D usable = new Rectangle2D(60, 48, 2500, 1392);

        assertThat(MainWindow.defaultBounds(usable)).isEqualTo(new Rectangle2D(60 + 610, 48 + 296, 1280, 800));
    }

    @Test
    void theMinimumSizeWinsOverTheNinetyPercentRule() {
        // 90% of 1000 × 640 is 900 × 576, under the 960 × 600 minimum the stage enforces anyway.
        Rectangle2D bounds = MainWindow.defaultBounds(new Rectangle2D(0, 0, 1000, 640));

        assertThat(bounds).isEqualTo(new Rectangle2D(20, 20, 960, 600));
    }
}
