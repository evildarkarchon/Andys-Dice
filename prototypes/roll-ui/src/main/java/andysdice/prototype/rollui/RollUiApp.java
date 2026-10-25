package andysdice.prototype.rollui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;

/**
 * PROTOTYPE entry point: three radically different Roll UIs around one placeholder tray, switchable from a
 * floating bar (or the Left/Right keys when no text box has focus). The bar also overrides the behaviour
 * dimensions (skip, input while dice move, camera) so any behaviour can be tried on any layout.
 * {@code -Drollui.variant=B} starts on a variant; {@code -Drollui.capture=dir} runs an unattended screenshot
 * tour of all three and exits.
 */
public final class RollUiApp extends Application {

    private final List<Supplier<Variant>> variants = List.of(VariantA::new, VariantB::new, VariantC::new);
    private final StackPane root = new StackPane();
    private AppState state;
    private Behaviour beh;
    private Stage stage;
    private Variant current;
    private Variant.Ctx ctx;
    private int index;
    private Label variantLabel;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        state = new AppState();
        beh = new Behaviour();
        String start = System.getProperty("rollui.variant", "A").trim().toUpperCase();
        index = Math.max(0, "ABC".indexOf(start.isEmpty() ? "A" : start.substring(0, 1)));

        root.getChildren().add(new Region());
        Node bar = switcher();
        root.getChildren().add(bar);
        StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(bar, new Insets(0, 0, 8, 0));

