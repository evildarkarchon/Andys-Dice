package andysdice.prototype.rollui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;

/**
 * PROTOTYPE variant B, "Workbench": a classic three-column desktop window under a menu bar. On the left a builder
 * (die buttons that write the Roll Expression, a Modifier spinner, Advantage toggles) above the Saved Rolls; in the
 * middle the expression box, the Roll button (it becomes Skip while dice move) and the tray; on the right the Roll
 * Result as a receipt, one row per term, above the Roll History with clock times. Errors are compiler-style (the
 * expression echoed with carets under the span), shown on Roll only. Confirmations are modal; notices are banners.
 */
final class VariantB implements Variant {

    private static final Expr.Kind[] KINDS = {Expr.Kind.D4, Expr.Kind.D6, Expr.Kind.D8, Expr.Kind.D10,
            Expr.Kind.D12, Expr.Kind.D20, Expr.Kind.D100};

    private Variant.Ctx ctx;
    private TrayView tray;
    private Roller roller;
    private TextField field;
    private Label echo;
    private Label carets;
    private Label message;
    private VBox receipt;
    private VBox banners;
    private final Map<Expr.Kind, Button> dieButtons = new EnumMap<>(Expr.Kind.class);
    private Spinner<Integer> modifier;
    private ToggleGroup advGroup;
    private boolean building;

    @Override
    public String key() {
        return "B";
    }

    @Override
    public String name() {
        return "Workbench";
    }

    @Override
    public void applyDefaults(Behaviour b) {
        b.skip.set(Behaviour.Skip.SKIP_THROW);
        b.input.set(Behaviour.Input.LOCKED);
        b.camera.set(Behaviour.Camera.AUTO);
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
        roller.onReveal = e -> showReceipt(e, false);
        roller.onStart = () -> receipt.getChildren().setAll(muted("Rolling… the Roll Result appears when every Die is at rest."));

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #eef0f3;");
        banners = new VBox();
        root.setTop(new VBox(menuBar(state), banners));
        root.setLeft(leftColumn(state));
        root.setCenter(centerColumn());
        root.setRight(rightColumn(state));
        BorderPane.setMargin(root.getCenter(), new Insets(0, 0, 52, 0));
        BorderPane.setMargin(root.getLeft(), new Insets(0, 0, 52, 0));
        BorderPane.setMargin(root.getRight(), new Insets(0, 0, 52, 0));

        state.notices.forEach(this::banner);
        ctx.listen(state.notices, (ListChangeListener<AppState.Notice>) c -> {
            while (c.next()) {
                c.getAddedSubList().forEach(this::banner);
            }
        });
        ctx.onDispose(tray::stop);
        return root;
    }

