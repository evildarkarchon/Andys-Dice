package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import org.junit.jupiter.api.Test;

/**
 * The Layout C shell (spec §3.2 "Window", "Layout", "First run", "Glyphs"), on JavaFX's headless platform with the
 * real pipeline behind it (ADR-0011): the window, the sidebar and toolbar, the die strip, the Roll button, the
 * first-run state, the code-drawn icons and the palette the controls are actually drawn in.
 */
class RollUiShellTest extends HeadlessUiTest {

    @Test
    void theWindowIsTitledAndOpensAtTheDefaultSizeCentredWithTheMinimum() throws Exception {
        Rectangle2D expected = fromFx(() -> MainWindow.defaultBounds(Screen.getPrimary().getVisualBounds()));

        assertThat(fromFx(stage::getTitle)).isEqualTo("Andy's Dice");
        assertThat(fromFx(stage::isShowing)).isTrue();
        assertThat(fromFx(stage::getX)).isEqualTo(expected.getMinX());
        assertThat(fromFx(stage::getY)).isEqualTo(expected.getMinY());
        assertThat(fromFx(stage::getWidth)).isEqualTo(expected.getWidth());
        assertThat(fromFx(stage::getHeight)).isEqualTo(expected.getHeight());
        assertThat(fromFx(stage::getMinWidth)).isEqualTo(960);
        assertThat(fromFx(stage::getMinHeight)).isEqualTo(600);
        assertThat(fromFx(() -> stage.getScene().getRoot())).isSameAs(ui());
    }

    @Test
    void theTestRunsOnItsOwnTempDataFolder() {
        assertThat(DataFolder.choose().folder()).isEqualTo(dataFolder.toAbsolutePath());
    }

    @Test
    void firstRunShowsAnEmptyTrayTheHintAnEmptyFocusedBoxAndTheHistoryTab() throws Exception {
        assertThat(tray.phase()).isEqualTo(StandInTray.Phase.EMPTY);
        assertThat(tray.showing()).isNull();
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("Type a Roll Expression, or click a die above.");

        TextField box = ui().expressionBox();
        assertThat(fromFx(() -> box.getText())).isEmpty();
        assertThat(fromFx(box::getPromptText)).isEqualTo("e.g. 1d20+5");
        assertThat(fromFx(box::isFocused)).isTrue();
        assertThat(fromFx(() -> stage.getScene().getFocusOwner())).isSameAs(box);
        // Modena hides a focused field's prompt; the first-run box is focused and must still show it.
        assertThat(fromFx(() -> promptNode(box).isVisible())).isTrue();
        assertThat(fromFx(() -> promptNode(box).getFill())).isEqualTo(Palette.TEXT_MUTED);

        assertThat(fromFx(() -> ui().tabs().getTabs().stream().map(Tab::getText).toList()))
                .containsExactly("Saved", "History", "Look", "Settings");
        assertThat(fromFx(() -> ui().tabs().getSelectionModel().getSelectedItem())).isSameAs(ui().historyTab());
        assertThat(fromFx(() -> ui().tabs().getTabs().stream().noneMatch(Tab::isClosable))).isTrue();
    }

    @Test
    void theSidebarHeaderHoldsTheAppNameAndTheBellAboveTheTabs() throws Exception {
        assertThat(fromFx(() -> ui().appName().getText())).isEqualTo("Andy's Dice");
        Bounds name = fromFx(() -> sceneBounds(ui().appName()));
        Bounds bell = fromFx(() -> sceneBounds(ui().bell()));
        Bounds tabs = fromFx(() -> sceneBounds(ui().tabs()));
        Bounds sidebar = fromFx(() -> sceneBounds(ui().sidebar()));

        assertThat(sidebar.getMinX()).isZero();
        assertThat(sidebar.getWidth()).isEqualTo(RollUi.SIDEBAR_WIDTH).isEqualTo(340);
        assertThat(sidebar.getHeight()).isEqualTo(fromFx(() -> stage.getScene().getHeight()));
        assertThat(bell.getMinX()).isGreaterThan(name.getMaxX());
        assertThat(name.getMaxY()).isLessThanOrEqualTo(tabs.getMinY());
        assertThat(bell.getMaxY()).isLessThanOrEqualTo(tabs.getMinY());
        assertThat(tabs.getMaxY()).isEqualTo(sidebar.getMaxY());
    }

