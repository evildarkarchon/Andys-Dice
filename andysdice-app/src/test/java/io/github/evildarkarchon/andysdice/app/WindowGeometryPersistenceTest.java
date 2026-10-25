package io.github.evildarkarchon.andysdice.app;

import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.fromFx;
import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.onFx;
import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.pulse;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.geometry.Rectangle2D;
import javafx.scene.layout.Pane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window's geometry across launches (spec §3.6): its normal bounds and maximised flag are written to
 * {@code settings.properties} when it closes and restored when the next launch opens it, through the real
 * {@link MainWindow#open(Stage, RollUi, SettingsStore)} on JavaFX's headless platform (whose one screen is
 * 1000 × 1000). Each "launch" is a fresh {@link Persistence} and {@link SettingsStore} over the same temp folder.
 */
class WindowGeometryPersistenceTest {

    @TempDir
    Path folder;

    private final List<Notice> notices = new CopyOnWriteArrayList<>();
    private Persistence persistence;
    private Stage stage;
    private WindowGeometry.Tracker tracker;

    @BeforeAll
    static void startToolkit() {
        HeadlessUiTest.startToolkit();
    }

    @BeforeEach
    void launch() {
        persistence = new Persistence(folder, notices::add);
    }

    @AfterEach
    void quit() throws Exception {
        if (stage != null) {
            onFx(stage::close);
        }
        persistence.close();
    }

    @Test
    void theFirstRunOpensAtTheDefaultAndClosingWritesIt() throws Exception {
        open();
        Rectangle2D expected = fromFx(() -> MainWindow.defaultBounds(Screen.getPrimary().getVisualBounds()));
        assertThat(bounds()).isEqualTo(expected);

        closeAndRelaunch();

        assertThat(saved()).isEqualTo(new WindowGeometry(expected, false));
        assertThat(notices).isEmpty();
    }

    @Test
    void aMovedAndResizedWindowReopensWhereItWas() throws Exception {
        open();
        moveTo(new Rectangle2D(30, 40, 970, 610));

        closeAndRelaunch();
        open();

        assertThat(bounds()).isEqualTo(new Rectangle2D(30, 40, 970, 610));
        assertThat(fromFx(() -> stage.isMaximized())).isFalse();
    }

    @Test
    void aMaximisedWindowKeepsItsNormalBoundsAndReopensMaximised() throws Exception {
        open();
        moveTo(new Rectangle2D(30, 40, 970, 610));
        onFx(() -> stage.setMaximized(true));
        pulse(stage.getScene());

        closeAndRelaunch();
        assertThat(saved()).isEqualTo(new WindowGeometry(new Rectangle2D(30, 40, 970, 610), true));
        open();

        assertThat(fromFx(() -> stage.isMaximized())).isTrue();
        assertThat(fromFx(() -> tracker.normalBounds())).isEqualTo(new Rectangle2D(30, 40, 970, 610));
    }

    /** The monitor the window was last on has gone: the default size is centred on the primary screen. */
    @Test
    void savedBoundsThatMissEveryScreenRecentreTheDefaultSize() throws Exception {
        Files.writeString(folder.resolve(Settings.FILE_NAME),
                "#andysdice settings.properties 1\nwindow.bounds=-3000,200,1000,700\nwindow.maximised=false\n",
                StandardCharsets.UTF_8);

        open();

        assertThat(bounds()).isEqualTo(fromFx(() -> MainWindow.defaultBounds(Screen.getPrimary().getVisualBounds())));
    }

    @Test
    void badBoundsFallBackAloneWhileTheMaximisedFlagStillApplies() throws Exception {
        Files.writeString(folder.resolve(Settings.FILE_NAME),
                "#andysdice settings.properties 1\nwindow.bounds=wide\nwindow.maximised=true\n",
                StandardCharsets.UTF_8);

        open();

        assertThat(fromFx(() -> stage.isMaximized())).isTrue();
        assertThat(fromFx(() -> tracker.normalBounds()))
                .isEqualTo(fromFx(() -> MainWindow.defaultBounds(Screen.getPrimary().getVisualBounds())));
    }

    /** A quit that stops the toolkit without closing the window (the smoke test, the failure panel's Quit). */
    @Test
    void savingWithoutClosingWritesTheGeometryToo() throws Exception {
        open();
        moveTo(new Rectangle2D(10, 20, 980, 640));

        onFx(() -> tracker.save());
        persistence.flush();

        assertThat(Files.readString(folder.resolve(Settings.FILE_NAME)))
                .contains("window.bounds=10,20,980,640").contains("window.maximised=false");
    }

    /** Opens the window as the app does, over this launch's settings, and lets it lay out. */
    private void open() throws Exception {
        SettingsStore settings = new SettingsStore(persistence);
        onFx(() -> {
            stage = new Stage();
            tracker = MainWindow.open(stage, new RollUi(new Pane()), settings);
        });
        pulse(stage.getScene());
    }

    /** Moves and resizes the window as the Player would, and lets the change settle. */
    private void moveTo(Rectangle2D bounds) throws Exception {
        onFx(() -> {
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
        });
        pulse(stage.getScene());
    }

    /** Closes the window, waits for its write, and starts the next launch's persistence. */
    private void closeAndRelaunch() throws Exception {
        onFx(stage::close);
        stage = null;
        persistence.close();
        persistence = new Persistence(folder, notices::add);
    }

    private WindowGeometry saved() {
        return WindowGeometry.from(persistence.load(Settings.FILE));
    }

    private Rectangle2D bounds() throws Exception {
        return fromFx(() -> new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
    }
}
