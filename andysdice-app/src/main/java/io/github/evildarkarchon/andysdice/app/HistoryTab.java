package io.github.evildarkarchon.andysdice.app;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The History tab (spec §3.2 "History tab", §3.6 "Roll History"): the Roll History, newest first under day headers,
 * kept in {@code history.txt} so it survives a restart.
 * <ul>
 *   <li>A Roll joins when its Roll Result is revealed ({@link RollStatus.Rested}); a Failed Roll never reaches that
 *       status, and neither does a Roll still moving when the app closes, so neither ever joins.</li>
 *   <li>A click copies a Roll's expression into the box, and a double-click rolls it again, queuing if Dice move;
 *       the new Roll joins as a Roll of its own. A right-click offers Roll again and Copy into the box, and Save as a
 *       Saved Roll… once the Saved tab {@linkplain #setOnSaveAsSavedRoll takes it} (#56).</li>
 *   <li>Keys: ↑/↓ move the selection, Space copies, Enter rolls, the Menu key or Shift+F10 opens the menu.</li>
 *   <li>The foot reads "n of 100 kept" beside Clear all…, which asks a {@link ConfirmDialog modal confirmation}
 *       first (Cancel the default) and is disabled while the History is empty. Empty, the list reads
 *       {@value #EMPTY_TEXT}</li>
 * </ul>
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread. Each change is saved through
 * {@link Persistence}, which writes it on its own thread.
 */
final class HistoryTab {

    /** What the list reads with no Rolls in it. */
    static final String EMPTY_TEXT = "No Rolls yet.";

    private static final String URI = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(css().getBytes(StandardCharsets.UTF_8));

    private final RollUi ui;
    private final RollSession session;
    private final Persistence persistence;
    private final ListView<PastRoll> list = new ListView<>();
    private final Label emptyText = new Label(EMPTY_TEXT);
    private final Label count = new Label();
    private final Button clearAll = new Button("Clear all…");
    private final ContextMenu menu = new ContextMenu();
    private final MenuItem rollAgainItem = new MenuItem("Roll again");
    private final MenuItem copyItem = new MenuItem("Copy into the box");
    private final MenuItem saveAsItem = new MenuItem("Save as a Saved Roll…");
    private RollHistory rolls;
    private Consumer<PastRoll> onSaveAsSavedRoll;
    private Dialog<ButtonType> confirmation;

    private HistoryTab(RollSession session, Persistence persistence, Clock clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(clock, "clock");
        ui = session.ui();

        list.getStyleClass().add("history-list");
        list.setAccessibleText("Roll History");
        list.setCellFactory(view -> new HistoryCell(clock, this::copy, this::rollAgain));
        // The empty state is a label of our own over the list, not the list's placeholder: a placeholder is shown
        // and hidden through a wrapper of the skin's, so its own visibility never says whether it is showing.
        list.setPlaceholder(new Region());
        emptyText.getStyleClass().add("history-empty");
        emptyText.setMouseTransparent(true);
        emptyText.visibleProperty().bind(Bindings.isEmpty(list.getItems()));
        StackPane listArea = new StackPane(list, emptyText);
        StackPane.setAlignment(emptyText, Pos.TOP_CENTER);
        VBox.setVgrow(listArea, Priority.ALWAYS);

        count.getStyleClass().add("history-count");
        clearAll.getStyleClass().add("history-clear-all");
        clearAll.setAccessibleText("Clear all of the Roll History");
        clearAll.setOnAction(event -> askToClear());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox foot = new HBox(count, gap, clearAll);
        foot.getStyleClass().add("history-foot");

        VBox root = new VBox(listArea, foot);
        root.getStyleClass().add("history");
        root.getStylesheets().add(URI);
        ((StackPane) ui.historyTab().getContent()).getChildren().add(root);

        rollAgainItem.setOnAction(event -> selected().ifPresent(this::rollAgain));
        copyItem.setOnAction(event -> selected().ifPresent(this::copy));
        saveAsItem.setOnAction(event -> selected().ifPresent(roll -> {
            if (onSaveAsSavedRoll != null) {
                onSaveAsSavedRoll.accept(roll);
            }
        }));
        menu.getItems().setAll(rollAgainItem, copyItem);
        list.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            // A cell has already selected itself (HistoryCell); a request over no Roll offers nothing.
            if (event.isKeyboardTrigger()) {
                showMenuAtSelection();
            } else if (selected().isPresent()) {
                showMenu(event.getScreenX(), event.getScreenY());
            }
            event.consume();
        });
        // A filter, ahead of the list's own key handling (which would take Space for selection).
        list.addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
    }

    /**
     * Fills the History tab: loads {@code history.txt} (set aside, with a Notice, if it can't be read) and joins each
     * Roll the session reveals from now on.
     *
     * @param session the Roll UI and its controller; the tab goes in its History tab
     * @param persistence where {@code history.txt} is kept
     * @param clock now, for each revealed Roll's instant and for "Today", in the system time zone
     * @return the tab's content and actions
     */
    static HistoryTab install(RollSession session, Persistence persistence, Clock clock) {
        HistoryTab tab = new HistoryTab(session, persistence, clock);
        tab.show(persistence.load(RollHistory.FILE));
        // Once per Rested, at the moment its Roll Result is revealed; later snapshots of the same Roll at rest (its
        // Queued Roll changing, the camera settling in) don't join it again.
        session.controller().addStateListener(RollState.statusChanges(status -> {
            if (status instanceof RollStatus.Rested rested) {
                tab.join(PastRoll.of(clock.instant(), rested));
            }
        }));
        return tab;
    }

    /**
     * Sets what Save as a Saved Roll… does with the Roll it was asked over (the Saved tab's dialog, #56). Until
     * one is set the menu doesn't offer it.
     *
     * @param handler given the Roll; {@code null} takes the item off the menu again
     */
    void setOnSaveAsSavedRoll(Consumer<PastRoll> handler) {
        onSaveAsSavedRoll = handler;
        if (handler == null) {
            menu.getItems().remove(saveAsItem);
        } else if (!menu.getItems().contains(saveAsItem)) {
            menu.getItems().add(saveAsItem);
        }
    }

    /**
     * Copies a Roll's expression into the box, replacing what it held (spec §3.2 "Entry"). Focus stays where it
     * is, so the list's keys keep working.
     *
     * @param roll the Roll
     */
    void copy(PastRoll roll) {
        TextField box = ui.expressionBox();
        box.setText(roll.expression());
        box.positionCaret(box.getLength());
    }

    /**
     * Rolls a Roll's expression again, as a new Roll: at once, or queued while Dice move, as Enter in the box does.
     * The box then holds it, all selected, with focus, as after any accepted Roll (spec §3.2 "Entry", "Keyboard").
     * The new Roll is from the History, not from a Saved Roll, so it carries no Saved Roll's name.
     *
     * @param roll the Roll
     */
    void rollAgain(PastRoll roll) {
        copy(roll);
        // Every PastRoll's expression parses (checked when it was made), so it is handed on parsed.
        session.roll(roll.parsed());
        ui.focusBox();
        ui.expressionBox().selectAll();
    }

    /** The Roll the menu and the keys act on: the list's selection. */
    private Optional<PastRoll> selected() {
        return Optional.ofNullable(list.getSelectionModel().getSelectedItem());
    }

    private void keyPressed(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == KeyCode.SPACE && !event.isShortcutDown()) {
            selected().ifPresent(this::copy);
            event.consume();
        } else if (code == KeyCode.ENTER) {
            selected().ifPresent(this::rollAgain);
            event.consume();
        } else if (code == KeyCode.CONTEXT_MENU || (code == KeyCode.F10 && event.isShiftDown())) {
            // Windows also turns these keys into a keyboard-triggered context-menu request; showing it is
            // idempotent, so whichever arrives first opens the menu and the other changes nothing.
            showMenuAtSelection();
            event.consume();
        }
    }

    /** Opens the menu under the selected Roll's row, as the keyboard asks for it. */
    private void showMenuAtSelection() {
        if (selected().isEmpty() && !list.getItems().isEmpty()) {
            // The keys can reach a list with a focused row but no selection; the menu acts on that row.
            int focused = Math.max(0, list.getFocusModel().getFocusedIndex());
            list.getSelectionModel().select(focused);
        }
        if (selected().isEmpty()) {
            return;
        }
        int index = list.getSelectionModel().getSelectedIndex();
        Node anchor = list.lookupAll("." + HistoryCell.STYLE_CLASS).stream()
                .filter(node -> node instanceof HistoryCell cell && cell.getIndex() == index && cell.isVisible())
                .findFirst().orElse(list);
        if (menu.isShowing()) {
            return;
        }
        if (anchor == list) {
            menu.show(list, Side.TOP, 0, 0);
        } else {
            Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
            showMenu(bounds.getMinX() + 16, bounds.getMaxY());
        }
    }

    private void showMenu(double screenX, double screenY) {
        if (!menu.isShowing()) {
            menu.show(list, screenX, screenY);
        }
    }

    /** Asks before clearing every Roll; Cancel is the default. */
    private void askToClear() {
        if (rolls.rolls().isEmpty()) {
            return;
        }
        confirmation = ConfirmDialog.ask(ui.getScene().getWindow(), "Clear the Roll History",
                "Clear all " + rolls.rolls().size() + " Rolls from the History? This can't be undone.",
                "Clear all", () -> change(RollHistory.EMPTY));
    }

    /** A Roll was revealed: it joins as the newest, the oldest dropped past the cap. */
    private void join(PastRoll roll) {
        change(rolls.with(roll));
    }

    private void change(RollHistory next) {
        show(next);
        persistence.save(RollHistory.FILE, next);
    }

    private void show(RollHistory next) {
        rolls = next;
        list.getItems().setAll(next.newestFirst());
        // A day header depends on the Roll above it, so every visible cell is worked out again.
        list.refresh();
        count.setText(next.rolls().size() + " of " + RollHistory.CAP + " kept");
        clearAll.setDisable(next.rolls().isEmpty());
    }

    /**
     * Returns the Roll History as the tab holds it.
     *
     * @return the Rolls, oldest first
     */
    RollHistory history() {
        return rolls;
    }

    /**
     * Returns the list of Rolls.
     *
     * @return the list, newest first, each row a {@link HistoryCell}
     */
    ListView<PastRoll> list() {
        return list;
    }

    /**
     * Returns the empty state's text.
     *
     * @return the label shown over the list while it holds no Rolls
     */
    Label emptyText() {
        return emptyText;
    }

    /**
     * Returns the foot's count.
     *
     * @return the "n of 100 kept" label
     */
    Label count() {
        return count;
    }

    /**
     * Returns Clear all….
     *
     * @return the button, disabled while the History is empty
     */
    Button clearAll() {
        return clearAll;
    }

    /**
     * Returns the History's context menu.
     *
     * @return the menu a right-click, the Menu key or Shift+F10 opens
     */
    ContextMenu menu() {
        return menu;
    }

    /**
     * Returns Clear all…'s confirmation while it shows.
     *
     * @return the showing dialog, or empty
     */
    Optional<Dialog<ButtonType>> confirmation() {
        return Optional.ofNullable(confirmation).filter(Dialog::isShowing);
    }

    /** The tab's own rules, over the shared theme; its selectors out-rank the shared ones in either load order. */
    private static String css() {
        String surface = Stylesheet.hex(Palette.SURFACE);
        String raised = Stylesheet.hex(Palette.RAISED);
        String muted = Stylesheet.hex(Palette.TEXT_MUTED);
        String text = Stylesheet.hex(Palette.TEXT);
        String accent = Stylesheet.hex(Palette.ACCENT);
        String focus = Stylesheet.hex(Palette.FOCUS);
        String scope = "." + Stylesheet.ROOT_CLASS + " .history";
        return String.join("\n",
                scope + " .history-list { -fx-background-color: " + surface + "; -fx-background-insets: 0; "
                        + "-fx-padding: 0; }",
                scope + " .history-list:focused { -fx-background-color: " + focus + ", " + surface
                        + "; -fx-background-insets: 0, 1; }",
                scope + " .history-list .list-cell.history-cell { -fx-background-color: transparent; "
                        + "-fx-padding: 0; }",
                scope + " .history-cell .history-day { -fx-text-fill: " + muted + "; -fx-font-size: 12px; "
                        + "-fx-font-weight: bold; -fx-padding: 10 12 3 12; }",
                scope + " .history-cell .history-row { -fx-spacing: 10; -fx-padding: 5 12 5 12; "
                        + "-fx-alignment: center-left; }",
                // The selected row: raised, with an accent bar at its left edge, so the selection never rests on
                // colour alone; the focused row adds the focus ring.
                scope + " .history-cell:filled:hover .history-row { -fx-background-color: " + raised + "; }",
                scope + " .history-cell:filled:selected .history-row { -fx-background-color: " + accent + ", "
                        + raised + "; -fx-background-insets: 0, 0 0 0 3; }",
                scope + " .history-list:focused .history-cell:filled:focused .history-row { -fx-border-color: "
                        + focus + "; -fx-border-width: 1; }",
                scope + " .history-cell .history-time { -fx-text-fill: " + muted + "; }",
                scope + " .history-cell .history-expression { -fx-text-fill: " + text + "; }",
                scope + " .history-cell .history-saved-name { -fx-text-fill: " + muted + "; }",
                scope + " .history-cell .history-total { -fx-text-fill: " + text + "; -fx-font-weight: bold; }",
                scope + " .history-empty { -fx-text-fill: " + muted + "; -fx-padding: 16 12 0 12; }",
                scope + " .history-foot { -fx-padding: 8 10 10 12; -fx-spacing: 8; -fx-alignment: center-left; }",
                scope + " .history-count { -fx-text-fill: " + muted + "; }",
                "");
    }
}