    private MenuBar menuBar(AppState state) {
        Menu dice = new Menu("Dice Set");
        ToggleGroup setGroup = new ToggleGroup();
        boolean playerSeen = false;
        for (AppState.DiceSetInfo d : AppState.DICE_SETS) {
            if (d.player() && !playerSeen) {
                dice.getItems().add(new SeparatorMenuItem());
                MenuItem h = new MenuItem("Yours, from data\\dicesets\\");
                h.setDisable(true);
                dice.getItems().add(h);
                playerSeen = true;
            }
            RadioMenuItem r = new RadioMenuItem(d.name());
            Circle c = new Circle(6, d.body());
            c.setStroke(d.ink());
            r.setGraphic(c);
            r.setToggleGroup(setGroup);
            r.setSelected(d == state.diceSet.get());
            r.setOnAction(e -> state.diceSet.set(d));
            dice.getItems().add(r);
        }
        for (AppState.Skipped s : AppState.SKIPPED_DICE_SETS) {
            MenuItem m = new MenuItem(s.file() + " (skipped: " + s.reason() + ")");
            m.setDisable(true);
            dice.getItems().add(m);
        }

        Menu trayMenu = new Menu("Tray");
        ToggleGroup trayGroup = new ToggleGroup();
        for (AppState.TrayInfo t : AppState.TRAYS) {
            RadioMenuItem r = new RadioMenuItem(t.toString());
            r.setToggleGroup(trayGroup);
            r.setSelected(t == state.tray.get());
            r.setOnAction(e -> state.tray.set(t));
            trayMenu.getItems().add(r);
        }
        trayMenu.getItems().add(new SeparatorMenuItem());
        MenuItem rigHead = new MenuItem("Light Rig");
        rigHead.setDisable(true);
        trayMenu.getItems().add(rigHead);
        ToggleGroup rigGroup = new ToggleGroup();
        for (String rig : AppState.LIGHT_RIGS) {
            RadioMenuItem r = new RadioMenuItem(rig);
            r.setToggleGroup(rigGroup);
            r.setSelected(rig.equals(state.lightRig.get()));
            r.setOnAction(e -> state.lightRig.set(rig));
            trayMenu.getItems().add(r);
        }

        Menu view = new Menu("Quality");
        view.getItems().add(radioGroup("Render scale", List.of("50%", "75%", "100%"),
                (int) (state.renderScale.get() * 100) + "%", v -> state.renderScale.set(Integer.parseInt(v.replace("%", "")) / 100.0)));
        view.getItems().add(radioGroup("Anti-aliasing", List.of("Off", "2×", "4×", "8×"),
                state.msaa.get() == 0 ? "Off" : state.msaa.get() + "×",
                v -> state.msaa.set(v.equals("Off") ? 0 : Integer.parseInt(v.replace("×", "")))));
        view.getItems().add(radioGroup("Shadows", List.of("Low", "Medium", "High"), state.shadowQuality.get(), state.shadowQuality::set));

        Menu sound = new Menu("Sound");
        CheckMenuItem mute = new CheckMenuItem("Mute");
        mute.selectedProperty().bindBidirectional(state.muted);
        Slider vol = new Slider(0, 1, state.volume.get());
        vol.valueProperty().bindBidirectional(state.volume);
        vol.setPrefWidth(160);
        CustomMenuItem volItem = new CustomMenuItem(new HBox(8, new Label("Volume"), vol), false);
        sound.getItems().addAll(mute, volItem);
        return new MenuBar(dice, trayMenu, view, sound);
    }

    private static Menu radioGroup(String title, List<String> values, String current, java.util.function.Consumer<String> set) {
        Menu m = new Menu(title);
        ToggleGroup g = new ToggleGroup();
        for (String v : values) {
            RadioMenuItem r = new RadioMenuItem(v);
            r.setToggleGroup(g);
            r.setSelected(v.equals(current));
            r.setOnAction(e -> set.accept(v));
            m.getItems().add(r);
        }
        return m;
    }

