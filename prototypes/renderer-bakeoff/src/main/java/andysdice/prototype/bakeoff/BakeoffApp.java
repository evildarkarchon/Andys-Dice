package andysdice.prototype.bakeoff;

import com.huskerdev.openglfx.internal.GLInteropType;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * PROTOTYPE. "Renderer bake-off: JavaFX 3D vs embedded renderer d20" (issue #8).
 *
 * <p>Three variants of one scene, switchable from a floating bottom bar or the arrow keys:
 * A = JavaFX 3D, B = openglfx + LWJGL with automatic interop, C = the same with forced Blit
 * (CPU copy). A HUD shows frame rate and renderer diagnostics so each switch surfaces the state.
 */
public final class BakeoffApp extends Application {
    private SceneSpec spec;
    private Textures tex;
    private final List<String> keys = List.of("A", "B", "C");
    private final List<Supplier<Variant>> factories = new ArrayList<>();
    private int current;
    private Variant variant;
    private final java.util.Map<Integer, Variant> built = new java.util.HashMap<>();

    private final StackPane viewport = new StackPane();
    private final Label hud = new Label();
    private final Label pill = new Label();
    private final StackPane root = new StackPane();

    // FX pulse statistics, reset every HUD refresh.
    private long lastPulse, statsWindowStart;
    private int pulsesInWindow;
    private double fxFps, worstFrameMs, worstInWindow;

    @Override
    public void start(Stage stage) {
        long t0 = System.nanoTime();
        tex = new Textures();
        spec = new SceneSpec();
        System.out.printf("textures+mesh built in %.0f ms, font=%s, d20 triangles=%d%n",
                (System.nanoTime() - t0) / 1e6, tex.fontUsed, spec.d20.mesh.triangleCount());
        boolean uncapped = Boolean.getBoolean("bakeoff.uncapped");
        factories.add(() -> new FxVariant(spec, tex));
        factories.add(() -> new GlVariant(spec, tex, "B", null, uncapped));
        factories.add(() -> new GlVariant(spec, tex, "C", GLInteropType.Blit, uncapped));

        hud.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px; -fx-text-fill: #e8e8e8;"
                + "-fx-background-color: rgba(0,0,0,0.55); -fx-padding: 8 10 8 10; -fx-background-radius: 6;");
        hud.setMouseTransparent(true);
        StackPane.setAlignment(hud, Pos.TOP_LEFT);
        StackPane.setMargin(hud, new Insets(10));

        Label help = new Label("<-/->  variant    M  Dice Set look    T  tumble    D  1 / 20 dice    Space  pause orbit    drag  orbit");
        help.setStyle("-fx-font-size: 11px; -fx-text-fill: #bbbbbb; -fx-background-color: rgba(0,0,0,0.45); -fx-padding: 4 8 4 8; -fx-background-radius: 4;");
        help.setMouseTransparent(true);
        StackPane.setAlignment(help, Pos.TOP_RIGHT);
        StackPane.setMargin(help, new Insets(10));

        root.getChildren().addAll(viewport, hud, help, switcher());
        Scene scene = new Scene(root, 1280, 800);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        double[] dragX = {0};
        viewport.setOnMousePressed(e -> dragX[0] = e.getSceneX());
        viewport.setOnMouseDragged(e -> {
            spec.manualYaw += (float) ((e.getSceneX() - dragX[0]) * 0.01);
            dragX[0] = e.getSceneX();
        });

        stage.setTitle("PROTOTYPE - Andy's Dice renderer bake-off");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            built.values().forEach(Variant::dispose);
        });
        stage.show();
        activate(Math.max(0, keys.indexOf(System.getProperty("bakeoff.variant", "A").toUpperCase())));

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                onPulse(now);
            }
        }.start();

        String capture = System.getProperty("bakeoff.capture", "");
        if (!capture.isBlank()) {
            // The Robot grabs whatever is on screen, so keep this window above everything else.
            stage.setAlwaysOnTop(true);
            stage.toFront();
            startCapture(Path.of(capture));
        }
    }

    /** The floating, obviously-not-the-design pill at the bottom centre. */
    private HBox switcher() {
        Button prev = new Button("<"), next = new Button(">");
        prev.setFocusTraversable(false);
        next.setFocusTraversable(false);
        prev.setOnAction(e -> activate((current + keys.size() - 1) % keys.size()));
        next.setOnAction(e -> activate((current + 1) % keys.size()));
        pill.setStyle("-fx-text-fill: black; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 0 10 0 10;");
        HBox bar = new HBox(6, prev, pill, next);
        bar.setAlignment(Pos.CENTER);
        bar.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        bar.setStyle("-fx-background-color: #ffd23f; -fx-background-radius: 20; -fx-padding: 6 10 6 10;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 12, 0, 0, 3);");
        StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(bar, new Insets(0, 0, 18, 0));
        return bar;
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case LEFT -> activate((current + keys.size() - 1) % keys.size());
            case RIGHT -> activate((current + 1) % keys.size());
            case M -> spec.nextLook();
            case T -> spec.tumbling = !spec.tumbling;
            case D -> spec.twenty = !spec.twenty;
            case SPACE -> spec.orbiting = !spec.orbiting;
            default -> {
                return;
            }
        }
        e.consume();
    }

    /**
     * Shows variant {@code index}, building it on first use. Variants are cached rather than disposed on
     * every switch: disposing one openglfx canvas and creating the next on the FX thread hung the app
     * (Windows "AppHangB1") in an unattended run, and v1 would hold one long-lived canvas anyway.
     */
    private void activate(int index) {
        viewport.getChildren().clear();
        current = index;
        variant = built.computeIfAbsent(index, i -> factories.get(i).get());
        viewport.getChildren().add(variant.node());
        pill.setText(variant.key() + "  " + variant.title());
        worstFrameMs = 0;
    }

    private void onPulse(long now) {
        variant.update();
        if (lastPulse != 0) worstInWindow = Math.max(worstInWindow, (now - lastPulse) / 1e6);
        lastPulse = now;
        pulsesInWindow++;
        if (statsWindowStart == 0) statsWindowStart = now;
        double window = (now - statsWindowStart) / 1e9;
        if (window >= 0.5) {
            fxFps = pulsesInWindow / window;
            worstFrameMs = worstInWindow;
            pulsesInWindow = 0;
            worstInWindow = 0;
            statsWindowStart = now;
            hud.setText(hudText());
        }
    }

    private String hudText() {
        double scale = root.getScene().getWindow().getOutputScaleX();
        return String.format("""
                        %s  %s
                        JavaFX pulses/s: %.1f   worst pulse gap: %.1f ms   (vsync %s)
                        %s
                        viewport %dx%d px (scale %.2f)   dice: %d   %s   orbit %s
                        Dice Set: %s
                        d20: %d triangles, numeral font %s""",
                variant.key(), variant.title(),
                fxFps, worstFrameMs, Boolean.getBoolean("bakeoff.uncapped") ? "off" : "on",
                variant.stats(),
                Math.round(viewport.getWidth() * scale), Math.round(viewport.getHeight() * scale), scale,
                spec.twenty ? 20 : 1, spec.tumbling ? "tumbling" : "at rest", spec.orbiting ? "on" : "off",
                spec.look().name(),
                spec.d20.mesh.triangleCount(), tex.fontUsed);
    }

    /**
     * Unattended run for side-by-side screenshots: for each variant and scenario, freeze the clock at
     * the same instant, let frame stats settle, capture the window, then exit. Runs off the FX thread
     * because the AWT Robot must not block JavaFX while it waits.
     */
    private void startCapture(Path dir) {
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(dir);
                Robot robot = new Robot();
                StringBuilder log = new StringBuilder();
                String[][] scenarios = {{"single-rest", "0", "0"}, {"single-tumble", "1", "0"}, {"twenty-rest", "0", "1"}, {"twenty-tumble", "1", "1"}};
                for (int look = 0; look < Look.ALL.size(); look++) {
                    for (int v = 0; v < keys.size(); v++) {
                        for (String[] s : scenarios) {
                            if (look > 0 && !s[0].equals("single-rest")) continue;
                            final int vi = v, li = look;
                            onFx(() -> {
                                spec.tumbling = s[1].equals("1");
                                spec.twenty = s[2].equals("1");
                                spec.orbiting = true;
                                spec.freezeAt(3.2);
                                if (spec.lookIndex != li) {
                                    spec.lookIndex = li;
                                    spec.lookVersion++;
                                }
                                if (current != vi || variant == null) activate(vi);
                            });
                            Thread.sleep(3500);
                            String[] text = new String[1];
                            Bounds[] b = new Bounds[1];
                            onFx(() -> {
                                text[0] = hudText();
                                b[0] = root.localToScreen(root.getBoundsInLocal());
                            });
                            Rectangle r = new Rectangle((int) b[0].getMinX(), (int) b[0].getMinY(), (int) b[0].getWidth(), (int) b[0].getHeight());
                            MultiResolutionImage mri = robot.createMultiResolutionScreenCapture(r);
                            List<java.awt.Image> variants = mri.getResolutionVariants();
                            BufferedImage img = (BufferedImage) variants.get(variants.size() - 1);
                            String name = keys.get(v) + "-" + s[0] + "-look" + look + ".png";
                            ImageIO.write(img, "png", dir.resolve(name).toFile());
                            log.append("== ").append(name).append('\n').append(text[0]).append("\n\n");
                            System.out.println("captured " + name);
                        }
                    }
                }
                Files.writeString(dir.resolve("stats.txt"), log);
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                Platform.runLater(() -> {
                    built.values().forEach(Variant::dispose);
                    Platform.exit();
                });
            }
        }, "capture");
        t.setDaemon(true);
        t.start();
    }

    private static void onFx(Runnable r) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        done.await();
    }
}
