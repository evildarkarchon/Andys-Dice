package andysdice.prototype.rollui;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.Text;
import javafx.stage.Popup;

/**
 * PROTOTYPE variant C, "Notebook sidebar": history-first. A tabbed sidebar (Saved, History grouped by day, Look,
 * Settings) beside the tray; a slim toolbar with the expression box and a die strip on top; the Roll Result as a
 * strip under the tray, Dice drawn as chips with rerolled-away values stacked behind. Errors select the span in
 * the box and pop a message under it, on Enter only. Notices collect quietly behind a bell.
 */
final class VariantC implements Variant {

    private static final String PAPER = "-fx-base: #e9e4da; -fx-background: #f6f2ea; -fx-control-inner-background: #fffdf8;"
            + " -fx-accent: #a0522d; -fx-focus-color: #a0522d; -fx-faint-focus-color: transparent;";

    private Variant.Ctx ctx;
    private TrayView tray;
    private Roller roller;
    private TextField field;
    private Popup errorPopup;
    private HBox strip;
    private TabPane tabs;
    private Button bell;
    private AppState.SavedRoll editing;
    private ListView<AppState.SavedRoll> savedList;

    @Override
    public String key() {
        return "C";
    }

    @Override
    public String name() {
        return "Notebook sidebar";
    }

    @Override
    public void applyDefaults(Behaviour b) {
        b.skip.set(Behaviour.Skip.FAST_FORWARD);
        b.input.set(Behaviour.Input.QUEUE);
        b.camera.set(Behaviour.Camera.ZOOM);
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
        roller.onStart = () -> strip.getChildren().setAll(waiting());
        roller.onReveal = this::showStrip;

        BorderPane main = new BorderPane(tray);
        main.setTop(toolbar());
        strip = new HBox(18);
        strip.setPadding(new Insets(10, 14, 10, 14));
        strip.setMinHeight(118);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setStyle("-fx-background-color: #fffdf8; -fx-border-color: #d8cfbf transparent transparent transparent;");
        strip.getChildren().setAll(muted("The Roll Result appears here once every Die is at rest."));
        main.setBottom(strip);

        BorderPane root = new BorderPane(main);
        root.setLeft(sidebar(state));
        root.setStyle(PAPER);
        BorderPane.setMargin(main, new Insets(0, 0, 52, 0));
        ctx.onDispose(tray::stop);
        ctx.onDispose(() -> errorPopup.hide());
        return root;
    }