    private Node leftColumn(AppState state) {
        Label title = VariantA.head("Build a Roll");
        GridPane grid = new GridPane(6, 6);
        for (int i = 0; i < KINDS.length; i++) {
            Expr.Kind k = KINDS[i];
            Button b = new Button(k.label);
            b.setPrefSize(64, 48);
            b.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
            b.setTooltip(new Tooltip("Click adds one " + k.label + "; right-click removes one"));
            b.setOnMouseClicked(e -> bump(k, e.getButton() == MouseButton.SECONDARY ? -1 : 1));
            dieButtons.put(k, b);
            grid.add(b, i % 3, i / 3);
        }
        modifier = new Spinner<>(-999, 999, 0);
        modifier.setEditable(true);
        modifier.setPrefWidth(90);
        modifier.valueProperty().addListener((o, a, b) -> rebuild());
        advGroup = new ToggleGroup();
        ToggleButton normal = new ToggleButton("Normal");
        ToggleButton adv = new ToggleButton("Adv");
        ToggleButton dis = new ToggleButton("Dis");
        for (ToggleButton t : List.of(normal, adv, dis)) {
            t.setToggleGroup(advGroup);
            t.setOnAction(e -> {
                if (!t.isSelected()) {
                    t.setSelected(true);
                }
                rebuild();
            });
        }
        normal.setSelected(true);
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            field.clear();
            modifier.getValueFactory().setValue(0);
            normal.setSelected(true);
        });
        Label hint = muted("Typing in the box works too; the buttons then add to what you typed.");
        hint.setWrapText(true);
        VBox builder = new VBox(8, title, grid, new HBox(8, new Label("Modifier"), modifier),
                new HBox(normal, adv, dis), clear, hint);

        Label savedTitle = VariantA.head("Saved Rolls");
        VBox savedBox = new VBox(4);
        Runnable fill = () -> {
            savedBox.getChildren().clear();
            for (AppState.SavedRoll s : state.saved) {
                savedBox.getChildren().add(savedButton(s));
            }
        };
        fill.run();
        ctx.listen(state.saved, (ListChangeListener<AppState.SavedRoll>) c -> fill.run());
        Button add = new Button("★ Save current…");
        add.setOnAction(e -> Ui.editSavedRoll(ctx.stage, state, null, field.getText()));
        ScrollPane scroll = new ScrollPane(savedBox);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox left = new VBox(12, builder, savedTitle, scroll, add);
        left.setPadding(new Insets(12));
        left.setPrefWidth(240);
        left.setStyle("-fx-background-color: white; -fx-border-color: transparent #d5d9de transparent transparent;");
        return left;
    }

    private Node savedButton(AppState.SavedRoll s) {
        Expr.ParseError err = s.error();
        Label name = new Label((err != null ? "⚠ " : "") + s.name.get());
        name.setStyle("-fx-font-weight: bold;");
        Label expr = new Label(s.expression.get());
        expr.setFont(Ui.MONO_SMALL);
        Button b = new Button();
        b.setGraphic(new VBox(name, expr));
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setTooltip(new Tooltip(err == null ? "Click to roll · right-click to edit, move or delete"
                : "Doesn't parse any more: " + err.message() + "\nClick to edit it."));
        if (err != null) {
            b.setStyle("-fx-base: #ffe0b2;");
        }
        b.disableProperty().bind(roller.inputLocked());
        b.setOnAction(e -> {
            if (err != null) {
                Ui.editSavedRoll(ctx.stage, ctx.state, s, null);
            } else {
                field.setText(s.expression.get());
                roll(s.name.get());
            }
        });
        MenuItem edit = new MenuItem("Edit…");
        edit.setOnAction(e -> Ui.editSavedRoll(ctx.stage, ctx.state, s, null));
        MenuItem up = new MenuItem("Move up");
        up.setOnAction(e -> move(s, -1));
        MenuItem down = new MenuItem("Move down");
        down.setOnAction(e -> move(s, 1));
        MenuItem del = new MenuItem("Delete…");
        del.setOnAction(e -> {
            if (Ui.confirm(ctx.stage, "Delete \"" + s.name.get() + "\"?", "The Saved Roll " + s.expression.get()
                    + " is removed. Past Rolls in the history keep its name.", "Delete")) {
                ctx.state.saved.remove(s);
            }
        });
        b.setContextMenu(new ContextMenu(edit, up, down, new SeparatorMenuItem(), del));
        return b;
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

    private Node centerColumn() {
        field = new TextField();
        field.setFont(BIG);
        field.setPromptText("Roll Expression");
        HBox.setHgrow(field, Priority.ALWAYS);
        field.disableProperty().bind(roller.inputLocked());
        field.setOnAction(e -> roll(null));
        field.textProperty().addListener((o, a, b) -> {
            clearError();
            if (!building) {
                syncBuilder();
            }
        });

        Button rollBtn = new Button();
        rollBtn.setPrefSize(130, 40);
        // While dice move, the button means Roll in the interrupt and queue modes, else it is the skip control.
        BooleanBinding rollsWhileBusy = ctx.beh.input.isEqualTo(Behaviour.Input.INTERRUPT)
                .or(ctx.beh.input.isEqualTo(Behaviour.Input.QUEUE));
        rollBtn.textProperty().bind(Bindings.createStringBinding(() -> {
            if (!tray.isBusy()) {
                return "Roll  ⏎";
            }
            if (rollsWhileBusy.get()) {
                return ctx.beh.input.get() == Behaviour.Input.QUEUE ? "Queue  ⏎" : "Roll now  ⏎";
            }
            return switch (ctx.beh.skip.get()) {
                case SKIP_ALL -> "Skip to result";
                case SKIP_THROW -> "Skip Throw  ▸▸";
                case FAST_FORWARD -> "Hold: ×4";
                case NONE -> "Rolling…";
            };
        }, tray.busyProperty(), ctx.beh.skip, rollsWhileBusy));
        rollBtn.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-base: #3d7bd9;");
        rollBtn.setOnAction(e -> {
            if (tray.isBusy() && !rollsWhileBusy.get()) {
                tray.skipByMode();
            } else {
                roll(null);
            }
        });
        rollBtn.setOnMousePressed(e -> {
            if (tray.isBusy() && !rollsWhileBusy.get() && ctx.beh.skip.get() == Behaviour.Skip.FAST_FORWARD) {
                tray.setFastForwardKey(true);
            }
        });
        rollBtn.setOnMouseReleased(e -> tray.setFastForwardKey(false));
        rollBtn.disableProperty().bind(tray.busyProperty().and(rollsWhileBusy.not())
                .and(ctx.beh.skip.isEqualTo(Behaviour.Skip.NONE)));

        echo = new Label();
        echo.setFont(BIG);
        carets = new Label();
        carets.setFont(BIG);
        carets.setStyle("-fx-text-fill: #c62828; -fx-font-weight: bold;");
        message = new Label();
        message.setStyle("-fx-text-fill: #c62828; -fx-font-size: 13px;");
        VBox err = new VBox(0, echo, carets, message);
        err.setPadding(new Insets(0, 0, 0, 8));

        Label queued = new Label();
        queued.textProperty().bind(Bindings.createStringBinding(() -> roller.queued.get() == null ? ""
                : "Queued next: " + roller.queued.get().text(), roller.queued));
        queued.setStyle("-fx-text-fill: #8a6d00;");

        HBox entry = new HBox(8, field, rollBtn);
        entry.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(4, entry, err, queued);
        top.setPadding(new Insets(12, 12, 8, 12));
        BorderPane center = new BorderPane(tray);
        center.setTop(top);
        BorderPane.setMargin(tray, new Insets(0, 12, 12, 12));
        return center;
    }

    private Node rightColumn(AppState state) {
        receipt = new VBox(6);
        receipt.getChildren().setAll(muted("Roll something: its Roll Result shows here."));
        receipt.setPadding(new Insets(12));
        receipt.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-color: #d5d9de; -fx-border-radius: 6;");

        Label histTitle = VariantA.head("Roll History");
        Label count = new Label();
        count.textProperty().bind(Bindings.size(state.history).asString("%d of " + AppState.HISTORY_CAP));
        count.setStyle("-fx-text-fill: #6b7280;");
        Button clear = new Button("Clear all…");
        clear.setOnAction(e -> {
            if (Ui.confirm(ctx.stage, "Clear the Roll History?", "All " + state.history.size()
                    + " Rolls are removed. Saved Rolls are not affected.", "Clear all")) {
                state.history.clear();
            }
        });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox histBar = new HBox(8, histTitle, count, grow, clear);
        histBar.setAlignment(Pos.CENTER_LEFT);

        ListView<AppState.HistoryEntry> list = new ListView<>(state.history);
        list.setCellFactory(lv -> new HistoryCell());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                showReceipt(b, true);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox right = new VBox(10, VariantA.head("Roll Result"), receipt, histBar, list);
        right.setPadding(new Insets(12));
        right.setPrefWidth(360);
        right.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: transparent transparent transparent #d5d9de;");
        return right;
    }

    private final class HistoryCell extends ListCell<AppState.HistoryEntry> {
        @Override
        protected void updateItem(AppState.HistoryEntry h, boolean empty) {
            super.updateItem(h, empty);
            if (empty || h == null) {
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            String day = Ui.dayHeader(h.at());
            Label when = new Label(day.equals("Today") ? Ui.clockSeconds(h.at())
                    : (day.equals("Yesterday") ? day : day.substring(0, 3)) + " " + Ui.clock(h.at()));
            when.setFont(Ui.MONO_SMALL);
            when.setStyle("-fx-text-fill: #6b7280;");
            when.setTooltip(new Tooltip(Ui.full(h.at())));
            Label expr = new Label(h.expression());
            expr.setFont(Ui.MONO);
            Label total = new Label(String.valueOf(h.plan().total));
            total.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            Button again = icon("⟳", "Roll again");
            again.disableProperty().bind(roller.inputLocked());
            again.setOnAction(e -> {
                field.setText(h.expression());
                roll(h.savedName());
            });
            Button copy = icon("⧉", "Copy into the expression box");
            copy.setOnAction(e -> field.setText(h.expression()));
            Button save = icon("★", "Save as a Saved Roll…");
            save.setOnAction(e -> Ui.editSavedRoll(ctx.stage, ctx.state, null, h.expression()));
            HBox actions = new HBox(2, again, copy, save);
            // Actions show on hover only, so the list stays a quiet column of times and totals.
            actions.visibleProperty().bind(hoverProperty().or(selectedProperty()));
            actions.managedProperty().bind(actions.visibleProperty());
            HBox line = new HBox(8, when, expr, grow, actions, total);
            line.setAlignment(Pos.CENTER_LEFT);
            // Pin the row to the list's width, or a long expression pushes the total off the right edge.
            line.prefWidthProperty().bind(getListView().widthProperty().subtract(34));
            line.setMaxWidth(Region.USE_PREF_SIZE);
            expr.setMinWidth(0);
            Node body = line;
            if (h.savedName() != null) {
                Label n = new Label(h.savedName());
                n.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
                body = new VBox(0, line, n);
            }
            setGraphic(body);
        }
    }

    private static Button icon(String glyph, String tip) {
        Button b = new Button(glyph);
        b.setTooltip(new Tooltip(tip));
        b.setStyle("-fx-padding: 0 5; -fx-font-size: 12px;");
        b.setFocusTraversable(false);
        return b;
    }

    /** The receipt: one row per term as written, its Dice as chips, its signed subtotal; the total underneath. */
    private void showReceipt(AppState.HistoryEntry e, boolean fromHistory) {
        List<Node> rows = new ArrayList<>();
        Label head = new Label(fromHistory ? "From the history · " + Ui.clock(e.at()) + " (the tray still shows the latest Roll)"
                : e.savedName() != null ? e.savedName() : "Latest Roll");
        head.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
        head.setWrapText(true);
        rows.add(head);
        GridPane g = new GridPane(10, 6);
        int r = 0;
        boolean first = true;
        for (RollPlan.TermResult t : e.plan().terms) {
            Label term = new Label(Ui.signText(t.sign(), first).strip() + " " + t.text()
                    + (t.term() instanceof Expr.Group gr && gr.advantage() != null ? " " + gr.advantage() : ""));
            first = false;
            term.setFont(Ui.MONO_BOLD);
            FlowPane chips = new FlowPane(4, 4);
            chips.setPrefWrapLength(150);
            for (RollPlan.DieResult d : t.dice()) {
                chips.getChildren().add(chip(d));
            }
            Label sub = new Label((t.sign() < 0 ? "−" : "") + t.subtotal());
            sub.setFont(Ui.MONO);
            sub.setMinWidth(40);
            sub.setAlignment(Pos.CENTER_RIGHT);
            GridPane.setHgrow(chips, Priority.ALWAYS);
            g.addRow(r++, term, chips, sub);
        }
        rows.add(g);
        Label total = new Label("Total  " + e.plan().total);
        total.setStyle("-fx-font-size: 30px; -fx-font-weight: bold;");
        HBox totalRow = new HBox(total);
        totalRow.setAlignment(Pos.CENTER_RIGHT);
        totalRow.setStyle("-fx-border-color: #222 transparent transparent transparent; -fx-padding: 4 0 0 0;");
        rows.add(totalRow);
        rows.add(muted("↻ earlier Face Values were rerolled away · grey and struck = not kept"));
        receipt.getChildren().setAll(rows);
    }

    private static Node chip(RollPlan.DieResult d) {
        HBox box = new HBox(2);
        box.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < d.chain().size() - 1; i++) {
            Label old = new Label(d.chain().get(i) + "↻");
            old.setStyle("-fx-text-fill: #b26a00; -fx-font-size: 11px;");
            box.getChildren().add(old);
        }
        Label v = new Label(String.valueOf(d.value()));
        v.setMinWidth(26);
        v.setAlignment(Pos.CENTER);
        v.setStyle(d.kept()
                ? "-fx-background-color: #1f2937; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 2 5; -fx-background-radius: 4;"
                : "-fx-border-color: #9ca3af; -fx-border-style: dashed; -fx-text-fill: #9ca3af; -fx-padding: 1 4; -fx-border-radius: 4;");
        if (!d.kept()) {
            v.setText("~" + d.value() + "~");
        }
        Tooltip.install(v, new Tooltip(d.kind().label + (d.rerolled() ? ", rerolled: " + d.chain() : "")
                + (d.kept() ? "" : ", not kept")));
        box.getChildren().add(v);
        return box;
    }

    private void roll(String savedName) {
        Expr.ParseError e = roller.submit(field.getText(), savedName);
        if (e != null) {
            echo.setText(field.getText());
            carets.setText(Ui.caretLine(e));
            message.setText(e.message());
        }
    }

    private void clearError() {
        echo.setText("");
        carets.setText("");
        message.setText("");
    }

    /** Die button click: bump that Die Kind's simple Dice Group in place, or append a new term. */
    private void bump(Expr.Kind kind, int by) {
        Expr.Result r = Expr.parse(field.getText());
        String text = field.getText().strip();
        if (r.ok() != null) {
            Map<Expr.Kind, Integer> counts = simpleCounts(r.ok());
            if (counts != null) {
                counts.merge(kind, by, Integer::sum);
                if (counts.get(kind) <= 0) {
                    counts.remove(kind);
                }
                write(counts);
                return;
            }
        }
        if (by > 0) {
            setField(text.isEmpty() ? "1" + kind.label : text + "+1" + kind.label);
        }
    }

    /** Counts per kind if the expression is only plain Dice Groups and Modifiers, else null. */
    private static Map<Expr.Kind, Integer> simpleCounts(Expr.Parsed p) {
        Map<Expr.Kind, Integer> counts = new EnumMap<>(Expr.Kind.class);
        for (Expr.Term t : p.terms()) {
            if (t instanceof Expr.Group g) {
                if (g.sign() < 0 || g.reroll() != null || (g.keepHighest() != null && g.advantage() == null)) {
                    return null;
                }
                counts.merge(g.kind(), g.advantage() != null ? 1 : g.count(), Integer::sum);
            }
        }
        return counts;
    }

    private void rebuild() {
        Expr.Result r = Expr.parse(field.getText());
        Map<Expr.Kind, Integer> counts = r.ok() == null ? null : simpleCounts(r.ok());
        if (counts == null && !field.getText().isBlank()) {
            return;
        }
        write(counts == null ? new EnumMap<>(Expr.Kind.class) : counts);
    }

    private void write(Map<Expr.Kind, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (Expr.Kind k : KINDS) {
            Integer c = counts.get(k);
            if (c != null && c > 0) {
                if (!sb.isEmpty()) {
                    sb.append('+');
                }
                sb.append(c).append(k.label);
            }
        }
        int m = modifier.getValue() == null ? 0 : modifier.getValue();
        if (m != 0 && !sb.isEmpty()) {
            sb.append(m > 0 ? "+" : "-").append(Math.abs(m));
        }
        String adv = ((ToggleButton) advGroup.getSelectedToggle()).getText();
        if (!adv.equals("Normal") && !sb.isEmpty()) {
            sb.append(' ').append(adv.toLowerCase());
        }
        setField(sb.toString());
    }

    private void setField(String s) {
        building = true;
        field.setText(s);
        field.end();
        building = false;
        syncBuilder();
    }

    /** Shows each die button's count from whatever the box holds. */
    private void syncBuilder() {
        Expr.Result r = Expr.parse(field.getText());
        Map<Expr.Kind, Integer> counts = new EnumMap<>(Expr.Kind.class);
        if (r.ok() != null) {
            for (Expr.Term t : r.ok().terms()) {
                if (t instanceof Expr.Group g) {
                    counts.merge(g.kind(), g.advantage() != null ? 1 : g.count(), Integer::sum);
                }
            }
        }
        for (Expr.Kind k : KINDS) {
            Integer c = counts.get(k);
            dieButtons.get(k).setText(c == null ? k.label : k.label + "\n×" + c);
        }
    }

    private void banner(AppState.Notice n) {
        Label text = new Label("ⓘ  " + n.title() + ": " + n.detail());
        text.setWrapText(true);
        Button close = icon("✕", "Dismiss");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(8, text, grow, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setStyle("-fx-background-color: #fff4ce; -fx-border-color: transparent transparent #e6d28a transparent;");
        close.setOnAction(e -> {
            banners.getChildren().remove(bar);
            ctx.state.notices.remove(n);
        });
        banners.getChildren().add(bar);
    }

    private static Label muted(String s) {
        Label l = new Label(s);
        l.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
        l.setWrapText(true);
        return l;
    }

    /** This variant's bigger entry box, so the caret line lines up under it character for character. */
    private static final Font BIG = Font.font("Consolas", 18);

    @Override
    public void demoType(String expression) {
        setField(expression);
    }

    @Override
    public void demoRoll() {
        roll(null);
    }

    @Override
    public void demoPanels() {
        // Everything in B is always on screen; nothing to open.
    }
}
