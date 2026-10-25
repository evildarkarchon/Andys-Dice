package andysdice.prototype.rollui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polyline;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.util.Duration;

/**
 * PROTOTYPE variant A, "Command bar over the tray": the tray fills the window, and everything else floats over
 * it. Entry is one keyboard-first text box (live validation with a red squiggle under the error span, Up/Down
 * recalls past Roll Expressions). The Roll Result is a card over the top of the tray. Saved Rolls and the Roll
 * History share a drawer, settings sit in a popover, and notices are toasts. Confirmations are inline.
 */
final class VariantA implements Variant {

    static final String DARK = "-fx-base: #30343b; -fx-background: #1e2126; -fx-control-inner-background: #24272d;"
            + " -fx-accent: #4f8cff; -fx-focus-color: #4f8cff; -fx-faint-focus-color: transparent;";
    private static final String PANEL = DARK + " -fx-background-color: rgba(22,24,28,0.92); -fx-background-radius: 10;";

    private Variant.Ctx ctx;
    private TrayView tray;
    private Roller roller;
    private TextField field;
    private Pane squiggle;
    private Label error;
    private Label info;
    private VBox card;
    private ToggleButton drawerToggle;
    private VBox toasts;
    private int historyCursor = -1;
    private String draft = "";

    @Override
    public String key() {
        return "A";
    }

    @Override
    public String name() {
        return "Command bar over the tray";
    }

    @Override
    public void applyDefaults(Behaviour b) {
        b.skip.set(Behaviour.Skip.SKIP_ALL);
        b.input.set(Behaviour.Input.INTERRUPT);
        b.camera.set(Behaviour.Camera.ORBIT);
    }

    @Override
    public TrayView tray() {
        return tray;
    }

    @Override
    public Region build(Variant.Ctx ctx) {
        this.ctx = ctx;
        AppState state = ctx.state;
        tray = new TrayView(state, ctx.beh);
        roller = new Roller(state, tray, ctx.beh);
        StackPane root = new StackPane(tray);
        root.setStyle(DARK);

        root.getChildren().add(commandBar());
        root.getChildren().add(resultCard());

        Button gear = new Button("⚙  Look & settings");
        Popup settings = new Popup();
        settings.setAutoHide(true);
        settings.getContent().add(settingsPanel(state));
        gear.setOnAction(e -> {
            var p = gear.localToScreen(0, gear.getHeight() + 4);
            settings.show(gear, p.getX(), p.getY());
        });
        StackPane.setAlignment(gear, Pos.TOP_LEFT);
        StackPane.setMargin(gear, new Insets(56, 0, 0, 10));

        drawerToggle = new ToggleButton("☰  Saved Rolls & History");
        StackPane.setAlignment(drawerToggle, Pos.TOP_RIGHT);
        StackPane.setMargin(drawerToggle, new Insets(10, 10, 0, 0));
        VBox drawer = drawer(state);
        drawer.visibleProperty().bind(drawerToggle.selectedProperty());
        StackPane.setAlignment(drawer, Pos.TOP_RIGHT);
        StackPane.setMargin(drawer, new Insets(46, 10, 64, 0));

        toasts = new VBox(8);
        toasts.setPickOnBounds(false);
        toasts.setMaxSize(360, Region.USE_PREF_SIZE);
        // Bottom-left, so they never sit on the drawer (right) or the command bar (centre).
        StackPane.setAlignment(toasts, Pos.BOTTOM_LEFT);
        StackPane.setMargin(toasts, new Insets(0, 0, 64, 12));
        root.getChildren().addAll(gear, drawer, drawerToggle, toasts);

        state.notices.forEach(this::toast);
        ctx.listen(state.notices, (ListChangeListener<AppState.Notice>) c -> {
            while (c.next()) {
                c.getAddedSubList().forEach(this::toast);
            }
        });
        ctx.onDispose(tray::stop);
        return root;
    }

