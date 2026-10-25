package io.github.evildarkarchon.andysdice.app;

import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * The window's saved geometry (spec §3.6 "Settings", ADR-0007): its normal bounds, the ones it has when neither
 * maximised nor minimised, and whether it was maximised. Both live in {@code settings.properties}, as
 * {@value #BOUNDS_KEY} ({@code x,y,width,height} in logical px) and {@value #MAXIMISED_KEY}; each falls back to its
 * own default alone. The window opens at them ({@link MainWindow#open(Stage, RollUi, SettingsStore)}) and writes
 * them once, when it closes ({@link Tracker}).
 *
 * @param normalBounds the normal bounds, logical px, or {@code null} if none are saved
 * @param maximised whether the window was maximised
 */
record WindowGeometry(Rectangle2D normalBounds, boolean maximised) {

    /** The key of the normal bounds. */
    static final String BOUNDS_KEY = "window.bounds";
    /** The key of the maximised flag. */
    static final String MAXIMISED_KEY = "window.maximised";

    /** The normal bounds; absent (no saved bounds) by default, and when the value is malformed or empty. */
    static final Setting<Rectangle2D> BOUNDS =
            new Setting<>(BOUNDS_KEY, null, WindowGeometry::parseBounds, WindowGeometry::formatBounds);
    /** The maximised flag; not maximised by default. */
    static final Setting<Boolean> MAXIMISED = Setting.flag(MAXIMISED_KEY, false);

    /**
     * Reads the geometry from the settings.
     *
     * @param settings the settings
     * @return the saved geometry, each part at its default if absent or bad
     */
    static WindowGeometry from(Settings settings) {
        return new WindowGeometry(settings.get(BOUNDS), settings.get(MAXIMISED));
    }

    /**
     * Writes this geometry into the settings.
     *
     * @param settings the settings to change
     * @return them with both keys set (the bounds removed if there are none)
     */
    Settings into(Settings settings) {
        return settings.with(BOUNDS, normalBounds).with(MAXIMISED, maximised);
    }

    /**
     * Returns where the window opens: at the saved bounds if they still meet a screen, else at the default size
     * centred on the primary screen ({@link MainWindow#defaultBounds}). "Meeting" a screen means overlapping its
     * usable area; touching it only along an edge is missing it.
     *
     * @param saved the saved normal bounds, or {@code null} if none
     * @param screens every current screen's usable area (visual bounds), logical px
     * @param primary the primary screen's usable area
     * @return the bounds to open at
     */
    static Rectangle2D placement(Rectangle2D saved, List<Rectangle2D> screens, Rectangle2D primary) {
        Objects.requireNonNull(screens, "screens");
        Objects.requireNonNull(primary, "primary");
        if (saved != null && screens.stream().anyMatch(screen -> overlap(saved, screen))) {
            return saved;
        }
        return MainWindow.defaultBounds(primary);
    }

    /** Whether two rectangles share some area (an edge alone isn't enough). */
    private static boolean overlap(Rectangle2D a, Rectangle2D b) {
        return a.getMinX() < b.getMaxX() && b.getMinX() < a.getMaxX()
                && a.getMinY() < b.getMaxY() && b.getMinY() < a.getMaxY();
    }

    /** Reads {@code x,y,width,height}: four finite numbers, the size positive; anything else is rejected. */
    private static Rectangle2D parseBounds(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 4) {
            return null;
        }
        double[] n = new double[4];
        for (int i = 0; i < 4; i++) {
            n[i] = Double.parseDouble(parts[i].strip());
            if (!Double.isFinite(n[i])) {
                return null;
            }
        }
        return n[2] > 0 && n[3] > 0 ? new Rectangle2D(n[0], n[1], n[2], n[3]) : null;
    }

    /** Writes {@code x,y,width,height}, whole numbers without a decimal point. */
    private static String formatBounds(Rectangle2D bounds) {
        return String.join(",", number(bounds.getMinX()), number(bounds.getMinY()), number(bounds.getWidth()),
                number(bounds.getHeight()));
    }

    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
                : Double.toString(value);
    }

    /**
     * Follows the open window's normal bounds and writes the geometry into the settings when it closes. Its
     * {@link #save} also runs from {@code Application.stop}, for a quit that doesn't close the window first; saving
     * twice writes once, since an unchanged setting isn't saved again.
     *
     * <p>The normal bounds are sampled a moment after each move or resize ({@code Platform.runLater}), not in the
     * listener itself, and only while the window is neither maximised nor minimised: maximising moves and resizes
     * the window in the same native event as it sets the flag, in an order that varies, so sampling at once could
     * record the maximised bounds as the normal ones.
     *
     * <p>Thread contract: made and used on the JavaFX Application Thread.
     */
    static final class Tracker {

        private final Stage stage;
        private final SettingsStore settings;
        private Rectangle2D normal;
        private boolean sampleQueued;

        private Tracker(Stage stage, Rectangle2D opened, SettingsStore settings) {
            this.stage = stage;
            this.settings = settings;
            this.normal = opened;
        }

        /**
         * Starts following a window just opened.
         *
         * @param stage the window
         * @param opened the normal bounds it was opened at
         * @param settings where the geometry is written on close
         * @return the tracker
         */
        static Tracker follow(Stage stage, Rectangle2D opened, SettingsStore settings) {
            Tracker tracker = new Tracker(Objects.requireNonNull(stage, "stage"), Objects.requireNonNull(opened,
                    "opened"), Objects.requireNonNull(settings, "settings"));
            ChangeListener<Number> moved = (property, was, now) -> tracker.queueSample();
            stage.xProperty().addListener(moved);
            stage.yProperty().addListener(moved);
            stage.widthProperty().addListener(moved);
            stage.heightProperty().addListener(moved);
            stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> tracker.save());
            return tracker;
        }

        /**
         * Returns the normal bounds as last seen.
         *
         * @return the window's bounds the last time it was neither maximised nor minimised
         */
        Rectangle2D normalBounds() {
            return normal;
        }

        /** Writes the normal bounds and the maximised flag into the settings, which saves them if they changed. */
        void save() {
            sample();
            settings.update(new WindowGeometry(normal, stage.isMaximized())::into);
        }

        private void queueSample() {
            if (!sampleQueued) {
                sampleQueued = true;
                Platform.runLater(() -> {
                    sampleQueued = false;
                    sample();
                });
            }
        }

        private void sample() {
            if (!stage.isMaximized() && !stage.isIconified() && stage.getWidth() > 0 && stage.getHeight() > 0) {
                normal = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            }
        }
    }
}
