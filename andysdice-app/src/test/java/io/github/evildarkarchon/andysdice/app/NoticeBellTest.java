package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Notices and the bell (spec §3.2 "Notices and confirmations", "Keyboard", "Accessibility"), headless over the real
 * Roll UI (ADR-0011): posting from any thread, the bell's count and unread highlight, its list with Dismiss all, and
 * that nothing pops up or takes focus when a Notice arrives.
 */
class NoticeBellTest extends HeadlessUiTest {

    @Test
    void aNoticePostedFromAnotherThreadReachesTheBellOnTheFxThread() throws Exception {
        List<Boolean> changedOnFx = new CopyOnWriteArrayList<>();
        onFx(() -> notices().all().addListener((ListChangeListener<Notice>) change ->
                changedOnFx.add(Platform.isFxApplicationThread())));

        Thread poster = Thread.ofPlatform().start(() ->
                notices().post("No audio device", "The app will run silently."));
        poster.join();

        assertThat(await(() -> notices().all().size() == 1, Duration.ofSeconds(10))).isTrue();
        assertThat(changedOnFx).containsExactly(true);
        assertThat(fromFx(() -> notices().all().getFirst()))
                .isEqualTo(new Notice("No audio device", "The app will run silently."));
        assertThat(fromFx(() -> ui().bell().getText())).isEqualTo("1");
    }

    @Test
    void theBellCountsEveryNoticeAndIsHighlightedWhileAnyIsUnread() throws Exception {
        assertThat(fromFx(() -> ui().bell().getText())).isEmpty();
        assertThat(fromFx(() -> highlighted())).isFalse();
        assertThat(fromFx(() -> background(ui().bell()))).isNotEqualTo(Palette.NOTICE);

        onFx(() -> {
            notices().post("A Player File was skipped", "dice-sets/neon.json: not valid JSON.");
            notices().post("Settings were set aside", "settings.json was renamed settings.json.bad-20261025.");
        });

        assertThat(fromFx(() -> ui().bell().getText())).isEqualTo("2");
        assertThat(fromFx(() -> notices().unread())).isEqualTo(2);
        assertThat(fromFx(() -> highlighted())).isTrue();
        assertThat(fromFx(() -> background(ui().bell()))).isEqualTo(Palette.NOTICE);
        assertThat(fromFx(() -> ui().bell().getTextFill())).isEqualTo(Palette.ON_ACCENT);
        assertThat(fromFx(() -> ((SVGPath) ui().bell().getGraphic()).getFill())).isEqualTo(Palette.ON_ACCENT);

        // Seen in the list, they are read: the count stays until they are dismissed, the highlight goes.
        onFx(() -> ui().bell().fire());
        onFx(() -> list().hide());

        assertThat(fromFx(() -> ui().bell().getText())).isEqualTo("2");
        assertThat(fromFx(() -> notices().unread())).isZero();
        assertThat(fromFx(() -> highlighted())).isFalse();
        assertThat(fromFx(() -> ((SVGPath) ui().bell().getGraphic()).getFill())).isEqualTo(Palette.ICON);

        onFx(() -> notices().post("No audio device", "The app will run silently."));
        assertThat(fromFx(() -> ui().bell().getText())).isEqualTo("3");
        assertThat(fromFx(() -> notices().unread())).isEqualTo(1);
        assertThat(fromFx(() -> highlighted())).isTrue();
    }

    @Test
    void clickingTheBellListsTheNoticesAndDismissAllClearsThem() throws Exception {
        onFx(() -> {
            notices().post("A Player File was skipped", "dice-sets/neon.json: not valid JSON.");
            notices().post("No audio device", "");
        });
        Bounds bell = fromFx(() -> ui().bell().localToScreen(ui().bell().getBoundsInLocal()));

        onFx(() -> {
            robot().mouseMove(bell.getCenterX(), bell.getCenterY());
            robot().mouseClick(MouseButton.PRIMARY);
        });

        assertThat(fromFx(() -> showingPopups())).hasSize(1);
        assertThat(fromFx(() -> texts(list()))).containsExactly("Notices", "A Player File was skipped",
                "dice-sets/neon.json: not valid JSON.", "No audio device", "Dismiss all");
        // It hangs under the bell, in the theme.
        Bounds listBounds = fromFx(() -> listRoot().localToScreen(listRoot().getBoundsInLocal()));
        assertThat(listBounds.getMinY()).isGreaterThanOrEqualTo(bell.getMaxY());
        assertThat(listBounds.getMaxX()).isCloseTo(bell.getMaxX(), within(1.0));
        assertThat(fromFx(() -> listRoot().getStyleClass())).contains(Stylesheet.ROOT_CLASS);
        assertThat(fromFx(() -> background(listRoot()))).isEqualTo(Palette.OUTLINE);

        onFx(() -> dismissAll().fire());

        assertThat(fromFx(() -> notices().all())).isEmpty();
        assertThat(fromFx(() -> ui().bell().getText())).isEmpty();
        assertThat(fromFx(() -> highlighted())).isFalse();
        assertThat(fromFx(() -> showingPopups())).isEmpty();
        assertThat(fromFx(() -> stage.getScene().getFocusOwner())).isSameAs(ui().bell());

        // With none, the bell still opens, to say so.
        onFx(() -> ui().bell().fire());
        assertThat(fromFx(() -> texts(list()))).containsExactly("Notices", "No Notices.");
        onFx(() -> list().hide());
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode.class, names = {"SPACE", "ENTER"})
    void theBellOpensFromTheKeyboardAndDismissAllIsReachableThere(KeyCode key) throws Exception {
        onFx(() -> notices().post("No audio device", "The app will run silently."));
        onFx(() -> ui().bell().requestFocus());

        onFx(() -> robot().keyType(key));

        assertThat(fromFx(() -> showingPopups())).hasSize(1);
        assertThat(fromFx(() -> notices().unread())).isZero();
        // Exactly once: the key doesn't open it and then toggle it shut, or fire anything else.
        assertThat(fromFx(() -> stage.getScene().getFocusOwner())).isSameAs(ui().bell());
        // Keys now go to the list, where Dismiss all has focus.
        assertThat(fromFx(() -> dismissAll().isFocused())).isTrue();

        onFx(() -> robot().keyType(KeyCode.SPACE));

        assertThat(fromFx(() -> notices().all())).isEmpty();
        assertThat(fromFx(() -> showingPopups())).isEmpty();
        assertThat(fromFx(() -> stage.getScene().getFocusOwner())).isSameAs(ui().bell());
    }

