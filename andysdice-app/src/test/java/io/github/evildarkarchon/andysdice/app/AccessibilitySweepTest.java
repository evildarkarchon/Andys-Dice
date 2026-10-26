package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.sound.SinkException;
import io.github.evildarkarchon.andysdice.sound.SoundOutput;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.PopupWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The accessibility sweep over the whole window (spec §3.2 "Accessibility", "Keyboard", #62), with every tab filled as
 * start-up fills it: the Look tab, the Quality Preset and the Sound section as well as the base class's History and
 * Saved tabs.
 * <ul>
 *   <li>ADR-0011's check that every focusable node has non-empty accessible text, in every tab, row, popup, dialog
 *       and the failure panel;</li>
 *   <li>Esc cancelling the Queued Roll wherever focus is, when nothing nearer takes it;</li>
 *   <li>reduced motion making the UI's one transition (a click on the volume slider's track) instant;</li>
 *   <li>the UI's numbers and times fixed, whatever the system locale.</li>
 * </ul>
 */
class AccessibilitySweepTest extends HeadlessUiTest {

    private SettingsStore settings;
    private SoundOutput sound;
    private SoundSettings soundSettings;
    private Locale previousLocale;

    /** Fills the Look and Settings tabs as {@code AndysDice.start} does, with a sound output that finds no device. */
    @BeforeEach
    void attachEverything() throws Exception {
        if (previousLocale == null) {
            previousLocale = Locale.getDefault();
        }
        // Attached again after a restart: the last run's output goes first, as quitting would close it.
        if (sound != null) {
            sound.close();
        }
        onFx(() -> {
            settings = new SettingsStore(persistence);
            QualityPresetChooser.attach(ui(), settings, preset -> {});
            session.attachLook(LookCatalogue.load(dataFolder, session.notices()::post), settings);
            sound = Clatter.output(clock, new SplittableRandom(5), () -> {
                throw new SinkException("no device", null);
            }, session.notices());
            soundSettings = SoundSettings.attach(ui(), settings, sound);
        });
    }

    /** Stops the sound output and puts the locale and the keyboard back. */
    @AfterEach
    void detach() throws Exception {
        Locale.setDefault(previousLocale);
        onFx(() -> {
            robot().keyRelease(KeyCode.CONTROL);
            stage.getScene().getPreferences().setReducedMotion(null);
        });
        if (sound != null) {
            sound.close();
        }
    }

    @Test
    void everyFocusableNodeInEveryTabHasAccessibleText() throws Exception {
        Files.writeString(dataFolder.resolve("saved-rolls.txt"),
                "#andysdice saved-rolls.txt 1\nFireball\t8d6\nOld homebrew\t3d7+2\n");
        restartApp();
        attachEverything();
        faces.then(4);
        roll("1d6+2");
        playToRest();

        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Tab tab : fromFx(() -> List.copyOf(ui().tabs().getTabs()))) {
            onFx(() -> ui().tabs().getSelectionModel().select(tab));
            pulse(stage.getScene());
            checked += fromFx(() -> checkTree(stage.getScene().getRoot(), missing));
        }

        // A Saved Roll's inline editor.
        onFx(() -> ui().tabs().getSelectionModel().select(ui().savedTab()));
        pulse(stage.getScene());
        onFx(() -> saved.edit(0));
        pulse(stage.getScene());
        checked += fromFx(() -> checkTree(stage.getScene().getRoot(), missing));
        onFx(() -> saved.cancelEdit());

