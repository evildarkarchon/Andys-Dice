package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import javafx.geometry.Bounds;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextFieldSkin;
import javafx.scene.input.KeyCode;
import javafx.stage.PopupWindow;
import org.junit.jupiter.api.Test;

/**
 * Entry errors (spec §3.2 "Entry", "Errors"), headless over the real parser (ADR-0011): checked on Enter only, the
 * box selects the error's span, a red popup under the span's start shows the one message, typing dismisses it, and
 * nothing is rolled or queued. An accepted Roll leaves its expression in the box, all selected.
 */
class EntryErrorsTest extends HeadlessUiTest {

    /** The grammar reference's message for {@code 1d7} (its invalid example table). */
    private static final String NO_D7 = "`d7` is not a Die Kind; use d4, d6, d8, d10, d12, d20 or d%";

    @Test
    void anInvalidExpressionSelectsItsSpanShowsTheMessageAndRollsNothing() throws Exception {
        roll("2d6+1d7kh1");

        TextField box = ui().expressionBox();
        // The error's span is the `d7`, [5, 7).
        assertThat(fromFx(() -> box.getSelection().getStart())).isEqualTo(5);
        assertThat(fromFx(() -> box.getSelection().getEnd())).isEqualTo(7);
        assertThat(fromFx(() -> box.getSelectedText())).isEqualTo("d7");
        assertThat(fromFx(() -> box.getText())).isEqualTo("2d6+1d7kh1");
        assertThat(fromFx(() -> box.isFocused())).isTrue();
        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isTrue();
        assertThat(fromFx(() -> entry().errorPopup().message())).isEqualTo(NO_D7);

        // Nothing was rolled: no Roll under way, nothing simulated, the Tray and the strip as they were.
        assertThat(fromFx(() -> controller().underWay())).isFalse();
        assertThat(simulator.requests).isEmpty();
        assertThat(tray.shows()).isZero();
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo(new RollStatus.Ready().text());
    }

    @Test
    void thePopupIsRedWithItsMessageAndUsesTheRollUiStylesheet() throws Exception {
        roll("1d7");

        PopupWindow popup = onlyPopup();
        // The message label is the popup's content, in the popup's own scene, carrying the Roll UI's theme itself.
        assertThat(fromFx(() -> entry().errorPopup().label().getScene())).isSameAs(fromFx(popup::getScene));
        assertThat(fromFx(() -> entry().errorPopup().label().getStyleClass()))
                .contains(Stylesheet.ROOT_CLASS, "error-popup");
        assertThat(fromFx(() -> entry().errorPopup().label().getStylesheets())).contains(Stylesheet.uri());
        assertThat(fromFx(() -> entry().errorPopup().label().getTextFill())).isEqualTo(Palette.ON_ERROR);
        assertThat(fromFx(() -> entry().errorPopup().label().getBackground().getFills().getFirst().getFill()))
                .isEqualTo(Palette.ERROR);
        assertThat(fromFx(() -> entry().errorPopup().label().getText())).isEqualTo(NO_D7);
    }

    @Test
    void thePopupSitsUnderTheSpansStart() throws Exception {
        roll("2d6+1d7kh1");

        TextField box = ui().expressionBox();
        PopupWindow popup = onlyPopup();
        // The screen x of the span's first character (the `d` of `d7`), measured by the box's own skin.
        double startX = fromFx(() -> box.localToScreen(
                ((TextFieldSkin) box.getSkin()).getCharacterBounds(5).getMinX(), 0).getX());
        Bounds boxOnScreen = fromFx(() -> box.localToScreen(box.getLayoutBounds()));

        assertThat(fromFx(popup::getX)).isCloseTo(startX - SpanGeometry.POPUP_INSET, within(1.0));
        assertThat(fromFx(popup::getY)).isCloseTo(boxOnScreen.getMaxY() + SpanGeometry.POPUP_GAP, within(1.0));
        // Further right than a popup for an error at the start of the box would be.
        assertThat(fromFx(popup::getX)).isGreaterThan(boxOnScreen.getMinX() + 20);
    }