    private Node toolbar() {
        field = new TextField();
        field.setFont(Ui.MONO);
        field.setPromptText("Roll Expression · Enter to roll · double-click a history entry to roll it again");
        HBox.setHgrow(field, Priority.ALWAYS);
        field.disableProperty().bind(roller.inputLocked());
        field.setOnAction(e -> roll(null));
        errorPopup = new Popup();
        errorPopup.setAutoHide(true);
        field.textProperty().addListener((o, a, b) -> errorPopup.hide());

        HBox dice = new HBox(2);
        for (Expr.Kind k : new Expr.Kind[] {Expr.Kind.D4, Expr.Kind.D6, Expr.Kind.D8, Expr.Kind.D10, Expr.Kind.D12,
                Expr.Kind.D20, Expr.Kind.D100}) {
            Button b = new Button(k.label);
            b.setFocusTraversable(false);
            b.setStyle("-fx-padding: 4 7; -fx-font-weight: bold;");
            b.setTooltip(new Tooltip("Add +1" + k.label + " at the end"));
            b.setOnAction(e -> {
                String t = field.getText().strip();
                field.setText(t.isEmpty() ? "1" + k.label : t + "+1" + k.label);
                field.requestFocus();
                field.end();
            });
            dice.getChildren().add(b);
        }
        Button roll = new Button("Roll");
        roll.setStyle("-fx-font-weight: bold; -fx-base: #c8a27a;");
        roll.disableProperty().bind(roller.rollBlocked());
        roll.setOnAction(e -> roll(null));
        Label queued = new Label();
        queued.setStyle("-fx-background-color: #fde7c8; -fx-padding: 2 8; -fx-background-radius: 10; -fx-text-fill: #7a4b12;");
        queued.textProperty().bind(Bindings.createStringBinding(() -> roller.queued.get() == null ? ""
                : "next: " + roller.queued.get().text(), roller.queued));
        queued.visibleProperty().bind(roller.queued.isNotNull());
        queued.managedProperty().bind(queued.visibleProperty());
        Label ff = new Label();
        ff.setStyle("-fx-text-fill: #7a6a55; -fx-font-size: 11px;");
        ff.textProperty().bind(Bindings.createStringBinding(() -> !tray.isBusy() ? ""
                : switch (ctx.beh.skip.get()) {
                    case FAST_FORWARD -> "hold Shift or the mouse on the tray: ×4";
                    case SKIP_ALL -> "Esc / click tray: skip";
                    case SKIP_THROW -> "Esc / click tray: skip Throw";
                    case NONE -> "";
                }, tray.busyProperty(), ctx.beh.skip));
        HBox bar = new HBox(8, field, queued, dice, roll, ff);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));
        bar.setStyle("-fx-background-color: #f1ebe0; -fx-border-color: transparent transparent #d8cfbf transparent;");
        return bar;
    }

    private void roll(String savedName) {
        Expr.ParseError e = roller.submit(field.getText(), savedName);
        if (e != null) {
            showError(e);
        }
    }

    /** Selects the span in the box and pops the message just under it. */
    private void showError(Expr.ParseError e) {
        field.requestFocus();
        field.selectRange(e.start(), Math.max(e.end(), Math.min(e.start() + 1, field.getLength())));
        Text probe = new Text(field.getText().substring(0, Math.min(e.start(), field.getLength())));
        probe.setFont(field.getFont());
        double x = field.snappedLeftInset() + probe.getLayoutBounds().getWidth();
        Label msg = new Label("▲  " + e.message());
        msg.setStyle("-fx-background-color: #b3261e; -fx-text-fill: white; -fx-padding: 6 10; -fx-background-radius: 6;"
                + " -fx-font-size: 13px;");
        errorPopup.getContent().setAll(msg);
        var p = field.localToScreen(Math.max(0, x - 6), field.getHeight() + 2);
        errorPopup.show(field, p.getX(), p.getY());
    }

    private Node sidebar(AppState state) {
        Label title = new Label("Andy's Dice");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        bell = new Button();
        bell.textProperty().bind(Bindings.createStringBinding(() -> state.notices.isEmpty() ? "🔔"
                : "🔔 " + state.notices.size(), state.notices));
        bell.styleProperty().bind(Bindings.createStringBinding(() -> state.notices.isEmpty() ? ""
                : "-fx-base: #f6c768; -fx-font-weight: bold;", state.notices));
        bell.setOnAction(e -> showNotices(state));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox head = new HBox(8, title, grow, bell);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(10, 10, 6, 12));

        tabs = new TabPane(new Tab("Saved", savedTab(state)), new Tab("History", historyTab(state)),
                new Tab("Look", lookTab(state)), new Tab("Settings", settingsTab(state)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().select(1);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox side = new VBox(head, tabs);
        side.setPrefWidth(340);
        side.setStyle("-fx-background-color: #efe8dc; -fx-border-color: transparent #d8cfbf transparent transparent;");
        BorderPane.setMargin(side, new Insets(0, 0, 52, 0));
        return side;
    }

    private void showNotices(AppState state) {
        VBox list = new VBox(8);
        list.setPadding(new Insets(12));
        list.setStyle(PAPER + " -fx-background-color: #fffdf8; -fx-border-color: #d8cfbf; -fx-effect: dropshadow(gaussian, #0004, 10, 0, 0, 3);");
        list.setPrefWidth(340);
        Popup pop = new Popup();
        pop.setAutoHide(true);
        if (state.notices.isEmpty()) {
            list.getChildren().add(muted("No notices."));
        }
        for (AppState.Notice n : List.copyOf(state.notices)) {
            Label t = new Label(n.title());
            t.setStyle("-fx-font-weight: bold;");
            Label d = new Label(n.detail());
            d.setWrapText(true);
            list.getChildren().add(new VBox(2, t, d));
        }
        if (!state.notices.isEmpty()) {
            Button ok = new Button("Dismiss all");
            ok.setOnAction(e -> {
                state.notices.clear();
                pop.hide();
            });
            list.getChildren().add(ok);
        }
        pop.getContent().setAll(list);
        var p = bell.localToScreen(0, bell.getHeight() + 4);
        pop.show(bell, p.getX(), p.getY());
    }

    private Node savedTab(AppState state) {
        Button add = new Button("+ New Saved Roll");
        add.setOnAction(e -> Ui.editSavedRoll(ctx.stage, state, null, field.getText()));
        savedList = new ListView<>(state.saved);
        savedList.setCellFactory(lv -> new SavedCell());
        VBox.setVgrow(savedList, Priority.ALWAYS);
        Label hint = muted("Click copies into the box · double-click rolls · ✎ edits in place");
        VBox v = new VBox(6, add, hint, savedList);
        v.setPadding(new Insets(8));
        return v;
    }

    private final class SavedCell extends ListCell<AppState.SavedRoll> {
        SavedCell() {
            setOnMouseClicked(e -> {
                AppState.SavedRoll s = getItem();
                if (s == null || s == editing || e.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                field.setText(s.expression.get());
                if (e.getClickCount() == 2 && s.error() == null) {
                    roll(s.name.get());
                }
            });
        }

        @Override
        protected void updateItem(AppState.SavedRoll s, boolean empty) {
            super.updateItem(s, empty);
            if (empty || s == null) {
                setGraphic(null);
                return;
            }
            setGraphic(s == editing ? editor(s) : view(s));
        }

        private Node view(AppState.SavedRoll s) {
            Expr.ParseError err = s.error();
            Label name = new Label(s.name.get());
            name.setStyle("-fx-font-weight: bold;");
            Label expr = new Label(s.expression.get());
            expr.setFont(Ui.MONO_SMALL);
            VBox text = new VBox(name, expr);
            if (err != null) {
                Label bad = new Label("⚠ doesn't parse: " + err.message());
                bad.setStyle("-fx-text-fill: #b3261e; -fx-font-size: 11px;");
                bad.setWrapText(true);
                bad.setMaxWidth(200);
                text.getChildren().add(bad);
            }
            Button up = icon("↑", "Move up");
            up.setOnAction(e -> move(s, -1));
            Button down = icon("↓", "Move down");
            down.setOnAction(e -> move(s, 1));
            Button edit = icon("✎", "Edit in place");
            edit.setOnAction(e -> {
                editing = s;
                savedList.refresh();
            });
            Button del = icon("🗑", "Delete…");
            del.setOnAction(e -> {
                if (Ui.confirm(ctx.stage, "Delete \"" + s.name.get() + "\"?", s.expression.get(), "Delete")) {
                    ctx.state.saved.remove(s);
                }
            });
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox row = new HBox(3, text, grow, up, down, edit, del);
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        }

        private Node editor(AppState.SavedRoll s) {
            TextField name = new TextField(s.name.get());
            TextField expr = new TextField(s.expression.get());
            expr.setFont(Ui.MONO_SMALL);
            Label err = new Label();
            err.setStyle("-fx-text-fill: #b3261e; -fx-font-size: 11px;");
            err.setWrapText(true);
            err.setMaxWidth(260);
            Button save = new Button("Save");
            Button cancel = new Button("Cancel");
            save.setOnAction(e -> {
                String problem = ctx.state.nameProblem(name.getText(), s);
                Expr.ParseError pe = Expr.parse(expr.getText()).error();
                if (problem != null) {
                    err.setText(problem);
                    name.requestFocus();
                } else if (pe != null) {
                    err.setText(pe.message());
                    expr.requestFocus();
                    expr.selectRange(pe.start(), Math.max(pe.end(), Math.min(pe.start() + 1, expr.getLength())));
                } else {
                    s.name.set(name.getText().strip());
                    s.expression.set(expr.getText().strip());
                    editing = null;
                    savedList.refresh();
                }
            });
            cancel.setOnAction(e -> {
                editing = null;
                savedList.refresh();
            });
            return new VBox(4, name, expr, err, new HBox(6, save, cancel));
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

    private Node historyTab(AppState state) {
        ObservableList<Object> rows = FXCollections.observableArrayList();
        Runnable fill = () -> {
            List<Object> out = new ArrayList<>();
            String day = null;
            for (AppState.HistoryEntry h : state.history) {
                String d = Ui.dayHeader(h.at());
                if (!d.equals(day)) {
                    out.add(d);
                    day = d;
                }
                out.add(h);
            }
            rows.setAll(out);
        };
        fill.run();
        ctx.listen(state.history, (ListChangeListener<AppState.HistoryEntry>) c -> fill.run());
        ListView<Object> list = new ListView<>(rows);
        list.setCellFactory(lv -> new HistoryCell());
        VBox.setVgrow(list, Priority.ALWAYS);
        Button clear = new Button("Clear all…");
        clear.setOnAction(e -> {
            if (Ui.confirm(ctx.stage, "Clear the Roll History?", "All " + state.history.size()
                    + " Rolls are removed. Saved Rolls stay.", "Clear all")) {
                state.history.clear();
            }
        });
        Label count = muted("");
        count.textProperty().bind(Bindings.size(state.history).asString("%d of " + AppState.HISTORY_CAP + " kept"));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bottom = new HBox(8, count, grow, clear);
        bottom.setAlignment(Pos.CENTER_LEFT);
        Label hint = muted("Click copies into the box · double-click rolls again · right-click for more");
        VBox v = new VBox(6, hint, list, bottom);
        v.setPadding(new Insets(8));
        return v;
    }

    private final class HistoryCell extends ListCell<Object> {
        HistoryCell() {
            setOnMouseClicked(e -> {
                if (getItem() instanceof AppState.HistoryEntry h && e.getButton() == MouseButton.PRIMARY) {
                    field.setText(h.expression());
                    if (e.getClickCount() == 2) {
                        roll(h.savedName());
                    }
                }
            });
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            setContextMenu(null);
            if (empty || item == null) {
                setGraphic(null);
                setStyle("");
                return;
            }
            if (item instanceof String day) {
                Label l = new Label(day.toUpperCase());
                l.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #8a7a62;");
                setGraphic(l);
                setStyle("-fx-background-color: transparent; -fx-padding: 8 4 2 4;");
                setMouseTransparent(true);
                return;
            }
            setMouseTransparent(false);
            setStyle("");
            AppState.HistoryEntry h = (AppState.HistoryEntry) item;
            Label when = new Label(Ui.clock(h.at()));
            when.setFont(Ui.MONO_SMALL);
            when.setStyle("-fx-text-fill: #8a7a62;");
            Label expr = new Label(h.expression());
            expr.setFont(Ui.MONO);
            Label total = new Label(String.valueOf(h.plan().total));
            total.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox line = new HBox(8, when, expr, grow, total);
            line.setAlignment(Pos.CENTER_LEFT);
            VBox body = new VBox(line);
            if (h.savedName() != null) {
                Label n = muted("        " + h.savedName());
                body.getChildren().add(n);
            }
            setGraphic(body);
            MenuItem again = new MenuItem("Roll again");
            again.setOnAction(e -> {
                field.setText(h.expression());
                roll(h.savedName());
            });
            MenuItem copy = new MenuItem("Copy into the box");
            copy.setOnAction(e -> field.setText(h.expression()));
            MenuItem save = new MenuItem("Save as a Saved Roll…");
            save.setOnAction(e -> Ui.editSavedRoll(ctx.stage, ctx.state, null, h.expression()));
            setContextMenu(new ContextMenu(again, copy, save));
        }
    }

    private Node lookTab(AppState state) {
        TilePane swatches = new TilePane(8, 10);
        swatches.setPrefColumns(4);
        Runnable fill = () -> {
            swatches.getChildren().clear();
            for (AppState.DiceSetInfo d : AppState.DICE_SETS) {
                Circle c = new Circle(16, d.body());
                c.setStroke(d == state.diceSet.get() ? Color.web("#a0522d") : d.ink().deriveColor(0, 1, 1, 0.5));
                c.setStrokeWidth(d == state.diceSet.get() ? 3.5 : 1);
                Label ink = new Label("20");
                ink.setTextFill(d.ink());
                ink.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
                StackPane sw = new StackPane(c, ink);
                Label name = new Label(d.name());
                name.setStyle("-fx-font-size: 10px;");
                name.setMaxWidth(66);
                VBox cell = new VBox(2, sw, name);
                if (d.player()) {
                    cell.getChildren().add(muted("yours"));
                }
                cell.setAlignment(Pos.TOP_CENTER);
                cell.setOnMouseClicked(e -> state.diceSet.set(d));
                Tooltip.install(cell, new Tooltip(d.name() + (d.player() ? " (from data\\dicesets\\)" : " (built in)")));
                swatches.getChildren().add(cell);
            }
        };
        fill.run();
        state.diceSet.addListener((o, a, b) -> fill.run());
        VBox skipped = new VBox(2);
        for (AppState.Skipped s : AppState.SKIPPED_DICE_SETS) {
            Label l = new Label("⊘ " + s.file() + ": " + s.reason());
            l.setStyle("-fx-text-fill: #9a8f80; -fx-font-size: 11px;");
            l.setWrapText(true);
            skipped.getChildren().add(l);
        }

        ToggleGroup trays = new ToggleGroup();
        VBox trayBox = new VBox(4);
        for (AppState.TrayInfo t : AppState.TRAYS) {
            RadioButton r = new RadioButton(t.toString());
            r.setToggleGroup(trays);
            r.setSelected(t == state.tray.get());
            r.setOnAction(e -> state.tray.set(t));
            trayBox.getChildren().add(r);
        }
        ToggleGroup rigs = new ToggleGroup();
        HBox rigBox = new HBox(10);
        for (String rig : AppState.LIGHT_RIGS) {
            RadioButton r = new RadioButton(rig);
            r.setToggleGroup(rigs);
            r.setSelected(rig.equals(state.lightRig.get()));
            r.setOnAction(e -> state.lightRig.set(rig));
            rigBox.getChildren().add(r);
        }
        VBox v = new VBox(8, VariantA.head("Dice Set"), swatches, skipped, VariantA.head("Tray"), trayBox,
                VariantA.head("Light Rig"), rigBox);
        v.setPadding(new Insets(10));
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        return sp;
    }

    private Node settingsTab(AppState state) {
        GridPane g = new GridPane(10, 10);
        g.setPadding(new Insets(12));
        VariantA.qualityAndSound(g, 0, state);
        return g;
    }

    private Node waiting() {
        return muted("Rolling…");
    }

    private void showStrip(AppState.HistoryEntry e) {
        Label total = new Label(String.valueOf(e.plan().total));
        total.setStyle("-fx-font-size: 56px; -fx-font-weight: bold; -fx-text-fill: #3a2a18;");
        total.setMinWidth(90);
        total.setAlignment(Pos.CENTER);
        FlowPane terms = new FlowPane(14, 8);
        HBox.setHgrow(terms, Priority.ALWAYS);
        boolean first = true;
        for (RollPlan.TermResult t : e.plan().terms) {
            String sign = Ui.signText(t.sign(), first).strip();
            first = false;
            if (t.term() instanceof Expr.Modifier) {
                Label m = new Label(sign + " " + t.text());
                m.setStyle("-fx-font-size: 22px; -fx-text-fill: #5a4630;");
                terms.getChildren().add(m);
                continue;
            }
            Expr.Group g = (Expr.Group) t.term();
            Label head = new Label((sign.isEmpty() ? "" : sign + " ") + t.text() + (g.advantage() == null ? "" : " " + g.advantage())
                    + "  = " + t.subtotal());
            head.setFont(Ui.MONO_SMALL);
            head.setStyle("-fx-text-fill: #7a6a55;");
            HBox chips = new HBox(6);
            for (RollPlan.DieResult d : t.dice()) {
                chips.getChildren().add(chip(d));
            }
            terms.getChildren().add(new VBox(3, head, chips));
        }
        Label expr = new Label(e.expression());
        expr.setFont(Ui.MONO);
        Label meta = muted((e.savedName() == null ? "" : e.savedName() + " · ") + Ui.clock(e.at())
                + " · " + e.plan().throwCount() + (e.plan().throwCount() == 1 ? " Throw" : " Throws"));
        VBox right = new VBox(2, expr, meta);
        right.setAlignment(Pos.CENTER_RIGHT);
        strip.getChildren().setAll(total, terms, right);
    }

    /** A die chip; values rerolled away are stacked behind it, and a Die that isn't kept is faded and slashed. */
    private static Node chip(RollPlan.DieResult d) {
        StackPane stack = new StackPane();
        stack.setAlignment(Pos.BOTTOM_LEFT);
        int behind = d.chain().size() - 1;
        for (int i = 0; i < behind; i++) {
            Label old = face(String.valueOf(d.chain().get(i)), "#e7dccb", "#9a8f80");
            StackPane.setMargin(old, new Insets(0, 0, (behind - i) * 6, (behind - i) * 6));
            stack.getChildren().add(old);
        }
        Label front = face(String.valueOf(d.value()), d.kept() ? "#3a2a18" : "#d9cfc0", d.kept() ? "#fff8ec" : "#8a7a62");
        stack.getChildren().add(front);
        if (!d.kept()) {
            Line slash = new Line(0, 30, 30, 0);
            slash.setStroke(Color.web("#b3261e"));
            slash.setStrokeWidth(2);
            slash.setMouseTransparent(true);
            stack.getChildren().add(slash);
        }
        if (behind > 0) {
            Label badge = new Label("↻" + (behind > 1 ? "×" + behind : ""));
            badge.setStyle("-fx-background-color: #e0a040; -fx-text-fill: white; -fx-font-size: 9px; -fx-padding: 0 3;"
                    + " -fx-background-radius: 6;");
            StackPane.setAlignment(badge, Pos.TOP_RIGHT);
            stack.getChildren().add(badge);
        }
        Tooltip.install(stack, new Tooltip(d.kind().label + ": " + (d.rerolled() ? "showed " + d.chain() + ", the last counts" : d.value())
                + (d.kept() ? "" : " · not kept")));
        stack.setPadding(new Insets(behind * 6, behind * 6, 0, 0));
        return stack;
    }

    private static Label face(String text, String bg, String fg) {
        Label l = new Label(text);
        l.setMinSize(30, 30);
        l.setMaxSize(30, 30);
        l.setAlignment(Pos.CENTER);
        l.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; -fx-font-weight: bold; -fx-font-size: 14px;"
                + " -fx-background-radius: 6; -fx-border-color: #0002; -fx-border-radius: 6;");
        return l;
    }

    private static Label muted(String s) {
        Label l = new Label(s);
        l.setStyle("-fx-text-fill: #8a7a62; -fx-font-size: 11px;");
        l.setWrapText(true);
        return l;
    }

    private static Button icon(String glyph, String tip) {
        Button b = new Button(glyph);
        b.setTooltip(new Tooltip(tip));
        b.setStyle("-fx-padding: 0 5; -fx-font-size: 12px;");
        b.setFocusTraversable(false);
        return b;
    }

    @Override
    public void demoType(String expression) {
        field.setText(expression);
        field.end();
    }

    @Override
    public void demoRoll() {
        roll(null);
    }

    @Override
    public void demoPanels() {
        tabs.getSelectionModel().select(2);
    }
}
