package andysdice.prototype.materials;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * PROTOTYPE. "How are Dice Set materials defined, and which Dice Sets ship in v1?" (issue #16).
 *
 * <p>The candidate catalogue is a folder of {@code .diceset} files ({@code dicesets/} next to the pom),
 * read at start-up and again on {@code R}, so a Dice Set can be edited in any text editor while the app
 * runs. The floating switcher (or the arrow keys) flips how translucent Dice Sets are drawn; letter keys
 * flip the rest. The HUD shows the full current state, and the current Dice Set's file verbatim.
 */
public final class MaterialsApp extends Application {
    /** Where the Dice Set files live, relative to the working directory (the pom's folder under exec:exec). */
    private static final Path SETS_DIR = Path.of(System.getProperty("materials.sets", "dicesets"));

    private final Params params = new Params();
    private final SceneSpec scene = new SceneSpec();
    private final Builds builds = new Builds();
    private GlRenderer renderer;

    private final Label hud = new Label();
    private final Label file = new Label();
    private final Label pill = new Label();
    private final StackPane root = new StackPane();
    private long lastHud;
    private String loadError = "";

    @Override
    public void start(Stage stage) {
        String v = System.getProperty("materials.variant", "C").toUpperCase();
        for (Params.Technique t : Params.Technique.values()) if (t.name().equals(v)) params.technique = t;
        reloadSets();
        renderer = new GlRenderer(scene, params, builds, new TrayTextures());
        builds.request(params.structure());

        String panel = "-fx-font-family: 'Consolas', monospace; -fx-text-fill: #e8e8e8;"
                + "-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 6;";
        hud.setStyle(panel + "-fx-font-size: 12px; -fx-padding: 8 10 8 10;");
        hud.setMouseTransparent(true);
        StackPane.setAlignment(hud, Pos.TOP_LEFT);
        StackPane.setMargin(hud, new Insets(10));

        Label help = new Label("""
                <- ->  translucency technique (A/B/C)
                M / N  next / previous Dice Set    R  reload dicesets/
                C  catalogue    0  whole set    1-7  focus d4 d6 d8 d10 d% d12 d20
                Z  close-up / macro    F  next face up
                K  swirl source    V  per-die variation
                I  ink fill    X  engraving depth
                drag  orbit    wheel  zoom    Space  orbit    T  tumble""");
        help.setStyle(panel + "-fx-font-size: 11px; -fx-text-fill: #bbbbbb; -fx-padding: 6 8 6 8;");
        file.setStyle(panel + "-fx-font-size: 11px; -fx-padding: 6 8 6 8;");
        VBox right = new VBox(8, help, file);
        right.setMouseTransparent(true);
        right.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);
        StackPane.setAlignment(right, Pos.TOP_RIGHT);
        StackPane.setMargin(right, new Insets(10));

        root.getChildren().addAll(renderer.canvas, hud, right, switcher());
        Scene fxScene = new Scene(root, 1280, 800);
        fxScene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        double[] drag = {0, 0};
        renderer.canvas.setOnMousePressed(e -> {
            drag[0] = e.getSceneX();
            drag[1] = e.getSceneY();
        });
        renderer.canvas.setOnMouseDragged(e -> {
            scene.yaw += (float) ((e.getSceneX() - drag[0]) * 0.01);
            scene.pitch = (float) Math.clamp(scene.pitch + (e.getSceneY() - drag[1]) * 0.006, Math.toRadians(4), Math.toRadians(88));
            drag[0] = e.getSceneX();
            drag[1] = e.getSceneY();
        });
        renderer.canvas.setOnScroll(e -> scene.zoom = (float) Math.clamp(scene.zoom * Math.pow(1.1, -e.getDeltaY() / 40), 0.3, 4));

        stage.setTitle("PROTOTYPE - Andy's Dice Dice Set materials");
        stage.setScene(fxScene);
        stage.setOnCloseRequest(e -> renderer.canvas.dispose());
        stage.show();
        refreshPill();

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                builds.request(params.structure());
                if (now - lastHud > 250_000_000L) {
                    lastHud = now;
                    refreshHud();
                }
            }
        }.start();

        String capture = System.getProperty("materials.capture", "");
        if (!capture.isBlank()) startCapture(Path.of(capture));
    }

    /** Re-reads every Dice Set file; the renderer notices the new list and re-bakes the swirl volumes. */
    private void reloadSets() {
        try {
            List<DiceSet> sets = DiceSet.loadAll(SETS_DIR);
            loadError = sets.isEmpty() ? "no .diceset files in " + SETS_DIR.toAbsolutePath() : "";
            scene.sets = sets;
            System.out.println("loaded " + sets.size() + " Dice Sets from " + SETS_DIR.toAbsolutePath());
        } catch (IOException ex) {
            loadError = "reload failed: " + ex;
        }
    }

    /** The floating, obviously-not-the-design pill at the bottom centre. */
    private HBox switcher() {
        Button prev = new Button("<"), next = new Button(">");
        prev.setFocusTraversable(false);
        next.setFocusTraversable(false);
        prev.setOnAction(e -> cycleTechnique(-1));
        next.setOnAction(e -> cycleTechnique(1));
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

    private void cycleTechnique(int step) {
        Params.Technique[] all = Params.Technique.values();
        params.technique = all[(params.technique.ordinal() + step + all.length) % all.length];
        refreshPill();
    }

    private void refreshPill() {
        pill.setText(params.technique.name() + "  " + params.technique.label);
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case LEFT -> cycleTechnique(-1);
            case RIGHT -> cycleTechnique(1);
            case C -> scene.focus = SceneSpec.CATALOGUE;
            case DIGIT0, NUMPAD0 -> focus(SceneSpec.WHOLE_SET);
            case DIGIT1, NUMPAD1 -> focus(0);
            case DIGIT2, NUMPAD2 -> focus(1);
            case DIGIT3, NUMPAD3 -> focus(2);
            case DIGIT4, NUMPAD4 -> focus(3);
            case DIGIT5, NUMPAD5 -> focus(4);
            case DIGIT6, NUMPAD6 -> focus(5);
            case DIGIT7, NUMPAD7 -> focus(6);
            case M -> scene.setIndex = Math.floorMod(scene.setIndex + 1, Math.max(1, scene.sets.size()));
            case N -> scene.setIndex = Math.floorMod(scene.setIndex - 1, Math.max(1, scene.sets.size()));
            case R -> reloadSets();
            case Z -> scene.preset = scene.preset == 1 ? 2 : 1;
            case F -> nextFace();
            case K -> params.swirlSource = Params.next(params.swirlSource);
            case V -> params.variation = !params.variation;
            case SPACE -> scene.orbiting = !scene.orbiting;
            case T -> scene.tumbling = !scene.tumbling;
            case X -> params.depthIndex = (params.depthIndex + 1) % Params.DEPTHS.length;
            case I -> params.fillIndex = (params.fillIndex + 1) % Params.FILLS.length;
            default -> {
                return;
            }
        }
        e.consume();
        refreshHud();
    }

    private void focus(int kind) {
        // Leaving the catalogue shows the seven kinds in the current Dice Set.
        scene.focus = kind;
        scene.zoom = 1;
    }

    /** Shows the next face on the focused die, or on every die in the whole-set view. */
    private void nextFace() {
        for (int k = 0; k < scene.upIndex.length; k++) if (scene.focus < 0 || scene.focus == k) scene.upIndex[k]++;
    }

    private void refreshHud() {
        hud.setText(hudText());
        DiceSet s = scene.set();
        if (s == null) {
            file.setText(loadError);
            return;
        }
        StringBuilder t = new StringBuilder(s.file()).append('\n').append("-".repeat(s.file().length())).append('\n');
        t.append(s.source().strip());
        if (!s.warnings().isEmpty()) t.append("\n\nWARNINGS:\n  ").append(String.join("\n  ", s.warnings()));
        file.setText(t.toString());
    }

    private String hudText() {
        Builds.Built b = builds.latest();
        DiceSet s = scene.set();
        StringBuilder t = new StringBuilder();
        t.append(params.technique.name()).append("  ").append(params.technique.label).append('\n');
        t.append(renderer.stats()).append('\n');
        t.append("view: ").append(scene.viewName()).append('\n');
        if (s != null) {
            t.append(String.format("Dice Set %d/%d: %s  (%s)%n", Math.floorMod(scene.setIndex, scene.sets.size()) + 1, scene.sets.size(),
                    s.name(), s.summary()));
        }
        t.append("swirl: ").append(params.swirlSource.label).append('\n');
        t.append("per-die variation: ").append(params.variation ? "on" : "off (every die shows the same pattern)").append('\n');
        t.append(String.format("engraving depth %.2f mm, ink fill %s (visible depth %.2f mm)%n",
                Params.DEPTHS[params.depthIndex], Params.FILL_NAMES[params.fillIndex], params.visibleDepth()));
        t.append(renderer.bakeInfo);
        if (b != null) t.append("\nbuilt v").append(b.st().version()).append(" in ").append(b.millis()).append(" ms");
        if (!builds.status.isEmpty()) t.append("   ").append(builds.status);
        if (!loadError.isEmpty()) t.append('\n').append(loadError);
        return t.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Unattended capture: a scripted tour that saves one PNG per state, then exits.

    /** One scripted state: a file name, a caption, and how to set it up. */
    private record Shot(String name, String caption, Runnable setup) {
    }

    private void resetState() {
        params.technique = Params.Technique.C;
        params.swirlSource = Params.SwirlSource.SHADER;
        params.variation = true;
        params.depthIndex = 1;
        params.fillIndex = 0;
        scene.setIndex = 0;
        scene.tumbling = false;
        scene.orbiting = false;
        scene.zoom = 1;
        scene.yaw = 0f;
        scene.pitch = (float) Math.toRadians(38);
        scene.focus = SceneSpec.CATALOGUE;
        scene.preset = 1;
        scene.resetFaces();
    }

    /** Index of the Dice Set whose file name starts with {@code prefix}, or -1. */
    private int set(String prefix) {
        List<DiceSet> all = scene.sets;
        for (int i = 0; i < all.size(); i++) if (all.get(i).file().startsWith(prefix)) return i;
        return -1;
    }

    private List<Shot> shots() {
        List<Shot> out = new ArrayList<>();
        for (Params.Technique t : Params.Technique.values()) {
            out.add(new Shot("01-catalogue-" + t.name(), "catalogue, technique " + t.name() + ": " + t.label, () -> params.technique = t));
        }
        List<DiceSet> all = scene.sets;
        for (int i = 0; i < all.size(); i++) {
            int ii = i;
            String stem = all.get(i).file().replace(".diceset", "");
            out.add(new Shot("02-set-" + stem, "whole set: " + all.get(i).name(), () -> {
                scene.setIndex = ii;
                scene.focus = SceneSpec.WHOLE_SET;
            }));
        }
        for (String p : new String[]{"11", "12", "13", "14"}) {
            int si = set(p);
            if (si < 0) continue;
            for (Params.Technique t : Params.Technique.values()) {
                out.add(new Shot("03-close-d20-" + all.get(si).file().replace(".diceset", "") + "-" + t.name(),
                        all.get(si).name() + " d20 close-up, technique " + t.name(), () -> {
                    scene.setIndex = si;
                    scene.focus = DieKind.D20.ordinal();
                    scene.pitch = (float) Math.toRadians(30);
                    params.technique = t;
                }));
            }
        }
        for (String p : new String[]{"03", "04", "05", "06", "07", "08", "09"}) {
            int si = set(p);
            if (si < 0) continue;
            for (int k : new int[]{DieKind.D20.ordinal(), DieKind.D6.ordinal()}) {
                out.add(new Shot("04-close-" + DieKind.values()[k].label + "-" + all.get(si).file().replace(".diceset", ""),
                        all.get(si).name() + " " + DieKind.values()[k].label + " close-up", () -> {
                    scene.setIndex = si;
                    scene.focus = k;
                    scene.pitch = (float) Math.toRadians(32);
                }));
            }
        }
        int vortex = set("04");
        for (boolean on : new boolean[]{true, false}) {
            out.add(new Shot("05-variation-" + (on ? "on" : "off"), "per-die variation " + (on ? "on" : "off") + " (Vortex)", () -> {
                scene.setIndex = Math.max(0, vortex);
                scene.focus = SceneSpec.WHOLE_SET;
                params.variation = on;
            }));
        }
        // Cost: the worst case is a translucent swirl filling the frame (macro), and the full catalogue.
        int nebula = set("14");
        for (Params.SwirlSource src : Params.SwirlSource.values()) {
            for (Params.Technique t : Params.Technique.values()) {
                out.add(new Shot("06-cost-macro-nebula-" + t.name() + "-" + src.name(), "Nebula d20 macro, " + t.name() + ", swirl " + src.name(), () -> {
                    scene.setIndex = Math.max(0, nebula);
                    scene.focus = DieKind.D20.ordinal();
                    scene.preset = 2;
                    params.technique = t;
                    params.swirlSource = src;
                }));
            }
            out.add(new Shot("06-cost-catalogue-C-" + src.name(), "catalogue, C, swirl " + src.name(), () -> params.swirlSource = src));
        }
        int crimson = set("03");
        for (int fill = 0; fill < 3; fill += 2) {
            int ff = fill;
            out.add(new Shot("07-ink-" + Params.FILL_NAMES[fill] + "-crimson", "Crimson gold ink, fill " + Params.FILL_NAMES[fill] + ", d20 macro", () -> {
                scene.setIndex = Math.max(0, crimson);
                scene.focus = DieKind.D20.ordinal();
                scene.preset = 2;
                scene.pitch = (float) Math.toRadians(36);
                params.fillIndex = ff;
            }));
        }
        return out;
    }

    /**
     * Walks the shot list off the FX thread: set each state on the FX thread, let the renderer save a
     * settled frame (with its GPU time), then exit.
     */
    private void startCapture(Path dir) {
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(dir);
                // -Dmaterials.shots=01,03 limits the tour to shots whose names start with a prefix.
                List<String> only = List.of(System.getProperty("materials.shots", "").split(","));
                for (Shot s : shots()) {
                    if (!only.getFirst().isEmpty() && only.stream().noneMatch(s.name()::startsWith)) continue;
                    int[] version = new int[1];
                    String[] caption = new String[1];
                    onFx(() -> {
                        resetState();
                        s.setup().run();
                        builds.request(params.structure());
                        version[0] = params.structureVersion;
                        caption[0] = s.caption() + "\n" + hudText().lines().skip(2).limit(4).reduce((a, b) -> a + "\n" + b).orElse("");
                    });
                    renderer.capture(dir.resolve(s.name() + ".png"), caption[0], version[0]).get(180, TimeUnit.SECONDS);
                    System.out.println("captured " + s.name());
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                Platform.runLater(() -> {
                    renderer.canvas.dispose();
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