    @Test
    void aZeroWidthSpanPutsTheCaretAtItsPositionAndAnchorsThePopupThere() throws Exception {
        roll("1d6+");

        TextField box = ui().expressionBox();
        assertThat(fromFx(() -> box.getSelectedText())).isEmpty();
        assertThat(fromFx(() -> box.getCaretPosition())).isEqualTo(4);
        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isTrue();
        PopupWindow popup = onlyPopup();
        double endX = fromFx(() -> box.localToScreen(
                ((TextFieldSkin) box.getSkin()).getCharacterBounds(4).getMinX(), 0).getX());
        assertThat(fromFx(popup::getX)).isCloseTo(endX - SpanGeometry.POPUP_INSET, within(1.0));
    }

    @Test
    void typingDismissesThePopupAndErrorsAreNeverCheckedAsYouType() throws Exception {
        roll("2d6+1d7kh1");
        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isTrue();

        // Typing over the selected `d7` with "4" keeps the text invalid, but nothing is checked until Enter.
        onFx(() -> robot().keyType(KeyCode.DIGIT4));
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("2d6+14kh1");
        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isFalse();
        assertThat(fromFx(HeadlessUiTest::showingPopups)).isEmpty();

        onFx(() -> {
            ui().expressionBox().setText("1d");
            ui().expressionBox().appendText("+");
        });
        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isFalse();
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo(new RollStatus.Ready().text());
        assertThat(simulator.requests).isEmpty();
    }

    @Test
    void theRollButtonChecksTheExpressionToo() throws Exception {
        onFx(() -> {
            ui().expressionBox().setText("2d6+1d7kh1");
            ui().rollButton().fire();
        });

        assertThat(fromFx(() -> ui().expressionBox().getSelectedText())).isEqualTo("d7");
        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isTrue();
        assertThat(fromFx(() -> ui().expressionBox().isFocused())).isTrue();
        assertThat(fromFx(() -> controller().underWay())).isFalse();
    }

    @Test
    void afterAnAcceptedRollTheBoxKeepsTheExpressionAllSelected() throws Exception {
        faces.then(3);
        roll("1d6+2");

        TextField box = ui().expressionBox();
        assertThat(fromFx(() -> box.getText())).isEqualTo("1d6+2");
        assertThat(fromFx(() -> box.getSelectedText())).isEqualTo("1d6+2");
        assertThat(fromFx(() -> box.isFocused())).isTrue();
        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isFalse();
        playToRest();
        assertThat(fromFx(() -> box.getSelectedText())).isEqualTo("1d6+2");

        // Typing then replaces it.
        onFx(() -> robot().keyType(KeyCode.DIGIT2));
        assertThat(fromFx(() -> box.getText())).isEqualTo("2");
    }

    @Test
    void anAcceptedRollByTheRollButtonIsAllSelectedToo() throws Exception {
        onFx(() -> {
            ui().expressionBox().setText("1d20");
            ui().rollButton().fire();
        });

        assertThat(fromFx(() -> ui().expressionBox().getSelectedText())).isEqualTo("1d20");
        assertThat(fromFx(() -> ui().expressionBox().isFocused())).isTrue();
    }

    @Test
    void aSecondEnterOnTheSameErrorShowsItAgain() throws Exception {
        roll("1d7");
        onFx(() -> entry().errorPopup().hide());

        onFx(this::pressEnterInBox);

        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isTrue();
        assertThat(fromFx(HeadlessUiTest::showingPopups)).hasSize(1);
    }

    private RollEntry entry() {
        return session.entry();
    }

    /** The one popup window showing; fails unless exactly one is. */
    private static PopupWindow onlyPopup() throws Exception {
        List<PopupWindow> popups = fromFx(HeadlessUiTest::showingPopups);
        assertThat(popups).hasSize(1);
        return popups.getFirst();
    }
}
