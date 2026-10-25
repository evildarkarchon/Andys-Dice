package io.github.evildarkarchon.andysdice.app;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * One Roll in the History tab's list (spec §3.2 "History tab"): its day header if it is the newest Roll of its day,
 * then a row of the local time ({@code HH:mm}, the full date and time on hover), the expression as written, the
 * Saved Roll's name at the time if any, and the total. The whole cell reads as one sentence to a screen reader.
 *
 * <p>The day headers live inside the first cell of each day rather than as list items of their own, so every item
 * of the list is a Roll: ↑/↓ never land on a header, and Space, Enter and the menu always have a Roll to act on.
 *
 * <p>A primary click copies the Roll into the box and a double-click rolls it again (its first click has copied it
 * already). A context-menu request selects the cell first, so the menu acts on the Roll that was right-clicked.
 *
 * <p>Thread contract: a JavaFX node, used on the JavaFX Application Thread.
 */
final class HistoryCell extends ListCell<PastRoll> {

    /** The style class every History cell carries. */
    static final String STYLE_CLASS = "history-cell";
    /** The widest a Saved Roll's name gets in a row, logical px; a longer one ends in an ellipsis. */
    static final double SAVED_NAME_MAX_WIDTH = 110;

    private final Clock clock;
    private final Label day = new Label();
    private final Label time = new Label();
    private final Label expression = new Label();
    private final Label savedName = new Label();
    private final Label total = new Label();
    private final Tooltip tooltip = new Tooltip();

    /**
     * Makes an empty cell.
     *
     * @param clock now, and the system time zone, which the day header and the time are read in
     * @param copy copies a Roll into the box (a click)
     * @param rollAgain rolls a Roll again (a double-click)
     */
    HistoryCell(Clock clock, Consumer<PastRoll> copy, Consumer<PastRoll> rollAgain) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(copy, "copy");
        Objects.requireNonNull(rollAgain, "rollAgain");
        getStyleClass().add(STYLE_CLASS);
        day.getStyleClass().add("history-day");
        // A header takes no room in the cells that don't carry one.
        day.managedProperty().bind(day.visibleProperty());
        time.getStyleClass().add("history-time");
        expression.getStyleClass().add("history-expression");
        savedName.getStyleClass().add("history-saved-name");
        savedName.managedProperty().bind(savedName.visibleProperty());
        total.getStyleClass().add("history-total");
        // The expression gives way first, then the name; the time and the total always show in full.
        expression.setMinWidth(0);
        expression.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(expression, Priority.ALWAYS);
        savedName.setMinWidth(0);
        // The expression matters more than the name: a long name is cut short before the expression is.
        savedName.setMaxWidth(SAVED_NAME_MAX_WIDTH);
        time.setMinWidth(USE_PREF_SIZE);
        total.setMinWidth(USE_PREF_SIZE);
        HBox row = new HBox(time, expression, savedName, total);
        row.getStyleClass().add("history-row");
        VBox content = new VBox(day, row);
        content.getStyleClass().add("history-cell-content");
        // A pref width of 0 lets the list fit cells to its width (no sideways scrolling), and the content then
        // follows the cell's width so the expression can take the spare room. The cell itself has no padding
        // (HistoryTab's stylesheet), so its content is exactly as wide as it.
        setPrefWidth(0);
        content.prefWidthProperty().bind(widthProperty());
        setGraphic(content);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        setOnMouseClicked(event -> {
            if (isEmpty() || event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (event.getClickCount() == 1) {
                copy.accept(getItem());
            } else if (event.getClickCount() == 2) {
                rollAgain.accept(getItem());
            }
        });
        addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            if (!isEmpty()) {
                getListView().getSelectionModel().select(getIndex());
            }
        });
    }

    @Override
    protected void updateItem(PastRoll roll, boolean empty) {
        super.updateItem(roll, empty);
        setText(null);
        if (empty || roll == null) {
            getGraphic().setVisible(false);
            setTooltip(null);
            setAccessibleText(null);
            return;
        }
        getGraphic().setVisible(true);
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        LocalDate rolledOn = HistoryDates.day(roll.at(), zone);
        String header = HistoryDates.dayHeader(rolledOn, today);
        // The list is newest first, so a Roll heads its day when the one above it fell on another day.
        List<PastRoll> items = getListView().getItems();
        int index = getIndex();
        day.setText(header);
        day.setVisible(index == 0 || index > items.size()
                || !HistoryDates.day(items.get(index - 1).at(), zone).equals(rolledOn));
        String at = HistoryDates.time(roll.at(), zone);
        time.setText(at);
        expression.setText(roll.expression());
        savedName.setText(roll.savedRollName().orElse(""));
        savedName.setVisible(roll.savedRollName().isPresent());
        // Integer.toString never groups digits and writes an ASCII minus, whatever the locale.
        String sum = Integer.toString(roll.total());
        total.setText(sum);
        tooltip.setText(HistoryDates.full(roll.at(), zone));
        setTooltip(tooltip);
        setAccessibleText(sentence(header, at, roll.expression(), sum, roll.savedRollName().orElse(null)));
    }

    /**
     * Returns how a screen reader reads a Roll's row: one sentence, its day included.
     *
     * @param day the Roll's day header ("Today", "Wed 21 Oct")
     * @param time its {@code HH:mm}
     * @param expression the expression as written
     * @param total the total as shown
     * @param savedRollName the Saved Roll's name, or {@code null}
     * @return for example "Today at 14:05, 4d6rr1kh3, total 14, from the Saved Roll Stats."
     */
    static String sentence(String day, String time, String expression, String total, String savedRollName) {
        return day + " at " + time + ", " + expression + ", total " + total
                + (savedRollName == null ? "" : ", from the Saved Roll " + savedRollName) + ".";
    }

    /**
     * Returns the day header, visible only in the newest Roll of its day.
     *
     * @return the label
     */
    Label day() {
        return day;
    }

    /**
     * Returns the local time.
     *
     * @return the {@code HH:mm} label
     */
    Label time() {
        return time;
    }

    /**
     * Returns the expression as written.
     *
     * @return the label
     */
    Label expression() {
        return expression;
    }

    /**
     * Returns the Saved Roll's name at the time, visible only if the Roll came from one.
     *
     * @return the label
     */
    Label savedName() {
        return savedName;
    }

    /**
     * Returns the total.
     *
     * @return the label
     */
    Label total() {
        return total;
    }

    /**
     * Returns the full date and time shown on hover.
     *
     * @return the tooltip's text, or empty for an empty cell
     */
    String hoverText() {
        return getTooltip() == null ? "" : getTooltip().getText();
    }
}