    private Node commandBar() {
        field = new TextField();
        field.setFont(Ui.MONO);
        field.setPromptText("Roll Expression, such as 4d6rr1kh3+2 or 1d20+5 adv");
        squiggle = new Pane();
        squiggle.setMouseTransparent(true);
        StackPane fieldStack = new StackPane(field, squiggle);
        HBox.setHgrow(fieldStack, Priority.ALWAYS);
        Button roll = new Button("Roll");
        roll.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        roll.setOnAction(e -> doRoll());
        roll.disableProperty().bind(roller.rollBlocked());
        field.disableProperty().bind(roller.inputLocked());
        field.setOnAction(e -> doRoll());

        error = new Label();
        error.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
        info = new Label();
        info.setStyle("-fx-text-fill: #aab2bd; -fx-font-size: 12px;");

        // Live validation: a beat after the player stops typing, so the squiggle doesn't flash mid-word.
        PauseTransition debounce = new PauseTransition(Duration.millis(300));
        debounce.setOnFinished(e -> validate(false));
        field.textProperty().addListener((o, a, b) -> {
            clearError();
            debounce.playFromStart();
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) {
                recall(e.getCode() == KeyCode.UP);
                e.consume();
            }
        });

        Label status = new Label();
        status.setStyle("-fx-text-fill: #ffd54a; -fx-font-size: 12px;");
        status.textProperty().bind(Bindings.createStringBinding(() -> {
            if (roller.queued.get() != null) {
                return "Queued next: " + roller.queued.get().text();
            }
            if (!tray.isBusy()) {
                return "";
            }
            return "Dice moving · " + switch (ctx.beh.skip.get()) {
                case SKIP_ALL -> "Esc or click the tray to skip to the result";
                case SKIP_THROW -> "Esc or click the tray to skip this Throw";
                case FAST_FORWARD -> "hold Shift (or hold the mouse on the tray) to fast-forward";
                case NONE -> "no skipping";
            } + switch (ctx.beh.input.get()) {
                case INTERRUPT -> " · Enter rolls now";
                case QUEUE -> " · Enter queues";
                case TYPE_AHEAD -> " · type ahead; Roll waits";
                case LOCKED -> "";
            };
        }, tray.busyProperty(), roller.queued, ctx.beh.skip, ctx.beh.input));

