package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.RollResult;
import io.github.evildarkarchon.andysdice.core.expression.TermResult;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The result strip under the Tray (spec §3.2 "Layout", "Failed Roll", "Screen readers"). It reads the first-run
 * hint before any Roll and "Rolling…" while Dice move. At rest it shows the Breakdown: the total, large; one
 * {@link TermBlock} per term exactly as written, with its subtotal and its Dice as {@link DieChip}s; and on the
 * right the expression and a facts line (the Saved Roll's name if any, the time, the number of Throws). A Failed
 * Roll's message, or why nothing was rolled, goes where the total would, and the facts keep the failed expression.
 *
 * <p>The strip is focusable (Ctrl+R moves focus to it, #62; a click focuses it), though not a Tab stop of its own,
 * so the spec's focus order is untouched. Its accessible text is what it says: the status line, or after a reveal
 * the spoken summary ({@link BreakdownText#spokenSummary}). {@link #text()} stays the strip's plain reading, the
 * status's own text, for tests.
 *
 * <p>Thread contract: a JavaFX node, used on the JavaFX Application Thread.
 */
final class ResultStrip extends StackPane {

    /** The facts line's time of a Roll: 24-hour, in the system time zone, fixed English (spec §3.2). */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK);

    private final Label message = new Label();
    private final Label total = new Label();
    private final FlowPane terms = new FlowPane(16, 6);
    private final Label expression = new Label();
    private final Label details = new Label();
    private final VBox facts = new VBox(2, expression, details);
    private final List<TermBlock> blocks = new ArrayList<>();
    private Clock clock = Clock.systemDefaultZone();
    private String plain = "";

    /** Creates an empty strip. */
    ResultStrip() {
        getStyleClass().add("result-strip");
        message.getStyleClass().add("result-message");
        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        total.getStyleClass().add("result-total");
        total.setMinWidth(USE_PREF_SIZE);
        terms.getStyleClass().add("result-terms");
        terms.setAlignment(Pos.CENTER_LEFT);
        terms.setRowValignment(javafx.geometry.VPos.BOTTOM);
        expression.getStyleClass().add("result-expression");
        details.getStyleClass().add("result-details");
        facts.getStyleClass().add("result-facts");
        facts.setAlignment(Pos.CENTER_RIGHT);
        facts.setMinWidth(USE_PREF_SIZE);
        HBox.setHgrow(message, Priority.ALWAYS);
        HBox.setHgrow(terms, Priority.ALWAYS);
        HBox row = new HBox(18, message, total, terms, facts);
        row.setAlignment(Pos.CENTER_LEFT);
        setAlignment(Pos.CENTER_LEFT);
        // At least room for the large total and a row of chips; a Roll with many terms wraps and grows the strip.
        setMinHeight(RollUi.RESULT_STRIP_HEIGHT);
        getChildren().add(row);

        setAccessibleRole(AccessibleRole.TEXT);
        // Focusable by Ctrl+R (#62) and by a click, but not a Tab stop: the spec's focus order doesn't list it.
        setFocusTraversable(false);
        setOnMousePressed(event -> requestFocus());
        showMessage("", null);
    }

    /**
     * Shows where the Roll stands: a status line, a Failed Roll's message where the total goes, or at rest the
     * whole Breakdown, stamped with the time from {@link #setClock the clock}.
     *
     * @param status the current status
     */
    void show(RollStatus status) {
        Objects.requireNonNull(status, "status");
        plain = status.text();
        switch (status) {
            case RollStatus.Rested rested -> showResult(rested);
            // The box and the Tray keep the failed Roll's expression and the previous Dice; the strip names it.
            case RollStatus.Failed failed -> showMessage(failed.message(), failed.expression().source().strip());
            default -> showMessage(status.text(), null);
        }
    }

    /** Shows only a line of text where the total goes, and the expression it is about, if any, on the right. */
    private void showMessage(String text, String about) {
        message.setText(text);
        show(message, true);
        show(total, false);
        blocks.clear();
        terms.getChildren().clear();
        show(terms, false);
        expression.setText(about == null ? "" : about);
        details.setText("");
        show(details, false);
        show(facts, about != null);
        setAccessibleText(text);
    }

    /** Shows a Roll Result's Breakdown. */
    private void showResult(RollStatus.Rested rested) {
        RollResult result = rested.result();
        message.setText("");
        show(message, false);
        total.setText(Integer.toString(result.total()));
        show(total, true);
        blocks.clear();
        boolean first = true;
        for (TermResult term : result.breakdown().terms()) {
            blocks.add(new TermBlock(term, first));
            first = false;
        }
        terms.getChildren().setAll(blocks);
        show(terms, true);

        expression.setText(rested.expression().source().strip());
        List<String> line = new ArrayList<>();
        rested.savedRollName().ifPresent(line::add);
        line.add(LocalTime.now(clock).format(TIME));
        line.add(BreakdownText.throwsText(rested.throwCount()));
        details.setText(String.join(BreakdownText.SEPARATOR, line));
        show(details, true);
        show(facts, true);
        setAccessibleText(BreakdownText.spokenSummary(result));
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /**
     * Sets the clock a revealed Roll's time is read from, in its zone. Tests fix it; the app uses the system clock
     * in the system time zone.
     *
     * @param clock the clock
     */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns what the strip says, as plain text: the status's own text ({@link RollStatus#text()}).
     *
     * @return the status line, or for a Roll at rest its expression and total, such as "1d6+2 = 6"
     */
    String text() {
        return plain;
    }

    /**
     * Returns the label the status line, or a Failed Roll's message, is drawn in, where the total goes.
     *
     * @return the message label; not shown while a Breakdown is
     */
    Label message() {
        return message;
    }

    /**
     * Returns the large total.
     *
     * @return the total's label; shown only once a Roll is at rest
     */
    Label total() {
        return total;
    }

    /**
     * Returns the Breakdown's blocks.
     *
     * @return one per term, in the expression's order; empty unless a Roll is at rest
     */
    List<TermBlock> terms() {
        return List.copyOf(blocks);
    }

    /**
     * Returns the expression on the right.
     *
     * @return the label holding the expression as typed; empty when no Roll is shown
     */
    Label expression() {
        return expression;
    }

    /**
     * Returns the facts line on the right, under the expression.
     *
     * @return the label, such as "Fireball · 14:05 · 2 Throws"; not shown unless a Roll is at rest
     */
    Label details() {
        return details;
    }
}