    @Test
    void aNoticeArrivingWhileTheListIsOpenJoinsItReadAndKeepsTheListsFocus() throws Exception {
        onFx(() -> notices().post("No audio device", ""));
        onFx(() -> {
            ui().bell().requestFocus();
            ui().bell().fire();
        });

        Thread.ofPlatform().start(() -> notices().post("A slow GPU", "Try a lower Quality Preset.")).join();
        assertThat(await(() -> notices().all().size() == 2, Duration.ofSeconds(10))).isTrue();

        assertThat(fromFx(() -> texts(list()))).containsExactly("Notices", "No audio device", "A slow GPU",
                "Try a lower Quality Preset.", "Dismiss all");
        assertThat(fromFx(() -> notices().unread())).isZero();
        assertThat(fromFx(() -> highlighted())).isFalse();
        assertThat(fromFx(() -> dismissAll().isFocused())).isTrue();
        onFx(() -> list().hide());
    }

    @Test
    void escClosesTheListAndLeavesTheNoticesThere() throws Exception {
        onFx(() -> notices().post("No audio device", "The app will run silently."));
        onFx(() -> {
            ui().bell().requestFocus();
            ui().bell().fire();
        });
        assertThat(fromFx(() -> showingPopups())).hasSize(1);

        onFx(() -> robot().keyType(KeyCode.ESCAPE));

        assertThat(fromFx(() -> showingPopups())).isEmpty();
        assertThat(fromFx(() -> notices().all())).hasSize(1);
        assertThat(fromFx(() -> stage.getScene().getFocusOwner())).isSameAs(ui().bell());
    }

    @Test
    void theBellsAccessibleNameIncludesTheCount() throws Exception {
        assertThat(fromFx(() -> ui().bell().getAccessibleText())).isEqualTo("Notices, none");

        onFx(() -> notices().post("No audio device", ""));
        assertThat(fromFx(() -> ui().bell().getAccessibleText())).isEqualTo("Notices, 1, 1 unread");

        onFx(() -> {
            ui().bell().fire();
            list().hide();
        });
        assertThat(fromFx(() -> ui().bell().getAccessibleText())).isEqualTo("Notices, 1");

        onFx(() -> {
            notices().post("A Player File was skipped", "");
            notices().post("Settings were set aside", "");
        });
        assertThat(fromFx(() -> ui().bell().getAccessibleText())).isEqualTo("Notices, 3, 2 unread");
    }

    @Test
    void aNoticeArrivingPopsNothingUpAndLeavesFocusInTheBox() throws Exception {
        onFx(() -> {
            ui().expressionBox().setText("1d2");
            ui().expressionBox().positionCaret(3);
        });
        assertThat(fromFx(() -> ui().expressionBox().isFocused())).isTrue();

        Thread.ofPlatform().start(() -> notices().post("A slow GPU", "Try a lower Quality Preset.")).join();
        onFx(() -> notices().post("No audio device", ""));
        assertThat(await(() -> notices().all().size() == 2, Duration.ofSeconds(10))).isTrue();
        pulse(stage.getScene());

        assertThat(fromFx(() -> showingPopups())).isEmpty();
        assertThat(fromFx(() -> Window.getWindows().stream().filter(Window::isShowing).toList()))
                .containsExactly(stage);
        assertThat(fromFx(() -> stage.isFocused())).isTrue();
        assertThat(fromFx(() -> stage.getScene().getFocusOwner())).isSameAs(ui().expressionBox());
        // Typing carries on into the box.
        onFx(() -> robot().keyType(KeyCode.DIGIT0));
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("1d20");
    }

    private Button dismissAll() {
        return (Button) listRoot().lookup("." + NoticeBell.DISMISS_CLASS);
    }

    /** Every Labeled's text in a popup, in scene-graph order. */
    private static List<String> texts(PopupWindow popup) {
        popup.getScene().getRoot().applyCss();
        List<String> into = new ArrayList<>();
        collectText(popup.getScene().getRoot(), into);
        return into;
    }

    private static void collectText(Parent parent, List<String> into) {
        if (parent instanceof Labeled labeled && !labeled.getText().isEmpty()) {
            into.add(labeled.getText());
        }
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Parent p) {
                collectText(p, into);
            }
        }
    }

    private boolean highlighted() {
        return ui().bell().getPseudoClassStates().contains(NoticeBell.UNREAD);
    }

    /** The bell's list, which must be showing. */
    private PopupWindow list() {
        return showingPopups().getFirst();
    }

    /** The root of the bell's list: what the bell put in its popup. */
    private Region listRoot() {
        return (Region) ((Popup) list()).getContent().getFirst();
    }

    /** The first background fill of a region after CSS, or null with no background. */
    private static Paint background(Region region) {
        region.applyCss();
        Background background = region.getBackground();
        return background == null || background.isEmpty() ? null : background.getFills().getFirst().getFill();
    }

    private Notices notices() {
        return session.notices();
    }
}
