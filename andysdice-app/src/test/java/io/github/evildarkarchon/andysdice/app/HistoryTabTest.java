package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Modality;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The History tab (spec §3.2 "History tab", §3.6 "Roll History", #55): revealed Rolls join it and survive a restart,
 * listed newest first under day headers, at most 100 kept; a click copies a Roll into the box, a double-click (or
 * Enter) rolls it again, a right-click (or the Menu key, or Shift+F10) offers both; Clear all… asks first, Cancel the
 * default; and the empty state. Headless, over the real app (ADR-0011).
 */
class HistoryTabTest extends HeadlessUiTest {

    private static final String EMPTY_TEXT = "No Rolls yet.";

    @AfterEach
    void releaseKeys() throws Exception {
        // The headless keyboard's state may outlive this test's window (see FastForwardTest).
        onFx(() -> robot().keyRelease(KeyCode.SHIFT));
    }

    @Test
    void theFirstRunHasNoRollsAndNothingToClear() throws Exception {
        assertThat(fromFx(() -> ui().tabs().getSelectionModel().getSelectedItem())).isSameAs(ui().historyTab());
        assertThat(fromFx(() -> history.list().getItems())).isEmpty();
        assertThat(fromFx(() -> history.emptyText().getText())).isEqualTo(EMPTY_TEXT);
        assertThat(fromFx(() -> history.emptyText().isVisible())).isTrue();
        assertThat(fromFx(() -> history.count().getText())).isEqualTo("0 of 100 kept");
        assertThat(fromFx(() -> history.clearAll().getText())).isEqualTo("Clear all…");
        assertThat(fromFx(() -> history.clearAll().isDisabled())).isTrue();
    }

    @Test
    void aRollJoinsWhenItsResultIsRevealedAndNotBefore() throws Exception {
        faces.then(4);
        roll("1d6+2");
        advance(0.2);
        assertThat(fromFx(() -> history.list().getItems())).isEmpty();

        playToRest();

        List<PastRoll> rolls = fromFx(() -> List.copyOf(history.list().getItems()));
        assertThat(rolls).singleElement().satisfies(roll -> {
            assertThat(roll.expression()).isEqualTo("1d6+2");
            assertThat(roll.total()).isEqualTo(6);
            assertThat(roll.at()).isEqualTo(WALL_CLOCK_START);
            assertThat(roll.savedRollName()).isEmpty();
        });
        assertThat(fromFx(() -> history.count().getText())).isEqualTo("1 of 100 kept");
        assertThat(fromFx(() -> history.clearAll().isDisabled())).isFalse();
        assertThat(fromFx(() -> history.emptyText().isVisible())).isFalse();
        persistence.flush();
        assertThat(historyFile()).isEqualTo("#andysdice history.txt 1\n2026-10-25T12:00:00Z\t1d6+2\t\t4\n");
    }

    @Test
    void theExpressionIsKeptAsWritten() throws Exception {
        faces.then(3);
        roll("  1D6 + 2 ");
        playToRest();

        assertThat(fromFx(() -> history.list().getItems().getFirst().expression())).isEqualTo("1D6 + 2");
    }

    @Test
    void aFailedRollNeverJoins() throws Exception {
        simulator.then(24, Ending.TIMED_OUT);
        roll("2d6");
        playToRest();
        faces.thenRepeat(101, 1);
        roll("1d6rr1");
        playToRest();

        assertThat(fromFx(() -> controller().status())).isInstanceOf(RollStatus.Failed.class);
        assertThat(fromFx(() -> history.list().getItems())).isEmpty();
        assertThat(dataFolder.resolve(RollHistory.FILE_NAME)).doesNotExist();
    }

    @Test
    void theHistorySurvivesARestartButARollCutShortByClosingIsLost() throws Exception {
        faces.then(5);
        roll("1d8");
        playToRest();
        wallClock.advance(Duration.ofMinutes(1));
        roll("1d20");
        advance(0.2);

        restartApp();

        List<PastRoll> rolls = fromFx(() -> List.copyOf(history.list().getItems()));
        assertThat(rolls).extracting(PastRoll::expression).containsExactly("1d8");
        assertThat(rolls.getFirst().total()).isEqualTo(5);
        assertThat(fromFx(() -> history.count().getText())).isEqualTo("1 of 100 kept");
    }

    @Test
    void rowsAreNewestFirstUnderDayHeaders() throws Exception {
        // The wall clock reads Sunday 25 October 2026, 12:00 UTC.
        load(line("2025-10-21T09:00:00Z", "1d4", "", "3"),
                line("2026-10-21T18:30:00Z", "1d6", "", "2"),
                line("2026-10-24T23:59:59Z", "1d8", "", "7"),
                line("2026-10-25T00:00:00Z", "1d10", "", "9"),
                line("2026-10-25T11:59:00Z", "1d12", "", "11"));

        List<HistoryCell> cells = cells();

        assertThat(cells).extracting(cell -> cell.expression().getText())
                .containsExactly("1d12", "1d10", "1d8", "1d6", "1d4");
        assertThat(cells).extracting(HistoryTabTest::header)
                .containsExactly("Today", null, "Yesterday", "Wed 21 Oct", "Tue 21 Oct 2025");
    }

    @Test
    void dayHeadersAndTimesFollowTheSystemTimeZone() throws Exception {
        // 23:30 UTC on the 24th is 08:30 on the 25th in Tokyo: today there, not yesterday.
        wallClock = new SettableClock(WALL_CLOCK_START, ZoneId.of("Asia/Tokyo"));
        load(line("2026-10-24T23:30:00Z", "1d6", "", "2"));

        HistoryCell cell = cells().getFirst();

        assertThat(header(cell)).isEqualTo("Today");
        assertThat(fromFx(() -> cell.time().getText())).isEqualTo("08:30");
        assertThat(fromFx(() -> cell.hoverText())).isEqualTo("Sunday 25 October 2026, 08:30:00");
    }

    @Test
    void aRowShowsTimeExpressionTotalAndTheSavedRollsName() throws Exception {
        load(line("2026-10-25T09:05:33Z", "d% + 999", "Big one", "100"),
                line("2026-10-25T10:15:00Z", "1d4-5", "", "1"));

        List<HistoryCell> cells = cells();

        HistoryCell latest = cells.get(0);
        assertThat(fromFx(() -> latest.time().getText())).isEqualTo("10:15");
        assertThat(fromFx(() -> latest.expression().getText())).isEqualTo("1d4-5");
        assertThat(fromFx(() -> latest.total().getText())).isEqualTo("-4");
        assertThat(fromFx(() -> latest.savedName().isVisible())).isFalse();
        HistoryCell saved = cells.get(1);
        assertThat(fromFx(() -> saved.time().getText())).isEqualTo("09:05");
        assertThat(fromFx(() -> saved.expression().getText())).isEqualTo("d% + 999");
        // No grouping separator, whatever the locale.
        assertThat(fromFx(() -> saved.total().getText())).isEqualTo("1099");
        assertThat(fromFx(() -> saved.savedName().getText())).isEqualTo("Big one");
        assertThat(fromFx(() -> saved.savedName().isVisible())).isTrue();
        assertThat(fromFx(() -> saved.hoverText())).isEqualTo("Sunday 25 October 2026, 09:05:33");
    }

    @Test
    void eachRowReadsAsOneSentence() throws Exception {
        load(line("2026-10-21T09:05:00Z", "4d6rr1kh3", "Stats", "1>5,3,6,2"),
                line("2026-10-25T10:15:00Z", "1d4-5", "", "1"));

        List<HistoryCell> cells = cells();

        assertThat(fromFx(() -> cells.get(0).getAccessibleText())).isEqualTo("Today at 10:15, 1d4-5, total -4.");
        assertThat(fromFx(() -> cells.get(1).getAccessibleText()))
                .isEqualTo("Wed 21 Oct at 09:05, 4d6rr1kh3, total 14, from the Saved Roll Stats.");
        assertThat(fromFx(() -> history.list().getAccessibleText())).isEqualTo("Roll History");
    }

    @Test
    void aClickCopiesTheRollIntoTheBox() throws Exception {
        load(line("2026-10-25T10:15:00Z", "2d6+3", "", "1,6"));
        onFx(() -> ui().expressionBox().setText("1d20"));

        click(cells().getFirst(), 1);

        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("2d6+3");
        assertThat(fromFx(() -> controller().underWay())).isFalse();
    }

    @Test
    void aDoubleClickRollsItAgainAsANewRoll() throws Exception {
        load(line("2026-10-25T10:15:00Z", "2d6+3", "Hit", "1,6"));
        wallClock.advance(Duration.ofMinutes(5));
        faces.then(2, 2);

        HistoryCell cell = cells().getFirst();
        click(cell, 1);
        click(cell, 2);
        onFx(() -> {});
        playToRest();

        List<PastRoll> rolls = fromFx(() -> List.copyOf(history.list().getItems()));
        assertThat(rolls).extracting(PastRoll::expression).containsExactly("2d6+3", "2d6+3");
        assertThat(rolls.getFirst().total()).isEqualTo(7);
        assertThat(rolls.getFirst().at()).isEqualTo(Instant.parse("2026-10-25T12:05:00Z"));
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("2d6+3 = 7");
    }

    @Test
    void aDoubleClickWhileDiceMoveQueuesTheRoll() throws Exception {
        load(line("2026-10-25T10:15:00Z", "1d8", "", "1"));
        roll("3d6");
        advance(0.2);

        HistoryCell cell = cells().getFirst();
        click(cell, 1);
        click(cell, 2);

        assertThat(fromFx(() -> session.entry().queue().queued())).contains("1d8");
        assertThat(fromFx(() -> session.entry().nextChip().getText())).isEqualTo("next: 1d8");
    }

    @Test
    void aRightClickOffersRollAgainAndCopyIntoTheBox() throws Exception {
        load(line("2026-10-25T09:00:00Z", "1d4", "", "1"), line("2026-10-25T10:00:00Z", "1d12", "", "1"));
        HistoryCell older = cells().get(1);

        rightClick(older);

        assertThat(fromFx(() -> history.menu().isShowing())).isTrue();
        assertThat(fromFx(() -> history.menu().getItems())).extracting(MenuItem::getText)
                .containsExactly("Roll again", "Copy into the box");
        assertThat(fromFx(() -> history.list().getSelectionModel().getSelectedItem().expression()))
                .isEqualTo("1d4");

        onFx(() -> item("Copy into the box").fire());
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("1d4");

        faces.then(3);
        rightClick(older);
        onFx(() -> item("Roll again").fire());
        onFx(() -> {});
        playToRest();
        assertThat(fromFx(() -> history.list().getItems().getFirst().expression())).isEqualTo("1d4");
        assertThat(fromFx(() -> history.list().getItems())).hasSize(3);
    }

    @Test
    void saveAsASavedRollJoinsTheMenuOnceSomethingTakesIt() throws Exception {
        load(line("2026-10-25T09:00:00Z", "1d4+1", "", "1"));
        List<PastRoll> saved = new ArrayList<>();
        onFx(() -> history.setOnSaveAsSavedRoll(saved::add));

        rightClick(cells().getFirst());
        assertThat(fromFx(() -> history.menu().getItems())).extracting(MenuItem::getText)
                .containsExactly("Roll again", "Copy into the box", "Save as a Saved Roll…");
        onFx(() -> item("Save as a Saved Roll…").fire());

        assertThat(saved).extracting(PastRoll::expression).containsExactly("1d4+1");
    }

    @Test
    void listKeysMoveCopyRollAndOpenTheMenu() throws Exception {
        load(line("2026-10-25T09:00:00Z", "1d4", "", "1"), line("2026-10-25T10:00:00Z", "1d12", "", "1"));
        onFx(() -> {
            history.list().requestFocus();
            history.list().getSelectionModel().select(0);
        });

        onFx(() -> robot().keyType(KeyCode.DOWN));
        assertThat(fromFx(() -> history.list().getSelectionModel().getSelectedItem().expression()))
                .isEqualTo("1d4");
        onFx(() -> robot().keyType(KeyCode.UP));
        onFx(() -> robot().keyType(KeyCode.DOWN));

        onFx(() -> robot().keyType(KeyCode.SPACE));
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("1d4");
        // Copying leaves the focus in the list, so the keys keep working.
        assertThat(fromFx(() -> history.list().isFocused())).isTrue();

        onFx(() -> robot().keyType(KeyCode.CONTEXT_MENU));
        assertThat(fromFx(() -> history.menu().isShowing())).isTrue();
        onFx(() -> history.menu().hide());
        onFx(() -> {
            robot().keyPress(KeyCode.SHIFT);
            robot().keyType(KeyCode.F10);
            robot().keyRelease(KeyCode.SHIFT);
        });
        assertThat(fromFx(() -> history.menu().isShowing())).isTrue();
        onFx(() -> history.menu().hide());

        faces.then(2);
        onFx(() -> history.list().requestFocus());
        onFx(() -> robot().keyType(KeyCode.ENTER));
        onFx(() -> {});
        assertThat(fromFx(() -> controller().underWay())).isTrue();
        playToRest();
        assertThat(fromFx(() -> history.list().getItems().getFirst().expression())).isEqualTo("1d4");
    }

    @Test
    void clearAllAsksFirstWithCancelAsTheDefault() throws Exception {
        load(line("2026-10-25T09:00:00Z", "1d4", "", "1"));

        onFx(() -> history.clearAll().fire());

        Dialog<ButtonType> dialog = fromFx(() -> history.confirmation().orElseThrow());
        assertThat(fromFx(dialog::isShowing)).isTrue();
        assertThat(fromFx(dialog::getModality)).isEqualTo(Modality.APPLICATION_MODAL);
        assertThat(fromFx(() -> dialog.getOwner())).isSameAs(stage);
        Button cancel = fromFx(() -> button(dialog, ButtonBar.ButtonData.CANCEL_CLOSE));
        Button clear = fromFx(() -> button(dialog, ButtonBar.ButtonData.OK_DONE));
        assertThat(fromFx(cancel::isDefaultButton)).isTrue();
        assertThat(fromFx(cancel::isCancelButton)).isTrue();
        assertThat(fromFx(clear::isDefaultButton)).isFalse();
        assertThat(fromFx(clear::getText)).isEqualTo("Clear all");
        assertThat(fromFx(() -> dialog.getDialogPane().getStyleClass())).contains(Stylesheet.ROOT_CLASS);

        onFx(cancel::fire);

        assertThat(fromFx(dialog::isShowing)).isFalse();
        assertThat(fromFx(() -> history.list().getItems())).hasSize(1);
    }

    @Test
    void confirmingClearAllEmptiesTheHistoryAndItsFile() throws Exception {
        faces.then(6);
        roll("1d6");
        playToRest();

        onFx(() -> history.clearAll().fire());
        Dialog<ButtonType> dialog = fromFx(() -> history.confirmation().orElseThrow());
        onFx(() -> button(dialog, ButtonBar.ButtonData.OK_DONE).fire());

        assertThat(fromFx(dialog::isShowing)).isFalse();
        assertThat(fromFx(() -> history.list().getItems())).isEmpty();
        assertThat(fromFx(() -> history.emptyText().isVisible())).isTrue();
        assertThat(fromFx(() -> history.count().getText())).isEqualTo("0 of 100 kept");
        assertThat(fromFx(() -> history.clearAll().isDisabled())).isTrue();
        persistence.flush();
        assertThat(historyFile()).isEqualTo("#andysdice history.txt 1\n");
    }

    @Test
    void atMostOneHundredRollsAreKept() throws Exception {
        String[] lines = new String[100];
        for (int i = 0; i < 100; i++) {
            lines[i] = line(Instant.parse("2026-10-24T10:00:00Z").plusSeconds(i).toString(), "1d6", "", "1");
        }
        load(lines);
        assertThat(fromFx(() -> history.count().getText())).isEqualTo("100 of 100 kept");

        faces.then(6);
        roll("1d6+1");
        playToRest();

        List<PastRoll> rolls = fromFx(() -> List.copyOf(history.list().getItems()));
        assertThat(rolls).hasSize(100);
        assertThat(rolls.getFirst().expression()).isEqualTo("1d6+1");
        assertThat(rolls.getLast().at()).isEqualTo(Instant.parse("2026-10-24T10:00:01Z"));
        assertThat(fromFx(() -> history.count().getText())).isEqualTo("100 of 100 kept");
        persistence.flush();
        assertThat(historyFile().lines()).hasSize(101);
    }

    // --- helpers ---

    /** One {@code history.txt} line of facts. */
    private static String line(String at, String expression, String savedRollName, String chains) {
        return String.join("\t", at, expression, savedRollName, chains);
    }

    /** Writes {@code history.txt} as an earlier run left it, then restarts the app so it loads it. */
    private void load(String... lines) throws Exception {
        Files.writeString(dataFolder.resolve(RollHistory.FILE_NAME),
                "#andysdice history.txt 1\n" + String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        restartApp();
    }

    private String historyFile() throws IOException {
        Path file = dataFolder.resolve(RollHistory.FILE_NAME);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The list's filled cells after a pulse, in list order. */
    private List<HistoryCell> cells() throws Exception {
        pulse(stage.getScene());
        return fromFx(() -> history.list().lookupAll(".history-cell").stream()
                .map(HistoryCell.class::cast)
                .filter(cell -> !cell.isEmpty() && cell.isVisible())
                .sorted(Comparator.comparingInt(HistoryCell::getIndex))
                .toList());
    }

    /** A cell's day header, or {@code null} if it carries none (it isn't its day's first). */
    private static String header(HistoryCell cell) {
        try {
            return fromFx(() -> cell.day().isVisible() ? cell.day().getText() : null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Clicks a cell with the primary button, as the {@code count}th click of a run. */
    private static void click(HistoryCell cell, int count) throws Exception {
        onFx(() -> {
            Bounds screen = cell.localToScreen(cell.getBoundsInLocal());
            double x = cell.getWidth() / 2;
            double y = cell.getHeight() / 2;
            cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, x, y, screen.getMinX() + x,
                    screen.getMinY() + y, MouseButton.PRIMARY, count, false, false, false, false, false, false,
                    false, false, false, true, null));
        });
    }

    /** Asks for a cell's context menu, as a right-click does. */
    private static void rightClick(HistoryCell cell) throws Exception {
        onFx(() -> {
            Bounds screen = cell.localToScreen(cell.getBoundsInLocal());
            cell.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10,
                    screen.getMinX() + 10, screen.getMinY() + 10, false, null));
        });
    }

    /** The History menu's item with this text. Call it on the JavaFX thread. */
    private MenuItem item(String text) {
        return history.menu().getItems().stream().filter(item -> text.equals(item.getText())).findFirst()
                .orElseThrow();
    }

    /** The dialog's button of this kind. Call it on the JavaFX thread. */
    private static Button button(Dialog<ButtonType> dialog, ButtonBar.ButtonData data) {
        Optional<ButtonType> type = dialog.getDialogPane().getButtonTypes().stream()
                .filter(t -> t.getButtonData() == data).findFirst();
        return (Button) dialog.getDialogPane().lookupButton(type.orElseThrow());
    }
}