        HBox bar = new HBox(8, fieldStack, roll);
        bar.setAlignment(Pos.CENTER);
        VBox cmd = new VBox(4, status, bar, error, info);
        cmd.setPadding(new Insets(10, 12, 8, 12));
        cmd.setMaxWidth(700);
        cmd.setMaxHeight(Region.USE_PREF_SIZE);
        cmd.setStyle(PANEL);
        StackPane.setAlignment(cmd, Pos.BOTTOM_CENTER);
        StackPane.setMargin(cmd, new Insets(0, 0, 60, 0));
        return cmd;
    }

    private Node resultCard() {
        card = new VBox(2);
        card.setPadding(new Insets(10, 18, 12, 18));
        card.setMaxSize(640, Region.USE_PREF_SIZE);
        card.setStyle(PANEL);
        card.setVisible(false);
        card.setMouseTransparent(true);
        StackPane.setAlignment(card, Pos.TOP_CENTER);
        StackPane.setMargin(card, new Insets(10, 0, 0, 0));
        roller.onStart = () -> card.setVisible(false);
        roller.onReveal = this::showResult;
        return card;
    }

    private void showResult(AppState.HistoryEntry e) {
        Label head = new Label(e.expression() + (e.savedName() == null ? "" : "   ·   " + e.savedName()));
        head.setFont(Ui.MONO_SMALL);
        head.setStyle("-fx-text-fill: #aab2bd;");
        Label total = new Label(String.valueOf(e.plan().total));
        total.setStyle("-fx-text-fill: white; -fx-font-size: 52px; -fx-font-weight: bold;");
        HBox top = new HBox(16, total, breakdown(e.plan(), Color.WHITE));
        top.setAlignment(Pos.CENTER_LEFT);
        Label legend = new Label("n↻ = rerolled away   ·   struck = not kept");
        legend.setStyle("-fx-text-fill: #7d8590; -fx-font-size: 10px;");
        card.getChildren().setAll(head, top, legend);
        card.setVisible(true);
        FadeTransition f = new FadeTransition(Duration.millis(250), card);
        f.setFromValue(0);
        f.setToValue(1);
        f.play();
    }

    /** One-line breakdown: each term as written, then its Dice in brackets; a chain reads {@code 1↻ 5}. */
    static TextFlow breakdown(RollPlan plan, Color ink) {
        TextFlow flow = new TextFlow();
        flow.setMaxWidth(520);
        boolean first = true;
        for (RollPlan.TermResult t : plan.terms) {
            flow.getChildren().add(text(Ui.signText(t.sign(), first), ink, false, false));
            first = false;
            if (t.term() instanceof Expr.Modifier) {
                flow.getChildren().add(text(t.text(), ink, true, false));
                continue;
            }
            Expr.Group g = (Expr.Group) t.term();
            flow.getChildren().add(text(t.text() + (g.advantage() == null ? "" : " " + g.advantage()), ink, true, false));
            flow.getChildren().add(text(" [", ink.deriveColor(0, 1, 0.7, 1), false, false));
            for (int i = 0; i < t.dice().size(); i++) {
                RollPlan.DieResult d = t.dice().get(i);
                if (i > 0) {
                    flow.getChildren().add(text(", ", ink.deriveColor(0, 1, 0.7, 1), false, false));
                }
                for (int c = 0; c < d.chain().size() - 1; c++) {
                    Text old = text(d.chain().get(c) + "↻ ", Color.web("#e0a040"), false, false);
                    old.setFont(Font.font("Consolas", 12));
                    flow.getChildren().add(old);
                }
                flow.getChildren().add(text(String.valueOf(d.value()), d.kept() ? ink : Color.web("#7d8590"), d.kept(), !d.kept()));
            }
            flow.getChildren().add(text("]", ink.deriveColor(0, 1, 0.7, 1), false, false));
        }
        flow.getChildren().add(text("  =  " + plan.total, ink, true, false));
        return flow;
    }

    private static Text text(String s, Color c, boolean bold, boolean strike) {
        Text t = new Text(s);
        t.setFont(bold ? Ui.MONO_BOLD : Ui.MONO);
        t.setFill(c);
        t.setStrikethrough(strike);
        return t;
    }

    private void doRoll() {
        if (roller.rollBlocked().get()) {
            return;
        }
        Expr.ParseError e = roller.submit(field.getText(), null);
        if (e != null) {
            showError(e);
        } else {
            historyCursor = -1;
        }
    }

    private void validate(boolean force) {
        if (field.getText().isBlank() && !force) {
            clearError();
            return;
        }
        Expr.Result r = Expr.parse(field.getText());
        if (r.error() != null) {
            showError(r.error());
        } else {
            info.setText("✓  " + r.ok().physicalDice() + (r.ok().physicalDice() == 1 ? " Die" : " Dice")
                    + " · Enter to roll");
        }
    }

    private void clearError() {
        error.setText("");
        info.setText("");
        squiggle.getChildren().clear();
    }

    private void showError(Expr.ParseError e) {
        error.setText("✕  " + e.message());
        info.setText("");
        double x0 = textX(e.start());
        double x1 = e.end() > e.start() ? textX(e.end()) : x0 + 9;
        Polyline wave = new Polyline();
        double y = field.getHeight() - 6;
        boolean up = true;
        for (double x = x0; x <= x1 + 0.1; x += 2.5) {
            wave.getPoints().addAll(x, y + (up ? -1.5 : 1.5));
            up = !up;
        }
        wave.setStroke(Color.web("#ff5252"));
        wave.setStrokeWidth(1.4);
        squiggle.getChildren().setAll(wave);
    }

    /** The x of character {@code index} inside the field; fine while the expression fits without scrolling. */
    private double textX(int index) {
        String s = field.getText();
        Text probe = new Text(s.substring(0, Math.min(index, s.length())));
        probe.setFont(field.getFont());
        return field.snappedLeftInset() + probe.getLayoutBounds().getWidth();
    }

    private void recall(boolean older) {
        var h = ctx.state.history;
        if (h.isEmpty()) {
            return;
        }
        if (historyCursor == -1) {
            draft = field.getText();
        }
        historyCursor = Math.clamp(historyCursor + (older ? 1 : -1), -1, h.size() - 1);
        field.setText(historyCursor == -1 ? draft : h.get(historyCursor).expression());
        field.end();
    }

    private VBox drawer(AppState state) {
        Label savedHead = new Label("Saved Rolls");
        savedHead.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        ListView<AppState.SavedRoll> saved = new ListView<>(state.saved);
        saved.setPrefHeight(230);
        saved.setCellFactory(lv -> new SavedCell());
        Button add = new Button("+ Save the current expression…");
        add.setOnAction(e -> Ui.editSavedRoll(ctx.stage, state, null, field.getText()));

        Label histHead = new Label();
        histHead.textProperty().bind(Bindings.size(state.history).asString("History  (%d of " + AppState.HISTORY_CAP + ")"));
        histHead.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        Button clear = new Button("Clear all");
        HBox confirmRow = new HBox(6);
        confirmRow.setAlignment(Pos.CENTER_RIGHT);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox histBar = new HBox(8, histHead, grow, clear);
        histBar.setAlignment(Pos.CENTER_LEFT);
        clear.setOnAction(e -> {
            // Inline confirmation: the button turns into the question, so nothing pops over the tray.
            Label q = new Label("Clear all " + state.history.size() + " Rolls?");
            q.setStyle("-fx-text-fill: #ff8a80;");
            Button yes = new Button("Clear");
            yes.setStyle("-fx-base: #8e2b2b;");
            Button no = new Button("Cancel");
            yes.setOnAction(x -> {
                state.history.clear();
                histBar.getChildren().setAll(histHead, grow, clear);
            });
            no.setOnAction(x -> histBar.getChildren().setAll(histHead, grow, clear));
            histBar.getChildren().setAll(histHead, grow, q, yes, no);
        });
        ListView<AppState.HistoryEntry> history = new ListView<>(state.history);
        history.setCellFactory(lv -> new HistoryCell());
        VBox.setVgrow(history, Priority.ALWAYS);

        VBox drawer = new VBox(8, savedHead, saved, add, histBar, history);
        drawer.setPadding(new Insets(12));
        drawer.setPrefWidth(380);
        drawer.setMaxWidth(380);
        drawer.setStyle(PANEL);
        return drawer;
    }

    private final class SavedCell extends ListCell<AppState.SavedRoll> {
        @Override
        protected void updateItem(AppState.SavedRoll s, boolean empty) {
            super.updateItem(s, empty);
            if (empty || s == null) {
                setGraphic(null);
                return;
            }
            Expr.ParseError err = s.error();
            Label name = new Label((err != null ? "⚠ " : "") + s.name.get());
            name.setStyle("-fx-font-weight: bold;" + (err != null ? " -fx-text-fill: #ffb74d;" : ""));
            Label expr = new Label(s.expression.get());
            expr.setFont(Ui.MONO_SMALL);
            VBox text = new VBox(name, expr);
            if (err != null) {
                Tooltip.install(text, new Tooltip("Doesn't parse any more: " + err.message() + "\nEdit it to roll it again."));
            }
            Button play = small("▶", "Roll it");
            play.setDisable(err != null);
            play.disableProperty().bind(Bindings.createBooleanBinding(() -> err != null || roller.rollBlocked().get(),
                    tray.busyProperty(), ctx.beh.input));
            play.setOnAction(e -> roller.submit(s.expression.get(), s.name.get()));
            Button edit = small("✎", "Edit");
            edit.setOnAction(e -> Ui.editSavedRoll(ctx.stage, ctx.state, s, null));
            Button upB = small("↑", "Move up");
            Button downB = small("↓", "Move down");
            upB.setOnAction(e -> move(s, -1));
            downB.setOnAction(e -> move(s, 1));
            Button del = small("✕", "Delete");
            del.setOnAction(e -> {
                if (!"Delete?".equals(del.getText())) {
                    del.setText("Delete?");
                    del.setStyle("-fx-base: #8e2b2b;");
                    PauseTransition back = new PauseTransition(Duration.seconds(3));
                    back.setOnFinished(x -> {
                        del.setText("✕");
                        del.setStyle("");
                    });
                    back.play();
                } else {
                    ctx.state.saved.remove(s);
                }
            });
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox row = new HBox(4, text, grow, play, edit, upB, downB, del);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }

    private void move(AppState.SavedRoll s, int by) {
        var list = ctx.state.saved;
        int i = list.indexOf(s);
        int j = i + by;
        if (j >= 0 && j < list.size()) {
            list.remove(i);
            list.add(j, s);
        }
    }

    private final class HistoryCell extends ListCell<AppState.HistoryEntry> {
        @Override
        protected void updateItem(AppState.HistoryEntry h, boolean empty) {
            super.updateItem(h, empty);
            if (empty || h == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            Label expr = new Label(h.expression());
            expr.setFont(Ui.MONO);
            Label total = new Label("= " + h.plan().total);
            total.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
            Label when = new Label(Ui.relative(h.at()));
            when.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 11px;");
            Tooltip.install(when, new Tooltip(Ui.full(h.at())));
            Label name = new Label(h.savedName() == null ? "" : h.savedName());
            name.setFont(Font.font("Segoe UI", FontWeight.NORMAL, FontPosture.ITALIC, 11));
            name.setStyle("-fx-text-fill: #8b949e;");
            Button again = small("⟳", "Roll again");
            again.disableProperty().bind(roller.rollBlocked());
            again.setOnAction(e -> roller.submit(h.expression(), h.savedName()));
            Button copy = small("⧉", "Copy into the expression box");
            copy.setOnAction(e -> {
                field.setText(h.expression());
                field.requestFocus();
                field.end();
            });
            Button save = small("★", "Save as a Saved Roll");
            save.setOnAction(e -> Ui.editSavedRoll(ctx.stage, ctx.state, null, h.expression()));
            Region g1 = new Region();
            HBox.setHgrow(g1, Priority.ALWAYS);
            Region g2 = new Region();
            HBox.setHgrow(g2, Priority.ALWAYS);
            HBox line1 = new HBox(6, expr, total, g1, again, copy, save);
            line1.setAlignment(Pos.CENTER_LEFT);
            HBox line2 = new HBox(6, name, g2, when);
            setGraphic(new VBox(1, line1, line2));
            setTooltip(new Tooltip("Facts kept (ADR-0007): " + h.plan().facts()));
        }
    }

    private static Button small(String glyph, String tip) {
        Button b = new Button(glyph);
        b.setTooltip(new Tooltip(tip));
        b.setStyle("-fx-padding: 1 6; -fx-font-size: 12px;");
        b.setFocusTraversable(false);
        return b;
    }

    private void toast(AppState.Notice n) {
        Label title = new Label(n.title());
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffd54a;");
        Label detail = new Label(n.detail());
        detail.setWrapText(true);
        detail.setMaxWidth(290);
        Button close = small("✕", "Dismiss");
        close.setMinWidth(Region.USE_PREF_SIZE);
        Label icon = new Label("ⓘ");
        icon.setMinWidth(Region.USE_PREF_SIZE);
        VBox text = new VBox(2, title, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(8, icon, text, close);
        box.setPadding(new Insets(10));
        box.setStyle(PANEL + " -fx-border-color: #ffd54a55; -fx-border-radius: 10;");
        Runnable dismiss = () -> {
            toasts.getChildren().remove(box);
            ctx.state.notices.remove(n);
        };
        close.setOnAction(e -> dismiss.run());
        // Toasts don't block and don't linger: gone after 12 s, which is what makes them one-time.
        PauseTransition life = new PauseTransition(Duration.seconds(12));
        life.setOnFinished(e -> dismiss.run());
        life.play();
        toasts.getChildren().add(box);
    }

    /** Dice Set, Tray, Light Rig, quality and sound as plain controls; variant C reuses the lower half. */
    private Node settingsPanel(AppState state) {
        ComboBox<AppState.DiceSetInfo> sets = new ComboBox<>();
        sets.getItems().setAll(AppState.DICE_SETS);
        sets.valueProperty().bindBidirectional(state.diceSet);
        sets.setCellFactory(lv -> new SwatchCell());
        sets.setButtonCell(new SwatchCell());
        sets.setVisibleRowCount(16);
        Label skipped = new Label("Skipped: " + String.join(", ",
                AppState.SKIPPED_DICE_SETS.stream().map(AppState.Skipped::file).toList()));
        skipped.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 11px;");
        skipped.setTooltip(new Tooltip(String.join("\n", AppState.SKIPPED_DICE_SETS.stream()
                .map(s -> s.file() + ": " + s.reason()).toList()) + "\nFolder: data\\dicesets\\"));

        ComboBox<AppState.TrayInfo> trays = new ComboBox<>();
        trays.getItems().setAll(AppState.TRAYS);
        trays.valueProperty().bindBidirectional(state.tray);

        ToggleGroup rigs = new ToggleGroup();
        HBox rigBox = new HBox();
        for (String rig : AppState.LIGHT_RIGS) {
            ToggleButton b = new ToggleButton(rig);
            b.setToggleGroup(rigs);
            b.setSelected(rig.equals(state.lightRig.get()));
            b.setOnAction(e -> {
                state.lightRig.set(rig);
                b.setSelected(true);
            });
            rigBox.getChildren().add(b);
        }

        GridPane g = new GridPane(10, 8);
        g.addRow(0, head("Dice Set"), sets);
        g.add(skipped, 1, 1);
        g.addRow(2, head("Tray"), trays);
        g.addRow(3, head("Light Rig"), rigBox);
        qualityAndSound(g, 4, state);
        g.setPadding(new Insets(14));
        g.setStyle(PANEL);
        return g;
    }

    /** Adds the ADR-0002 quality levers and the sound settings to {@code g} from {@code row}; returns the next row. */
    static int qualityAndSound(GridPane g, int row, AppState state) {
        ChoiceBox<String> scale = new ChoiceBox<>();
        scale.getItems().setAll("50%", "75%", "100%");
        scale.setValue((int) (state.renderScale.get() * 100) + "%");
        scale.setOnAction(e -> state.renderScale.set(Integer.parseInt(scale.getValue().replace("%", "")) / 100.0));
        ChoiceBox<String> msaa = new ChoiceBox<>();
        msaa.getItems().setAll("Off", "2×", "4×", "8×");
        msaa.setValue(state.msaa.get() == 0 ? "Off" : state.msaa.get() + "×");
        msaa.setOnAction(e -> state.msaa.set(msaa.getValue().equals("Off") ? 0 : Integer.parseInt(msaa.getValue().replace("×", ""))));
        ChoiceBox<String> shadows = new ChoiceBox<>();
        shadows.getItems().setAll("Low", "Medium", "High");
        shadows.valueProperty().bindBidirectional(state.shadowQuality);
        CheckBox mute = new CheckBox("Mute");
        mute.selectedProperty().bindBidirectional(state.muted);
        Slider vol = new Slider(0, 1, state.volume.get());
        vol.valueProperty().bindBidirectional(state.volume);
        vol.disableProperty().bind(state.muted);
        g.addRow(row++, head("Render scale"), scale);
        g.addRow(row++, head("Anti-aliasing"), msaa);
        g.addRow(row++, head("Shadows"), shadows);
        g.addRow(row++, head("Sound"), new HBox(10, mute, vol));
        return row;
    }

    static Label head(String s) {
        Label l = new Label(s);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    static final class SwatchCell extends ListCell<AppState.DiceSetInfo> {
        @Override
        protected void updateItem(AppState.DiceSetInfo d, boolean empty) {
            super.updateItem(d, empty);
            if (empty || d == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Circle c = new Circle(7, d.body());
            c.setStroke(d.ink());
            setGraphic(c);
            setText(d.toString());
        }
    }

    @Override
    public void demoType(String expression) {
        field.setText(expression);
        field.end();
    }

    @Override
    public void demoRoll() {
        doRoll();
    }

    @Override
    public void demoPanels() {
        drawerToggle.setSelected(true);
    }
}