    @Test
    void theToolbarHoldsTheBoxTheDieStripAndTheRollButtonAboveTheTrayAndTheResultStrip() throws Exception {
        assertThat(fromFx(() -> ui().dieButtons().stream().map(Button::getText).toList()))
                .containsExactly("d4", "d6", "d8", "d10", "d12", "d20", "d%");
        assertThat(fromFx(() -> ui().rollButton().getText())).isEqualTo("Roll");

        List<Node> row = new ArrayList<>();
        row.add(ui().expressionBox());
        row.addAll(ui().dieButtons());
        row.add(ui().rollButton());
        List<Bounds> bounds = fromFx(() -> row.stream().map(RollUiShellTest::sceneBounds).toList());
        Bounds sidebar = fromFx(() -> sceneBounds(ui().sidebar()));
        Bounds trayArea = fromFx(() -> sceneBounds(tray));
        Bounds strip = fromFx(() -> sceneBounds(ui().resultStrip()));
        double sceneWidth = fromFx(() -> stage.getScene().getWidth());
        double sceneHeight = fromFx(() -> stage.getScene().getHeight());

        // Left to right in one row, right of the sidebar, and all above the Tray.
        for (int i = 0; i < bounds.size(); i++) {
            assertThat(bounds.get(i).getMinX()).isGreaterThanOrEqualTo(sidebar.getMaxX());
            assertThat(bounds.get(i).getMaxY()).isLessThanOrEqualTo(trayArea.getMinY());
            if (i > 0) {
                assertThat(bounds.get(i).getMinX()).isGreaterThan(bounds.get(i - 1).getMaxX());
            }
        }
        // The box takes the toolbar's spare width.
        assertThat(bounds.getFirst().getWidth()).isGreaterThan(bounds.get(1).getWidth() * 4);
        // The Tray fills the rest of the main area, with the result strip under it.
        assertThat(trayArea.getMinX()).isEqualTo(sidebar.getMaxX());
        assertThat(trayArea.getMaxX()).isEqualTo(sceneWidth);
        assertThat(strip.getMinY()).isEqualTo(trayArea.getMaxY());
        assertThat(strip.getMaxY()).isEqualTo(sceneHeight);
        assertThat(strip.getMinX()).isEqualTo(sidebar.getMaxX());
        assertThat(strip.getHeight()).isGreaterThanOrEqualTo(RollUi.RESULT_STRIP_HEIGHT);
    }

    @Test
    void theDieStripAppendsToTheBoxAndStartsAnEmptyBoxWithOneDie() throws Exception {
        onFx(() -> dieButton("d20").fire());
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("1d20");

        onFx(() -> dieButton("d6").fire());
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("1d20+1d6");

        // Each press leaves the caret at the end, with focus back in the box, so typing and Enter carry on.
        assertThat(fromFx(() -> ui().expressionBox().getCaretPosition())).isEqualTo("1d20+1d6".length());
        assertThat(fromFx(() -> ui().expressionBox().isFocused())).isTrue();
        assertThat(tray.shows()).isZero();
    }

    @Test
    void aDieStripButtonAppendsEvenWhenTheExpressionIsSelected() throws Exception {
        onFx(() -> {
            ui().expressionBox().setText("4d6rr1kh3");
            ui().expressionBox().selectAll();
        });

        onFx(() -> dieButton("d%").fire());

        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("4d6rr1kh3+1d%");
        assertThat(fromFx(() -> ui().expressionBox().getSelectedText())).isEmpty();
        assertThat(fromFx(() -> ui().expressionBox().getCaretPosition())).isEqualTo("4d6rr1kh3+1d%".length());
    }

    @Test
    void aDieStripButtonPressedByTheRobotAppends() throws Exception {
        Bounds d8 = fromFx(() -> ui().dieButtons().get(2).localToScreen(ui().dieButtons().get(2).getBoundsInLocal()));
        onFx(() -> {
            robot().mouseMove(d8.getCenterX(), d8.getCenterY());
            robot().mouseClick(javafx.scene.input.MouseButton.PRIMARY);
        });

        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("1d8");
        assertThat(fromFx(() -> ui().expressionBox().isFocused())).isTrue();
    }

    @Test
    void theRollButtonRollsJustLikeEnter() throws Exception {
        faces.then(4, 2);
        onFx(() -> ui().expressionBox().setText("1d6+2"));

        onFx(() -> ui().rollButton().fire());
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("Rolling…");
        assertThat(fromFx(() -> ui().expressionBox().isFocused())).isTrue();
        playToRest();
        String byButton = fromFx(() -> ui().resultStrip().text());

        roll("1d6+2");
        playToRest();
        String byEnter = fromFx(() -> ui().resultStrip().text());

        assertThat(byButton).isEqualTo("1d6+2 = 6");
        assertThat(byEnter).isEqualTo("1d6+2 = 4");
        assertThat(tray.shows()).isEqualTo(2);
        assertThat(tray.phase()).isEqualTo(StandInTray.Phase.AT_REST);
    }

    @Test
    void enterTypedThroughTheRobotRolls() throws Exception {
        faces.then(5);
        onFx(() -> {
            ui().expressionBox().setText("1d6");
            robot().keyType(javafx.scene.input.KeyCode.ENTER);
        });
        onFx(() -> {});
        playToRest();

        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("1d6 = 5");
    }

