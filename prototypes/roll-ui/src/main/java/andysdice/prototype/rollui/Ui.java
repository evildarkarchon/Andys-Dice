package andysdice.prototype.rollui;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;

/** PROTOTYPE helpers shared by the variants: time formats, a confirm dialog and the Saved Roll editor. */
final class Ui {

    static final Font MONO = Font.font("Consolas", 15);
    static final Font MONO_SMALL = Font.font("Consolas", 12);
    static final Font MONO_BOLD = Font.font("Consolas", FontWeight.BOLD, 15);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter CLOCK_SEC = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss", Locale.UK);

    private Ui() {
    }

    static String relative(Instant at) {
        Duration d = Duration.between(at, Instant.now());
        if (d.toSeconds() < 45) {
            return "just now";
        }
        if (d.toMinutes() < 60) {
            return Math.max(1, d.toMinutes()) + " min ago";
        }
        if (d.toHours() < 24) {
            return d.toHours() + " h ago";
        }
        long days = d.toDays();
        return days == 1 ? "yesterday" : days + " days ago";
    }

    static String clock(Instant at) {
        return CLOCK.format(at.atZone(ZoneId.systemDefault()));
    }

    static String clockSeconds(Instant at) {
        return CLOCK_SEC.format(at.atZone(ZoneId.systemDefault()));
    }

    static String full(Instant at) {
        return FULL.format(at.atZone(ZoneId.systemDefault()));
    }

    static String dayHeader(Instant at) {
        LocalDate day = at.atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (day.equals(today)) {
            return "Today";
        }
        if (day.equals(today.minusDays(1))) {
            return "Yesterday";
        }
        return DAY.format(day);
    }

    /** The caret line under a compiler-style echo: spaces up to the span, then one ^ per character (at least one). */
    static String caretLine(Expr.ParseError e) {
        return " ".repeat(e.start()) + "^".repeat(Math.max(1, e.end() - e.start()));
    }

    /** A sign as it sits between terms: nothing before a leading +, a real minus sign otherwise. */
    static String signText(int sign, boolean first) {
        if (first) {
            return sign < 0 ? "−" : "";
        }
        return sign < 0 ? " − " : " + ";
    }

    static boolean confirm(Window owner, String header, String content, String okText) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.initOwner(owner);
        a.setTitle("Andy's Dice");
        a.setHeaderText(header);
        a.setContentText(content);
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        a.getButtonTypes().setAll(ok, ButtonType.CANCEL);
        // Cancel is the default so Enter never destroys anything by accident.
        ((Button) a.getDialogPane().lookupButton(ok)).setDefaultButton(false);
        ((Button) a.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return a.showAndWait().filter(ok::equals).isPresent();
    }

    /**
     * The Saved Roll editor, shared by every variant: a new one when {@code existing} is null (pre-filled with
     * {@code expression}), else an edit in place. It refuses to close on an invalid name, or on an expression that
     * doesn't parse, showing the #7 error under its span (ADR-0007: the expression must parse when saved).
     *
     * @return the saved or edited Saved Roll, or empty if cancelled
     */
    static Optional<AppState.SavedRoll> editSavedRoll(Window owner, AppState state, AppState.SavedRoll existing, String expression) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle(existing == null ? "Save Roll" : "Edit Saved Roll");
        TextField name = new TextField(existing == null ? "" : existing.name.get());
        name.setPromptText("Name, such as Longsword attack");
        TextField expr = new TextField(existing == null ? expression : existing.expression.get());
        expr.setFont(MONO);
        expr.setPrefColumnCount(28);
        Label nameErr = new Label();
        nameErr.setStyle("-fx-text-fill: #c62828;");
        Label echo = new Label();
        echo.setFont(MONO);
        Label caret = new Label();
        caret.setFont(MONO);
        caret.setStyle("-fx-text-fill: #c62828;");
        Label exprErr = new Label();
        exprErr.setStyle("-fx-text-fill: #c62828;");
        VBox exprBox = new VBox(expr, echo, caret, exprErr);
        GridPane g = new GridPane(10, 6);
        g.setPadding(new Insets(12));
        g.addRow(0, new Label("Name"), name);
        g.add(nameErr, 1, 1);
        g.addRow(2, new Label("Roll Expression"), exprBox);
        d.getDialogPane().setContent(g);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        Runnable clear = () -> {
            for (Label l : new Label[] {nameErr, echo, caret, exprErr}) {
                l.setText("");
                l.setManaged(false);
            }
        };
        clear.run();
        Expr.ParseError initial = Expr.parse(expr.getText()).error();
        if (existing != null && initial != null) {
            showEcho(expr.getText(), initial, echo, caret, exprErr);
        }
        d.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, ev -> {
            clear.run();
            String problem = state.nameProblem(name.getText(), existing);
            Expr.ParseError err = Expr.parse(expr.getText()).error();
            if (problem != null) {
                nameErr.setText(problem);
                nameErr.setManaged(true);
            }
            if (err != null) {
                showEcho(expr.getText(), err, echo, caret, exprErr);
            }
            if (problem != null || err != null) {
                ev.consume();
                d.getDialogPane().getScene().getWindow().sizeToScene();
            }
        });
        Optional<ButtonType> r = d.showAndWait();
        if (r.isEmpty() || r.get() != save) {
            return Optional.empty();
        }
        AppState.SavedRoll target = existing;
        if (target == null) {
            target = new AppState.SavedRoll(name.getText().strip(), expr.getText().strip());
            state.saved.add(target);
        } else {
            target.name.set(name.getText().strip());
            target.expression.set(expr.getText().strip());
        }
        return Optional.of(target);
    }

    private static void showEcho(String src, Expr.ParseError err, Label echo, Label caret, Label msg) {
        echo.setText(src);
        caret.setText(caretLine(err));
        msg.setText(err.message());
        echo.setManaged(true);
        caret.setManaged(true);
        msg.setManaged(true);
    }
}
