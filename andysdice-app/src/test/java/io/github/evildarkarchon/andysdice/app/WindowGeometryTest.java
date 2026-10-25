package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

/**
 * The window's saved geometry (spec §3.6 "Settings"): its normal bounds and a maximised flag in
 * {@code settings.properties}, and where the window opens from them. Saved bounds that miss every current screen
 * give way to the default size centred on the primary screen. Pure: no toolkit.
 */
class WindowGeometryTest {

    /** A 1920 × 1080 primary screen under a 40 px taskbar. */
    private static final Rectangle2D PRIMARY = new Rectangle2D(0, 0, 1920, 1040);
    /** A second screen to the primary's left. */
    private static final Rectangle2D LEFT = new Rectangle2D(-1280, 0, 1280, 984);

    @Test
    void savedBoundsOnAScreenAreUsedAsTheyAre() {
        Rectangle2D saved = new Rectangle2D(100, 50, 1400, 900);

        assertThat(WindowGeometry.placement(saved, List.of(PRIMARY, LEFT), PRIMARY)).isEqualTo(saved);
    }

    @Test
    void savedBoundsOnTheOtherScreenAreUsedToo() {
        Rectangle2D saved = new Rectangle2D(-1200, 40, 1000, 700);

        assertThat(WindowGeometry.placement(saved, List.of(PRIMARY, LEFT), PRIMARY)).isEqualTo(saved);
    }

    @Test
    void boundsPartlyOnAScreenStillCount() {
        Rectangle2D saved = new Rectangle2D(1800, 900, 1280, 800);

        assertThat(WindowGeometry.placement(saved, List.of(PRIMARY), PRIMARY)).isEqualTo(saved);
    }

    /** The monitor the window was on has been unplugged. */
    @Test
    void savedBoundsThatMissEveryScreenRecentreTheDefaultSizeOnThePrimary() {
        Rectangle2D saved = new Rectangle2D(-1200, 40, 1000, 700);

        assertThat(WindowGeometry.placement(saved, List.of(PRIMARY), PRIMARY))
                .isEqualTo(new Rectangle2D(320, 120, 1280, 800));
    }

    @Test
    void touchingAScreenOnlyAtItsEdgeIsMissingIt() {
        Rectangle2D saved = new Rectangle2D(1920, 0, 1000, 700);

        assertThat(WindowGeometry.placement(saved, List.of(PRIMARY), PRIMARY))
                .isEqualTo(MainWindow.defaultBounds(PRIMARY));
    }

    @Test
    void noSavedBoundsMeansTheDefault() {
        assertThat(WindowGeometry.placement(null, List.of(PRIMARY, LEFT), PRIMARY))
                .isEqualTo(MainWindow.defaultBounds(PRIMARY));
    }

    @Test
    void theGeometryRoundTripsThroughTheSettings() {
        WindowGeometry geometry = new WindowGeometry(new Rectangle2D(68.3, -36.4, 1229.4, 655.2), true);

        Settings settings = geometry.into(Settings.defaults());

        assertThat(settings.values()).containsEntry("window.bounds", "68.3,-36.4,1229.4,655.2")
                .containsEntry("window.maximised", "true");
        assertThat(WindowGeometry.from(settings)).isEqualTo(geometry);
    }

    @Test
    void wholeNumbersAreWrittenWithoutADecimalPoint() {
        Settings settings = new WindowGeometry(new Rectangle2D(320, 120, 1280, 800), false).into(Settings.defaults());

        assertThat(settings.values()).containsEntry("window.bounds", "320,120,1280,800")
                .containsEntry("window.maximised", "false");
    }

    @Test
    void noSettingsMeansNoSavedBoundsAndNotMaximised() {
        assertThat(WindowGeometry.from(Settings.defaults())).isEqualTo(new WindowGeometry(null, false));
    }

    @Test
    void badBoundsFallBackToNoneAloneAndKeepTheFlag() {
        for (String bad : List.of("1,2,3", "1,2,3,4,5", "a,b,c,d", "0,0,0,800", "0,0,1280,-1", "NaN,0,1280,800",
                "0,0,Infinity,800", "")) {
            Settings settings = Settings.defaults().with(WindowGeometry.MAXIMISED, true)
                    .with(Setting.text("window.bounds", ""), bad.isEmpty() ? " " : bad);

            assertThat(WindowGeometry.from(settings)).as(bad).isEqualTo(new WindowGeometry(null, true));
        }
    }

    @Test
    void spacesAroundTheNumbersAreAllowed() {
        Settings settings = Settings.defaults().with(Setting.text("window.bounds", ""), " 10 , 20, 1000 ,700");

        assertThat(WindowGeometry.from(settings).normalBounds()).isEqualTo(new Rectangle2D(10, 20, 1000, 700));
    }
}