    @Test
    void everyIconIsACodeDrawnPathAndNoTextNeedsASymbolFont() throws Exception {
        assertThat(fromFx(() -> ui().bell().getGraphic())).isInstanceOf(SVGPath.class);
        assertThat(fromFx(() -> ui().bell().getText())).isEmpty();
        assertThat(fromFx(() -> ui().bell().getAccessibleText())).isEqualTo("Notices");

        List<String> texts = fromFx(() -> {
            List<String> all = new ArrayList<>();
            collectText(ui(), all);
            return all;
        });
        assertThat(texts).isNotEmpty();
        for (String text : texts) {
            assertThat(text.codePoints().filter(RollUiShellTest::needsASymbolFont).toArray())
                    .as("symbol characters in \"%s\"", text).isEmpty();
        }

        // Every icon the spec lists has path data that draws something, in the icon colour.
        for (SVGPath icon : fromFx(() -> List.of(Icons.bell(), Icons.reroll(), Icons.edit(), Icons.delete(),
                Icons.warning(), Icons.up(), Icons.down(), Icons.notKept()))) {
            assertThat(icon.getContent()).isNotBlank();
            Bounds drawn = fromFx(icon::getLayoutBounds);
            assertThat(drawn.getWidth()).as(icon.getContent()).isGreaterThan(8);
            assertThat(drawn.getHeight()).as(icon.getContent()).isGreaterThan(8);
            assertThat(icon.getStyleClass()).contains("icon");
        }
        assertThat(fromFx(() -> ((SVGPath) ui().bell().getGraphic()).getFill())).isEqualTo(Palette.ICON);
    }

    @Test
    void theControlsAreDrawnInThePaletteColours() throws Exception {
        assertThat(fromFx(() -> background(ui()))).isEqualTo(Palette.BACKGROUND);
        assertThat(fromFx(() -> background(ui().sidebar()))).isEqualTo(Palette.SURFACE);
        assertThat(fromFx(() -> background(ui().toolbar()))).isEqualTo(Palette.SURFACE);
        assertThat(fromFx(() -> background(ui().resultStrip()))).isEqualTo(Palette.SURFACE);
        assertThat(fromFx(() -> ui().appName().getTextFill())).isEqualTo(Palette.TEXT);
        assertThat(fromFx(() -> ui().resultStrip().message().getTextFill())).isEqualTo(Palette.TEXT);
        assertThat(fromFx(() -> ui().rollButton().getTextFill())).isEqualTo(Palette.ON_ACCENT);
        assertThat(fromFx(() -> dieButton("d6").getTextFill())).isEqualTo(Palette.TEXT);

        onFx(() -> {
            ui().expressionBox().setText("2d6+3");
            ui().applyCss();
            ui().layout();
        });
        assertThat(fromFx(() -> textNode(ui().expressionBox(), "2d6+3").getFill())).isEqualTo(Palette.TEXT);
    }

    @Test
    void everyControlHasAnAccessibleName() throws Exception {
        assertThat(fromFx(() -> ui().expressionBox().getAccessibleText())).isEqualTo("Roll Expression");
        assertThat(fromFx(() -> ui().rollButton().getAccessibleText())).isEqualTo("Roll");
        assertThat(fromFx(() -> dieButton("d%").getAccessibleText())).isEqualTo("Add a d%");
        assertThat(fromFx(() -> dieButton("d20").getAccessibleText())).isEqualTo("Add a d20");
    }

    private Button dieButton(String die) {
        return ui().dieButtons().get(DieStrip.DICE.indexOf(die));
    }

    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getLayoutBounds());
    }

    /** The first background fill of a region, as the stylesheet set it. */
    private static Paint background(Region region) {
        region.applyCss();
        return region.getBackground().getFills().getFirst().getFill();
    }

    /** The prompt-text node a text field's skin draws (a {@code Text} holding the prompt). */
    private static Text promptNode(TextField field) {
        return textNode(field, field.getPromptText());
    }

    private static Text textNode(Parent parent, String text) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Text t && text.equals(t.getText())) {
                return t;
            }
            if (child instanceof Parent p) {
                Text found = textNode(p, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Every string the scene graph draws as text: Labeled controls, text fields and loose Text nodes. */
    private static void collectText(Parent parent, List<String> into) {
        if (parent instanceof Labeled labeled && !labeled.getText().isEmpty()) {
            into.add(labeled.getText());
        }
        if (parent instanceof TextField field) {
            into.add(field.getPromptText());
        }
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Text text && !text.getText().isEmpty()) {
                into.add(text.getText());
            }
            if (child instanceof Parent p) {
                collectText(p, into);
            }
        }
        if (parent instanceof javafx.scene.control.TabPane tabs) {
            tabs.getTabs().forEach(tab -> into.add(tab.getText()));
        }
    }

    /**
     * Whether a character would need an emoji or symbol font: arrows, technical, dingbats, geometric shapes,
     * miscellaneous symbols, emoji, and the variation selectors that ask for emoji presentation. Letters, digits,
     * punctuation and the ellipsis of "Rolling…" are plain text in the system font.
     */
    private static boolean needsASymbolFont(int c) {
        return (c >= 0x2190 && c <= 0x2BFF) || (c >= 0x1F000 && c <= 0x1FAFF) || (c >= 0xFE00 && c <= 0xFE0F);
    }
}