        Scene scene = new Scene(root, 1440, 900);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if ((e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) && !typingIn(scene.getFocusOwner())) {
                show(index + (e.getCode() == KeyCode.LEFT ? -1 : 1));
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE && current.tray().isBusy()) {
                current.tray().skipByMode();
                e.consume();
            } else if (e.getCode() == KeyCode.SHIFT) {
                current.tray().setFastForwardKey(true);
            }
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.SHIFT) {
                current.tray().setFastForwardKey(false);
            }
        });
        stage.setScene(scene);
        show(index);
        stage.show();

        String capture = System.getProperty("rollui.capture", "").trim();
        if (!capture.isEmpty()) {
            captureTour(new File(capture));
        }
    }

    /** Arrow keys belong to whatever the player is typing in or adjusting, never to the switcher. */
    private static boolean typingIn(Node focus) {
        return focus instanceof TextInputControl || focus instanceof ComboBoxBase<?> || focus instanceof Spinner<?>
                || focus instanceof Slider;
    }

    private void show(int i) {
        index = Math.floorMod(i, variants.size());
        if (ctx != null) {
            ctx.dispose();
        }
        current = variants.get(index).get();
        current.applyDefaults(beh);
        ctx = new Variant.Ctx(state, beh, stage);
        root.getChildren().set(0, current.build(ctx));
        variantLabel.setText(current.key() + "  ·  " + current.name());
        stage.setTitle("PROTOTYPE · Andy's Dice Roll UI · " + current.key() + " (" + current.name() + ")");
    }

    private Node switcher() {
        Button prev = new Button("◀");
        Button next = new Button("▶");
        prev.setOnAction(e -> show(index - 1));
        next.setOnAction(e -> show(index + 1));
        variantLabel = new Label();
        variantLabel.setMinWidth(230);
        variantLabel.setAlignment(Pos.CENTER);
        variantLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;");
        ComboBox<Behaviour.Skip> skip = combo(Behaviour.Skip.values());
        skip.valueProperty().bindBidirectional(beh.skip);
        ComboBox<Behaviour.Input> input = combo(Behaviour.Input.values());
        input.valueProperty().bindBidirectional(beh.input);
        ComboBox<Behaviour.Camera> camera = combo(Behaviour.Camera.values());
        camera.valueProperty().bindBidirectional(beh.camera);
        Button notice = new Button("+ notice");
        notice.setTooltip(new Tooltip("Raise a one-time notice, as when a file was set aside at start-up"));
        notice.setOnAction(e -> state.notices.add(new AppState.Notice("A file was set aside",
                "history.txt couldn't be read, so it was renamed history.txt.bad-20261024T143200 and starts fresh.")));
        Button defaults = new Button("variant defaults");
        defaults.setOnAction(e -> current.applyDefaults(beh));
        HBox bar = new HBox(8, prev, variantLabel, next, new Separator(javafx.geometry.Orientation.VERTICAL),
                tag("Skip"), skip, tag("Input"), input, tag("Camera"), camera,
                new Separator(javafx.geometry.Orientation.VERTICAL), notice, defaults);
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(6, 14, 6, 14));
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        bar.setStyle(VariantA.DARK + " -fx-background-color: #0b0c0e; -fx-background-radius: 22;"
                + " -fx-border-color: #ff00aa; -fx-border-radius: 22; -fx-border-width: 1.5;"
                + " -fx-effect: dropshadow(gaussian, #000a, 14, 0.2, 0, 3);");
        return bar;
    }

    private static <T> ComboBox<T> combo(T[] values) {
        ComboBox<T> c = new ComboBox<>();
        c.getItems().setAll(values);
        c.setFocusTraversable(false);
        return c;
    }

    private static Label tag(String s) {
        Label l = new Label(s);
        l.setStyle("-fx-text-fill: #ff7ad1; -fx-font-size: 11px;");
        return l;
    }

    /** Unattended tour: each variant at start, mid-Throw, mid-pickup, at rest, with panels open, and with an error. */
    private void captureTour(File dir) {
        dir.mkdirs();
        String expression = "4d6rr1kh3+1d20+5 adv";
        long seed = seedWithReroll(expression);
        List<Object[]> steps = new ArrayList<>();
        for (int v = 0; v < variants.size(); v++) {
            final int vi = v;
            String k = "ABC".substring(v, v + 1);
            steps.add(new Object[] {0.2, (Runnable) () -> show(vi)});
            steps.add(new Object[] {1.0, (Runnable) () -> shot(dir, k + "-1-start")});
            steps.add(new Object[] {0.1, (Runnable) () -> {
                state.rng = new SplittableRandom(seed);
                current.demoType(expression);
                current.demoRoll();
            }});
            steps.add(new Object[] {0.4, (Runnable) () -> shot(dir, k + "-2-throw")});
            steps.add(new Object[] {0.1, (Runnable) () -> current.tray().seek(1.9)});
            steps.add(new Object[] {0.15, (Runnable) () -> shot(dir, k + "-3-pickup-glow")});
            steps.add(new Object[] {0.1, (Runnable) () -> current.tray().seek(99)});
            steps.add(new Object[] {0.6, (Runnable) () -> shot(dir, k + "-4-result")});
            steps.add(new Object[] {0.1, (Runnable) () -> current.demoPanels()});
            steps.add(new Object[] {0.5, (Runnable) () -> shot(dir, k + "-5-panels")});
            steps.add(new Object[] {0.1, (Runnable) () -> {
                current.demoType("2d6+1d7kh1");
                current.demoRoll();
            }});
            steps.add(new Object[] {0.6, (Runnable) () -> shot(dir, k + "-6-error")});
        }
        steps.add(new Object[] {0.2, (Runnable) Platform::exit});
        runSteps(steps, 0);
    }

    private void runSteps(List<Object[]> steps, int i) {
        if (i >= steps.size()) {
            return;
        }
        PauseTransition p = new PauseTransition(Duration.seconds((Double) steps.get(i)[0]));
        p.setOnFinished(e -> {
            ((Runnable) steps.get(i)[1]).run();
            runSteps(steps, i + 1);
        });
        p.play();
    }

    /** A seed whose Roll needs at least one Reroll Throw, so the tour shows the pickup and a chain. */
    private static long seedWithReroll(String expression) {
        Expr.Parsed p = Expr.parse(expression).ok();
        for (long s = 1; ; s++) {
            try {
                RollPlan plan = RollPlan.roll(p, new SplittableRandom(s));
                if (plan.throwCount() >= 2) {
                    return s;
                }
            } catch (RollPlan.RollFailed e) {
                // Can't happen for this expression; try the next seed anyway.
            }
        }
    }

    private void shot(File dir, String name) {
        save(stage.getScene().snapshot(null), new File(dir, name + ".png"));
        int n = 0;
        // Popups (C's error, settings popovers) are separate windows, so they get their own files.
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w instanceof PopupWindow pw && pw.isShowing() && pw.getScene() != null && !(pw instanceof javafx.scene.control.ContextMenu)
                    && !(pw instanceof javafx.scene.control.Tooltip)) {
                save(pw.getScene().snapshot(null), new File(dir, name + "-popup" + (++n) + ".png"));
            }
        }
    }

    private static void save(WritableImage img, File f) {
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
        } catch (IOException e) {
            System.err.println("capture failed: " + f + ": " + e);
        }
    }
}
