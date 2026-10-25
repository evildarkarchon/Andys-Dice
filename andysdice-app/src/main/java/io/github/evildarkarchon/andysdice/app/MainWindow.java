package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The app's one window (spec §3.2 "Window"): titled "Andy's Dice", opening at 1280 × 800 logical px, shrunk to
 * at most 90% of the primary screen's usable area and centred on it, with a 960 × 600 minimum (the 340 px sidebar
 * plus a Tray at least 620 px wide). Every size here is in logical pixels; Windows display scaling turns them into
 * physical ones, and only the Tray's render target works in physical pixels ({@link RenderTargetCap}).
 *
 * <p>The app opens it at the saved bounds and maximised flag (spec §3.6, {@link WindowGeometry}) when they still
 * meet a screen, and writes them back when it closes.
 */
final class MainWindow {

    /** The window title. */
    static final String TITLE = "Andy's Dice";
    /** The default width, logical px. */
    static final double DEFAULT_WIDTH = 1280;
    /** The default height, logical px. */
    static final double DEFAULT_HEIGHT = 800;
    /** The minimum width, logical px. */
    static final double MIN_WIDTH = 960;
    /** The minimum height, logical px. */
    static final double MIN_HEIGHT = 600;
    /** The most of the primary screen's usable area the default size may take, on each axis. */
    static final double SCREEN_SHARE = 0.9;

    private MainWindow() {}

    /**
     * Opens the window around the Roll UI as a first run sees it: titled, at the default bounds on the primary
     * screen with the minimum size set, shown, and with focus in the Roll Expression box.
     *
     * @param stage the window to open; JavaFX's primary stage in the app
     * @param ui the window content
     */
    static void open(Stage stage, RollUi ui) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(ui, "ui");
        show(stage, ui, defaultBounds(Screen.getPrimary().getVisualBounds()), false);
    }

    /**
     * Opens the window as {@link #open(Stage, RollUi)} does, but at the geometry saved in the settings (spec §3.6):
     * the saved normal bounds if they still meet a screen, else the default size centred on the primary screen,
     * and maximised if it was. The geometry is written back when the window closes.
     *
     * @param stage the window to open
     * @param ui the window content
     * @param settings the app's settings, holding the saved geometry and receiving it on close
     * @return what follows the window's geometry; call its {@link WindowGeometry.Tracker#save save} from
     *     {@code Application.stop} too, for a quit that doesn't close the window
     */
    static WindowGeometry.Tracker open(Stage stage, RollUi ui, SettingsStore settings) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(ui, "ui");
        WindowGeometry saved = WindowGeometry.from(settings.current());
        Rectangle2D bounds = WindowGeometry.placement(saved.normalBounds(),
                Screen.getScreens().stream().map(Screen::getVisualBounds).toList(),
                Screen.getPrimary().getVisualBounds());
        show(stage, ui, bounds, saved.maximised());
        return WindowGeometry.Tracker.follow(stage, bounds, settings);
    }

    /** Titles, sizes and shows the window, then puts focus in the box. */
    private static void show(Stage stage, RollUi ui, Rectangle2D bounds, boolean maximised) {
        Scene scene = new Scene(ui);
        // The theme's background before the first layout, so the window never flashes white.
        scene.setFill(Palette.BACKGROUND);
        stage.setTitle(TITLE);
        stage.setScene(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        // After the bounds, so they are the ones the window restores to from maximised. Only when it was: a first
        // run leaves the window's state alone.
        if (maximised) {
            stage.setMaximized(true);
        }
        stage.show();
        ui.focusBox();
    }

    /**
     * Returns the window's first-run bounds on a screen.
     *
     * @param usable the primary screen's usable area (its visual bounds, without the taskbar), logical px
     * @return the default size, each side cut to {@value #SCREEN_SHARE} of the usable area's but never below the
     *     minimum (which the stage enforces anyway), centred on the usable area
     */
    static Rectangle2D defaultBounds(Rectangle2D usable) {
        Objects.requireNonNull(usable, "usable");
        double width = Math.max(MIN_WIDTH, Math.min(DEFAULT_WIDTH, usable.getWidth() * SCREEN_SHARE));
        double height = Math.max(MIN_HEIGHT, Math.min(DEFAULT_HEIGHT, usable.getHeight() * SCREEN_SHARE));
        return new Rectangle2D(usable.getMinX() + (usable.getWidth() - width) / 2,
                usable.getMinY() + (usable.getHeight() - height) / 2, width, height);
    }
}
