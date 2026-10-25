package andysdice.prototype.numerals;

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
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * PROTOTYPE. "How are engraved, inked numerals made and laid out on every Die Kind?" (issue #15).
 *
 * <p>The seven Die Kinds on a felt tray, drawn by the bake-off's GL renderer. The floating switcher
 * (or the arrow keys) flips between three engraving techniques; letter keys cycle the other
 * sub-questions (font, size, 6/9 mark, orientation rules, edge radius, depth, ink fill). The HUD
 * always shows the full current state.
 */
public final class NumeralsApp extends Application {
    private final Params params = new Params();
    private final SceneSpec scene = new SceneSpec();
    private final Builds builds = new Builds();
    private GlRenderer renderer;

    private final Label hud = new Label();
    private final Label pill = new Label();
    private final StackPane root = new StackPane();
    private long lastHud;

    @Override
    public void start(Stage stage) {
        String v = System.getProperty("numerals.variant", "A").toUpperCase();
        for (Params.Technique t : Params.Technique.values()) if (t.name().equals(v)) params.technique = t;
        renderer = new GlRenderer(scene, params, builds, new TrayTextures());
        builds.request(params.structure());

        hud.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px; -fx-text-fill: #e8e8e8;"
                + "-fx-background-color: rgba(0,0,0,0.55); -fx-padding: 8 10 8 10; -fx-background-radius: 6;");
        hud.setMouseTransparent(true);
        StackPane.setAlignment(hud, Pos.TOP_LEFT);
        StackPane.setMargin(hud, new Insets(10));

        Label help = new Label("""
                <- ->  technique (A/B/C)
                0  whole set    1-7  focus d4 d6 d8 d10 d% d12 d20
                Z  close-up / macro    N  next face up
                drag  orbit    wheel  zoom    Space  orbit    T  tumble
                M  Dice Set    E  edge radius    X  depth    I  ink fill
                F  font    W  weight    S  size    U  6/9 mark
                O  d12/d20 orientation    P  percentile direction""");
        help.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px; -fx-text-fill: #bbbbbb;"
                + "-fx-background-color: rgba(0,0,0,0.45); -fx-padding: 6 8 6 8; -fx-background-radius: 4;");
        help.setMouseTransparent(true);
        StackPane.setAlignment(help, Pos.TOP_RIGHT);
        StackPane.setMargin(help, new Insets(10));

        root.getChildren().addAll(renderer.canvas, hud, help, switcher());
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

        stage.setTitle("PROTOTYPE - Andy's Dice engraved numerals");
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
                    hud.setText(hudText());
                }
            }
        }.start();

        String capture = System.getProperty("numerals.capture", "");
        if (!capture.isBlank()) startCapture(Path.of(capture));
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
            case DIGIT0, NUMPAD0 -> focus(-1);
            case DIGIT1, NUMPAD1 -> focus(0);
            case DIGIT2, NUMPAD2 -> focus(1);
            case DIGIT3, NUMPAD3 -> focus(2);
            case DIGIT4, NUMPAD4 -> focus(3);
            case DIGIT5, NUMPAD5 -> focus(4);
            case DIGIT6, NUMPAD6 -> focus(5);
            case DIGIT7, NUMPAD7 -> focus(6);
            case Z -> scene.preset = scene.preset == 1 ? 2 : 1;
            case N -> nextFace();
            case SPACE -> scene.orbiting = !scene.orbiting;
            case T -> scene.tumbling = !scene.tumbling;
            case M -> scene.lookIndex = (scene.lookIndex + 1) % Look.ALL.size();
            case X -> params.depthIndex = (params.depthIndex + 1) % Params.DEPTHS.length;
            case I -> params.fillIndex = (params.fillIndex + 1) % Params.FILLS.length;
            case E -> {
                params.edgeIndex = (params.edgeIndex + 1) % Params.EDGE_RADII.length;
                params.changed();
            }
            case F -> {
                params.style = Params.next(params.style);
                params.changed();
            }
            case W -> {
                params.weightIndex = (params.weightIndex + 1) % Params.WEIGHTS.length;
                params.changed();
            }
            case S -> {
                params.sizeIndex = (params.sizeIndex + 1) % Params.SIZES.length;
                params.changed();
            }
            case U -> {
                params.mark = Params.next(params.mark);
                params.changed();
            }
            case O -> {
                params.orient = Params.next(params.orient);
                params.changed();
            }
            case P -> {
                params.percentDir = Params.next(params.percentDir);
                params.changed();
            }
            default -> {
                return;
            }
        }
        e.consume();
        hud.setText(hudText());
    }

    private void focus(int kind) {
        scene.focus = kind;
        scene.zoom = 1;
    }

    /** Shows the next face on the focused die, or on every die in the whole-set view. */
    private void nextFace() {
        for (int k = 0; k < scene.upIndex.length; k++) if (scene.focus < 0 || scene.focus == k) scene.upIndex[k]++;
    }

    private String hudText() {
        Builds.Built b = builds.latest();
        StringBuilder s = new StringBuilder();
        s.append(params.technique.name()).append("  ").append(params.technique.label).append('\n');
        s.append(renderer.stats()).append('\n');
        s.append("view: ").append(scene.viewName()).append("   Dice Set: ").append(scene.look().name()).append('\n');
        s.append(String.format("edge radius %.2f mm   engraving depth %.2f mm   ink fill %s (visible depth %.2f mm)%n",
                Params.EDGE_RADII[params.edgeIndex], Params.DEPTHS[params.depthIndex], Params.FILL_NAMES[params.fillIndex], params.visibleDepth()));
        s.append(String.format("font %s, %s   size %.0f%%   6/9 mark %s%n", params.style.label,
                Params.WEIGHT_NAMES[params.weightIndex], Params.SIZES[params.sizeIndex] * 100, params.mark.label));
        s.append("d12/d20: ").append(params.orient.label).append('\n');
        s.append("d%: ").append(params.percentDir.label).append('\n');
        if (b != null) {
            int f = scene.focus;
            if (f >= 0) {
                DieModel m = b.models().get(f);
                List<String> labels = m.labels();
                String up = labels.get(scene.upIndex[f] % labels.size());
                s.append(String.format("%s showing %s   numeral height %.2f mm   %d triangles (dense %s)%n", m.kind.label, up,
                        m.capHeightMm, m.mesh.triangleCount(), b.dense() == null ? "building" : b.dense().get(f).triangleCount()));
                if (m.kind == DieKind.D12 || m.kind == DieKind.D20 || m.kind == DieKind.D6) {
                    List<String> notes = new ArrayList<>();
                    for (DieModel.FaceLayout l : m.layouts) notes.add(l.label + ": " + l.orientNote.replace("baseline toward ", "->"));
                    s.append(String.join("  ", notes)).append('\n');
                }
            } else {
                StringBuilder h = new StringBuilder("numeral heights:");
                for (DieModel m : b.models()) h.append(String.format("  %s %.1f mm", m.kind.label, m.capHeightMm));
                s.append(h).append('\n');
            }
            s.append("built v").append(b.st().version()).append(" in ").append(b.millis()).append(" ms");
        }
        if (!builds.status.isEmpty()) s.append("   ").append(builds.status);
        return s.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Unattended capture: a scripted tour that saves one PNG per state, then exits.

    /** One scripted state: a file name, a caption, and how to set it up. */
    private record Shot(String name, String caption, Runnable setup) {
    }

    private void resetState() {
        params.technique = Params.Technique.A;
        params.depthIndex = 1;
        params.fillIndex = 1;
        boolean structural = params.edgeIndex != 1 || params.weightIndex != 1 || params.sizeIndex != 3
                || params.style != StrokeFont.Style.CHESSEX || params.mark != StrokeFont.Mark.UNDERLINE
                || params.orient != Params.OrientRule.AWAY_FROM_TOP || params.percentDir != Params.PercentDir.PERP_OUT;
        params.edgeIndex = 1;
        params.weightIndex = 1;
        params.sizeIndex = 3;
        params.style = StrokeFont.Style.CHESSEX;
        params.mark = StrokeFont.Mark.UNDERLINE;
        params.orient = Params.OrientRule.AWAY_FROM_TOP;
        params.percentDir = Params.PercentDir.PERP_OUT;
        if (structural) params.changed();
        scene.lookIndex = 0;
        scene.tumbling = false;
        scene.orbiting = false;
        scene.zoom = 1;
        scene.yaw = 0.35f;
        scene.pitch = (float) Math.toRadians(42);
        scene.focus = -1;
        scene.preset = 1;
        scene.resetFaces();
    }

    private static Runnable all(Runnable... rs) {
        return () -> {
            for (Runnable r : rs) r.run();
        };
    }

    private List<Shot> shots() {
        List<Shot> out = new ArrayList<>();
        String[] kinds = {"d4", "d6", "d8", "d10", "dpct", "d12", "d20"};
        for (Params.Technique t : Params.Technique.values()) {
            out.add(new Shot("01-set-" + t.name(), "whole set, technique " + t.name(), () -> {
                params.technique = t;
                scene.yaw = 0.1f;
            }));
        }
        for (int k = 0; k < 7; k++) {
            int kk = k;
            out.add(new Shot("02-close-" + kinds[k], kinds[k] + " close-up, A", () -> scene.focus = kk));
        }
        for (int k : new int[]{6, 1, 5, 3}) {
            for (Params.Technique t : Params.Technique.values()) {
                out.add(new Shot("03-macro-" + kinds[k] + "-" + t.name(), kinds[k] + " macro, technique " + t.name(), () -> {
                    scene.focus = k;
                    scene.preset = 2;
                    scene.pitch = (float) Math.toRadians(36);
                    params.technique = t;
                }));
            }
        }
        for (int k : new int[]{0, 4}) {
            out.add(new Shot("03-macro-" + kinds[k] + "-A", kinds[k] + " macro, A", () -> {
                scene.focus = k;
                scene.preset = 2;
            }));
        }
        for (Params.OrientRule r : Params.OrientRule.values()) {
            for (int k : new int[]{6, 5}) {
                out.add(new Shot("04-orient-" + kinds[k] + "-" + r.name(), kinds[k] + " orientation: " + r.label, () -> {
                    scene.focus = k;
                    scene.pitch = (float) Math.toRadians(22);
                    params.orient = r;
                    params.changed();
                }));
            }
        }
        for (Params.PercentDir d : Params.PercentDir.values()) {
            out.add(new Shot("05-pct-" + d.name(), "d% numerals: " + d.label, () -> {
                scene.focus = 4;
                scene.pitch = (float) Math.toRadians(30);
                params.percentDir = d;
                params.changed();
            }));
        }
        for (StrokeFont.Mark m : StrokeFont.Mark.values()) {
            out.add(new Shot("06-mark-" + m.name(), "6/9 mark: " + m.label + " (d10 showing 9)", () -> {
                scene.focus = 3;
                params.mark = m;
                params.changed();
            }));
        }
        out.add(new Shot("07-font-plain-d20", "font: plain", all(() -> scene.focus = 6, () -> {
            params.style = StrokeFont.Style.PLAIN;
            params.changed();
        })));
        out.add(new Shot("07-font-plain-d10", "font: plain", all(() -> scene.focus = 3, () -> {
            params.style = StrokeFont.Style.PLAIN;
            params.changed();
        })));
        for (int w = 0; w < 3; w += 2) {
            int ww = w;
            out.add(new Shot("07-weight-" + Params.WEIGHT_NAMES[w], "stroke weight " + Params.WEIGHT_NAMES[w], () -> {
                scene.focus = 6;
                params.weightIndex = ww;
                params.changed();
            }));
        }
        for (int sz : new int[]{1, 4}) {
            out.add(new Shot("07-size-d6-" + Math.round(Params.SIZES[sz] * 100), "numeral size " + Math.round(Params.SIZES[sz] * 100) + "%", () -> {
                scene.focus = 1;
                params.sizeIndex = sz;
                params.changed();
            }));
        }
        for (int e = 0; e < 3; e += 2) {
            int ee = e;
            out.add(new Shot("08-edge-" + Params.EDGE_RADII[e], "edge radius " + Params.EDGE_RADII[e] + " mm", () -> {
                scene.focus = 6;
                scene.pitch = (float) Math.toRadians(25);
                params.edgeIndex = ee;
                params.changed();
            }));
        }
        for (int d = 0; d < 3; d += 2) {
            for (int fill = 0; fill < 3; fill += 2) {
                int dd = d, ff = fill;
                out.add(new Shot("09-depth-" + Params.DEPTHS[d] + "-fill-" + Params.FILL_NAMES[fill],
                        "C macro d6, depth " + Params.DEPTHS[d] + " mm, ink " + Params.FILL_NAMES[fill], () -> {
                    scene.focus = 1;
                    scene.preset = 2;
                    scene.pitch = (float) Math.toRadians(36);
                    params.technique = Params.Technique.C;
                    params.depthIndex = dd;
                    params.fillIndex = ff;
                }));
            }
        }
        for (int l = 1; l < Look.ALL.size(); l++) {
            int ll = l;
            out.add(new Shot("10-look-" + l, "Dice Set: " + Look.ALL.get(l).name(), () -> {
                scene.lookIndex = ll;
                scene.yaw = 0.1f;
            }));
        }
        return out;
    }

    /**
     * Walks the shot list off the FX thread: set each state on the FX thread, wait for its build, let
     * the renderer save a settled frame, then exit.
     */
    private void startCapture(Path dir) {
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(dir);
                // -Dnumerals.shots=01,03-macro-d20 limits the tour to shots whose names start with a prefix.
                List<String> only = List.of(System.getProperty("numerals.shots", "").split(","));
                for (Shot s : shots()) {
                    if (!only.getFirst().isEmpty() && only.stream().noneMatch(s.name()::startsWith)) continue;
                    int[] version = new int[1];
                    boolean[] dense = new boolean[1];
                    String[] caption = new String[1];
                    onFx(() -> {
                        resetState();
                        s.setup().run();
                        builds.request(params.structure());
                        version[0] = params.structureVersion;
                        dense[0] = params.technique == Params.Technique.C;
                        caption[0] = s.caption() + "\n" + hudText().lines().skip(2).limit(5).reduce((a, b) -> a + "\n" + b).orElse("");
                    });
                    renderer.capture(dir.resolve(s.name() + ".png"), caption[0], version[0], dense[0]).get(180, TimeUnit.SECONDS);
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