        assertThat(missing).as("focusable nodes with no accessible text").isEmpty();
        // The walk found the window's controls: the box, seven dice, Roll, the bell, the tabs and each tab's own.
        assertThat(checked).isGreaterThan(30);
    }

    @Test
    void everyFocusableNodeInThePopupsAndDialogsHasAccessibleText() throws Exception {
        Files.writeString(dataFolder.resolve("saved-rolls.txt"), "#andysdice saved-rolls.txt 1\nFireball\t8d6\n");
        restartApp();
        attachEverything();
        List<String> missing = new ArrayList<>();

        // The Notice list, with a Notice in it, and its Dismiss all.
        onFx(() -> session.notices().post("No audio device", "Rolls are silent."));
        onFx(() -> ui().bell().fire());
        assertThat(fromFx(() -> checkPopups(missing))).isPositive();
        onFx(() -> showingPopups().forEach(PopupWindow::hide));

        // + New Saved Roll.
        onFx(() -> saved.askNew("2d6"));
        assertThat(fromFx(() -> checkTree(saved.dialog().orElseThrow().dialog().getDialogPane(), missing)))
                .isPositive();
        onFx(() -> saved.dialog().orElseThrow().dialog().close());

        // The delete confirmation.
        onFx(() -> saved.askToDelete(0));
        assertThat(fromFx(() -> checkTree(saved.confirmation().orElseThrow().getDialogPane(), missing)))
                .isPositive();
        onFx(() -> saved.confirmation().orElseThrow().close());

        // The failure panel.
        onFx(() -> FailureScreen.show(ui(), Failure.startup(new IllegalStateException("broken"), null), () -> {}));
        assertThat(fromFx(() -> checkTree(FailureScreen.panelOf(ui()), missing))).isEqualTo(2);

        assertThat(missing).as("focusable nodes with no accessible text").isEmpty();
    }

    @Test
    void escCancelsTheQueuedRollWhereverFocusIsWhenNothingNearerTakesIt() throws Exception {
        faces.then(3);
        roll("1d6");
        playToRest();
        roll("1d6");
        List<String> kept = new ArrayList<>();
        for (Tab tab : fromFx(() -> List.copyOf(ui().tabs().getTabs()))) {
            onFx(() -> ui().tabs().getSelectionModel().select(tab));
            pulse(stage.getScene());
            for (Node node : fromFx(() -> focusTraversable(stage.getScene().getRoot()))) {
                onFx(() -> {
                    session.roll("1d8");
                    node.requestFocus();
                });
                onFx(() -> robot().keyType(KeyCode.ESCAPE));
                if (queuedRoll().isPresent()) {
                    kept.add(describe(node));
                }
            }
        }

        assertThat(kept).as("focus owners where Esc left the Queued Roll").isEmpty();
        // Esc never skipped the Roll under way.
        assertThat(fromFx(() -> controller().status())).isInstanceOf(RollStatus.Rolling.class);
    }

    @Test
    void withReducedMotionAClickOnTheVolumeTrackMovesTheThumbAtOnce() throws Exception {
        onFx(() -> {
            stage.getScene().getPreferences().setReducedMotion(true);
            ui().tabs().getSelectionModel().select(ui().settingsTab());
        });
        pulse(stage.getScene());
        Slider volume = soundSettings.volume();
        Node track = fromFx(() -> volume.lookup(".track"));
        Node thumb = fromFx(() -> volume.lookup(".thumb"));

        // The press is dispatched in this turn and the thumb read straight after, before any animation could have
        // run a frame: a glide would leave the thumb where it was.
        double[] shownAndLaidOut = fromFx(() -> {
            Bounds bounds = track.localToScene(track.getLayoutBounds());
            press(track, bounds.getMinX() + bounds.getWidth() * 0.9, bounds.getCenterY());
            double shown = thumb.getLayoutX();
            // Where layout puts the thumb for the new value: where it ends up once any glide is over.
            volume.requestLayout();
            volume.applyCss();
            volume.layout();
            return new double[] {shown, thumb.getLayoutX()};
        });

        assertThat(fromFx(volume::getValue)).isBetween(85.0, 95.0);
        assertThat(shownAndLaidOut[0]).isEqualTo(shownAndLaidOut[1]);
        assertThat(fromFx(volume::isFocused)).isTrue();
    }

    @Test
    void withoutReducedMotionAClickOnTheVolumeTrackStillSetsTheVolume() throws Exception {
        onFx(() -> {
            stage.getScene().getPreferences().setReducedMotion(false);
            ui().tabs().getSelectionModel().select(ui().settingsTab());
        });
        pulse(stage.getScene());
        Slider volume = soundSettings.volume();
        Node track = fromFx(() -> volume.lookup(".track"));

        onFx(() -> {
            Bounds bounds = track.localToScene(track.getLayoutBounds());
            press(track, bounds.getMinX() + bounds.getWidth() * 0.2, bounds.getCenterY());
        });

        assertThat(fromFx(volume::getValue)).isBetween(15.0, 25.0);
        assertThat(fromFx(() -> soundSettings.volumeText().getText())).isEqualTo(
                Math.round(fromFx(volume::getValue)) + "%");
    }

    /** A primary-button press and release on a node at a point in scene coordinates, dispatched at once. */
    private static void press(Node target, double sceneX, double sceneY) {
        Point2D screen = target.getScene().getRoot().localToScreen(sceneX, sceneY);
        for (EventType<MouseEvent> type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
            Event.fireEvent(target, new MouseEvent(type, sceneX, sceneY, screen.getX(), screen.getY(),
                    MouseButton.PRIMARY, 1, false, false, false, false, type == MouseEvent.MOUSE_PRESSED, false, false,
                    false, false, false, null));
        }
    }

    @Test
    void totalsHaveNoGroupingAndTimesAre24HourWhateverTheSystemLocale() throws Exception {
        Locale.setDefault(Locale.US);
        wallClock.set(java.time.Instant.parse("2026-10-25T15:07:00Z"));
        onFx(() -> ui().resultStrip().setClock(wallClock));
        faces.then(20);
        roll("1d20+999");
        playToRest();

        assertThat(fromFx(() -> ui().resultStrip().total().getText())).isEqualTo("1019");
        assertThat(fromFx(() -> ui().resultStrip().getAccessibleText())).startsWith("Total 1019.");
        assertThat(fromFx(() -> ui().resultStrip().details().getText())).contains("15:07");
        assertThat(fromFx(() -> history.list().getItems().getFirst().total())).isEqualTo(1019);
    }

    /**
     * Checks every focusable node under a root, as {@link #check} does, once CSS has given every control under it its
     * skin: a control's accessible text comes from its skin (a button's from its label), as it does for a screen
     * reader reading a window that has been drawn. Call it on the JavaFX thread.
     *
     * @return how many nodes were checked
     */
    private int checkTree(Node root, List<String> missing) {
        root.applyCss();
        return check(root, missing);
    }

    /**
     * Checks every focusable node under a root: focus-traversable, a button (the icon-only ones aren't Tab stops but
     * are clicked), or the result strip (focused by a click or Ctrl+R). Invisible subtrees are skipped: another tab's
     * content is checked with that tab open. Call it on the JavaFX thread.
     *
     * @return how many nodes were checked
     */
    private int check(Node root, List<String> missing) {
        int checked = 0;
        if (!root.isVisible()) {
            return 0;
        }
        if (root.isFocusTraversable() || root instanceof ButtonBase || root == ui().resultStrip()) {
            checked++;
            Object text = root.queryAccessibleAttribute(AccessibleAttribute.TEXT);
            if (!(text instanceof String s) || s.isBlank()) {
                missing.add(describe(root));
            }
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                checked += check(child, missing);
            }
        }
        return checked;
    }

    private int checkPopups(List<String> missing) {
        int checked = 0;
        for (PopupWindow popup : showingPopups()) {
            Scene scene = popup.getScene();
            checked += checkTree(scene.getRoot(), missing);
        }
        return checked;
    }

    /** Every visible, enabled, focus-traversable node under a root. Call it on the JavaFX thread. */
    private static List<Node> focusTraversable(Node root) {
        List<Node> found = new ArrayList<>();
        if (!root.isVisible() || root.isDisabled()) {
            return found;
        }
        if (root.isFocusTraversable()) {
            found.add(root);
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                found.addAll(focusTraversable(child));
            }
        }
        return found;
    }

    /** A node's class and style classes, then its ancestors', for a failure message. */
    private static String describe(Node node) {
        StringBuilder text = new StringBuilder();
        for (Node at = node; at != null; at = at.getParent()) {
            if (!text.isEmpty()) {
                text.append(" in ");
            }
            text.append(at.getClass().getSimpleName()).append(at.getStyleClass());
        }
        return text.toString();
    }
}
