package andysdice.prototype.tray;

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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * PROTOTYPE. "What do the tray, surface, camera and lighting look like?" (issue #17).
 *
 * <p>Enter throws the current dice into the tray: a real Jolt pre-simulation (ADR-0003), played back.
 * E rerolls the low dice as a Reroll Throw (ADR-0006). The floating switcher (or the arrow keys) flips
 * between three whole looks; letter keys remix one dimension at a time. The HUD shows the full state and
 * what the last Throw took (attempts, endings, time to rest).
 */
public final class TrayApp extends Application {
    private static final Path SETS_DIR = Path.of(System.getProperty("tray.sets", "dicesets"));

    private final Params params = new Params();
    private final SceneSpec scene = new SceneSpec();
    private final Builds builds = new Builds();
    private final ExecutorService physics = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "physics");
        t.setDaemon(true);
        return t;
    });
    private Throws throwsSim;
    private GlRenderer renderer;

    private final Label hud = new Label();
    private final Label pill = new Label();
    private final StackPane root = new StackPane();
    private long lastHud;
    private long seed = 1;
    private volatile String throwInfo = "";
    private volatile boolean busy;

    @Override
    public void start(Stage stage) throws Exception {
        String v = System.getProperty("tray.look", "A").toUpperCase();
        for (Params.Look l : Params.Look.values()) if (l.name().equals(v)) params.apply(l);
        physics.submit(() -> {
            Throws.loadNatives(Path.of(System.getProperty("tray.natives")));
            throwsSim = new Throws();
        }).get();
        reloadSets();
        scene.setIndex = Math.max(0, set("03"));
        rebuildTray();
        renderer = new GlRenderer(scene, params, builds, new TrayTextures());
        builds.request(params.structure());

        String panel = "-fx-font-family: 'Consolas', monospace; -fx-text-fill: #e8e8e8;"
                + "-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 6;";
        hud.setStyle(panel + "-fx-font-size: 12px; -fx-padding: 8 10 8 10;");
        hud.setMouseTransparent(true);
        StackPane.setAlignment(hud, Pos.TOP_LEFT);
        StackPane.setMargin(hud, new Insets(10));

        Label help = new Label("""
                Enter  throw      E  reroll low dice      P  replay last
                <- ->  look A/B/C           D  dice      M / N  Dice Set
                S surface   W shape   J walls   H wall height   G size
                V camera    K pickup  L light   O shadows   A contact AO
                U translucent shadows   X escape volume   Y show it
                Q render scale    drag orbit   wheel zoom   Space reset view""");
        help.setStyle(panel + "-fx-font-size: 11px; -fx-text-fill: #bbbbbb; -fx-padding: 6 8 6 8;");
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
            scene.pitch = (float) Math.clamp(scene.pitch + (e.getSceneY() - drag[1]) * 0.006, Math.toRadians(12), Math.toRadians(88));
            drag[0] = e.getSceneX();
            drag[1] = e.getSceneY();
        });
        renderer.canvas.setOnScroll(e -> scene.zoom = (float) Math.clamp(scene.zoom * Math.pow(1.1, -e.getDeltaY() / 40), 0.15, 3));

        stage.setTitle("PROTOTYPE - Andy's Dice tray, surface, camera and lighting");
        stage.setScene(fxScene);
        stage.setOnCloseRequest(e -> renderer.canvas.dispose());
        stage.show();
        refreshPill();

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (now - lastHud > 200_000_000L) {
                    lastHud = now;
                    hud.setText(hudText());
                }
            }
        }.start();

        String capture = System.getProperty("tray.capture", "");
        if (!capture.isBlank()) startCapture(Path.of(capture));
        else throwDice(seed);
    }

    private void reloadSets() {
        try {
            scene.sets = DiceSet.loadAll(SETS_DIR);
        } catch (IOException ex) {
            throwInfo = "Dice Set reload failed: " + ex;
        }
    }

    /** Index of the Dice Set whose file name starts with {@code prefix}, or -1. */
    private int set(String prefix) {
        List<DiceSet> all = scene.sets;
        for (int i = 0; i < all.size(); i++) if (all.get(i).file().startsWith(prefix)) return i;
        return -1;
    }

    /** Builds the tray for the current knobs if they changed; a new tray empties it. */
    private void rebuildTray() {
        TrayGeom g = scene.tray;
        if (g != null && g.matches(params)) return;
        scene.tray = new TrayGeom(params.shape, params.size, params.wall, params.wallHeight(), params.escape);
        scene.roll = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Throws

    /** Pre-simulates a Throw of the current dice on the physics thread, then plays it. */
    CompletableFuture<Roll> throwDice(long s) {
        TrayGeom tray = scene.tray;
        Params.Surface surface = params.surface;
        List<DieKind> kinds = List.of(params.dice().kinds());
        busy = true;
        return CompletableFuture.supplyAsync(() -> {
            Throws.Result r = throwsSim.simulate(tray, surface, kinds, List.of(), List.of(), s);
            int[] all = new int[kinds.size()];
            Arrays.setAll(all, i -> i);
            Roll roll = new Roll(kinds, List.of(new Roll.Segment(all, r, null, null, System.nanoTime())));
            report("Throw", r, roll);
            scene.roll = roll;
            return roll;
        }, physics).whenComplete((r, ex) -> done(ex));
    }

    /**
     * A Reroll Throw of every die showing the low third of its range (the single lowest die if none
     * does), the way {@code 4d6rr<3} might: those dice are picked up and thrown again while the rest stay
     * frozen where they lie (ADR-0006).
     */
    CompletableFuture<Roll> reroll(long s) {
        Roll roll = scene.roll;
        if (roll == null) return CompletableFuture.completedFuture(null);
        TrayGeom tray = scene.tray;
        Params.Surface surface = params.surface;
        Params.Pickup pickup = params.pickup;
        busy = true;
        return CompletableFuture.supplyAsync(() -> {
            Throws.Pose[] rest = roll.restPoses();
            List<String> faces = roll.faces();
            List<Integer> chosen = new ArrayList<>();
            int lowest = 0;
            double lowestFrac = 9;
            for (int i = 0; i < faces.size(); i++) {
                double frac = lowFraction(roll.kinds().get(i), faces.get(i));
                if (frac <= 1 / 3.0) chosen.add(i);
                if (frac < lowestFrac) {
                    lowestFrac = frac;
                    lowest = i;
                }
            }
            if (chosen.isEmpty()) chosen.add(lowest);
            List<DieKind> moving = new ArrayList<>(), frozenKinds = new ArrayList<>();
            List<Throws.Pose> frozen = new ArrayList<>();
            for (int i = 0; i < faces.size(); i++) {
                if (chosen.contains(i)) {
                    moving.add(roll.kinds().get(i));
                } else {
                    frozenKinds.add(roll.kinds().get(i));
                    frozen.add(rest[i]);
                }
            }
            Throws.Result r = throwsSim.simulate(tray, surface, moving, frozenKinds, frozen, s);
            List<Roll.Segment> segs = new ArrayList<>(roll.segments());
            segs.add(new Roll.Segment(chosen.stream().mapToInt(Integer::intValue).toArray(), r, rest, pickup, System.nanoTime()));
            Roll next = new Roll(roll.kinds(), List.copyOf(segs));
            report("Reroll Throw of " + chosen.size(), r, next);
            scene.roll = next;
            return next;
        }, physics).whenComplete((r, ex) -> done(ex));
    }

    /** Where a Face Value sits in its kind's range, 0 (lowest) to 1 (highest). */
    private static double lowFraction(DieKind k, String face) {
        int v = Integer.parseInt(face);
        return switch (k) {
            case D10 -> v / 9.0;
            case D_PERCENT -> v / 90.0;
            default -> (v - 1) / (double) (Integer.parseInt(k.label.substring(1)) - 1);
        };
    }

    private void report(String what, Throws.Result r, Roll roll) {
        throwInfo = String.format("%s: %s after %d attempt(s) %s, pre-simulated in %d ms, %.2f s to rest%nFace Values: %s",
                what, r.ending(), r.attempts(), r.endings(), r.wallMillis(), r.rec().restTime(), String.join(" ", roll.faces()));
        System.out.println(throwInfo.replace('\n', ' '));
    }

    private void done(Throwable ex) {
        busy = false;
        if (ex != null) {
            throwInfo = "THROW FAILED: " + ex;
            ex.printStackTrace();
        }
    }

    /** Restarts the last segment's playback (pickup and all), to watch the camera do it again. */
    private void replay() {
        Roll r = scene.roll;
        if (r == null) return;
        List<Roll.Segment> segs = new ArrayList<>(r.segments());
        Roll.Segment s = segs.removeLast();
        segs.add(new Roll.Segment(s.moving(), s.result(), s.before(), s.pickup(), System.nanoTime()));
        scene.roll = new Roll(r.kinds(), List.copyOf(segs));
    }

    // ---------------------------------------------------------------------------------------------
    // UI

    /** The floating, obviously-not-the-design pill at the bottom centre. */
    private HBox switcher() {
        Button prev = new Button("<"), next = new Button(">");
        prev.setFocusTraversable(false);
        next.setFocusTraversable(false);
        prev.setOnAction(e -> cycleLook(-1));
        next.setOnAction(e -> cycleLook(1));
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

    private void cycleLook(int step) {
        Params.Look[] all = Params.Look.values();
        params.apply(all[(params.look.ordinal() + step + all.length) % all.length]);
        scene.pitch = (float) Math.toRadians(50);
        afterTrayKnob();
        refreshPill();
    }

    private void refreshPill() {
        pill.setText(params.look.name() + "  " + params.look.label);
    }

    /** A tray knob changed: rebuild it, and throw again so the new tray is never shown empty. */
    private void afterTrayKnob() {
        TrayGeom before = scene.tray;
        rebuildTray();
        if (scene.tray != before && !busy) throwDice(++seed);
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case LEFT -> cycleLook(-1);
            case RIGHT -> cycleLook(1);
            case ENTER -> {
                if (!busy) throwDice(++seed);
            }
            case E -> {
                if (!busy) reroll(++seed);
            }
            case P -> replay();
            case D -> {
                params.diceIndex = (params.diceIndex + 1) % Params.DICE.length;
                if (!busy) throwDice(++seed);
            }
            case M -> scene.setIndex = Math.floorMod(scene.setIndex + 1, Math.max(1, scene.sets.size()));
            case N -> scene.setIndex = Math.floorMod(scene.setIndex - 1, Math.max(1, scene.sets.size()));
            case S -> params.surface = Params.next(params.surface);
            case W -> {
                params.shape = Params.next(params.shape);
                afterTrayKnob();
            }
            case J -> {
                params.wall = Params.next(params.wall);
                afterTrayKnob();
            }
            case H -> {
                params.wallHeightIndex = (params.wallHeightIndex + 1) % Params.WALL_HEIGHTS.length;
                afterTrayKnob();
            }
            case G -> {
                params.size = Params.next(params.size);
                afterTrayKnob();
            }
            case X -> {
                params.escape = Params.next(params.escape);
                afterTrayKnob();
            }
            case Y -> params.showEscape = !params.showEscape;
            case V -> params.camera = Params.next(params.camera);
            case K -> params.pickup = Params.next(params.pickup);
            case L -> params.rig = Params.next(params.rig);
            case O -> params.shadows = Params.next(params.shadows);
            case A -> params.contactAo = !params.contactAo;
            case U -> params.transShadow = Params.next(params.transShadow);
            case Q -> params.renderScaleIndex = (params.renderScaleIndex + 1) % Params.RENDER_SCALES.length;
            case SPACE -> {
                scene.yaw = 0;
                scene.pitch = (float) Math.toRadians(50);
                scene.zoom = 1;
            }
            default -> {
                return;
            }
        }
        e.consume();
        hud.setText(hudText());
    }

    private String hudText() {
        DiceSet s = scene.set();
        TrayGeom g = scene.tray;
        StringBuilder t = new StringBuilder();
        t.append(params.look.name()).append("  ").append(params.look.label).append('\n');
        t.append(renderer.stats()).append('\n');
        t.append("dice     D  ").append(params.dice().expression()).append("   in ").append(s == null ? "-" : s.name() + " (" + s.summary() + ")").append('\n');
        t.append("surface  S  ").append(params.surface.label).append('\n');
        t.append("tray     W  ").append(params.shape.label).append("   G  ").append(params.size.label).append('\n');
        t.append("walls    J  ").append(params.wall.label).append("   H  ").append((int) params.wallHeight()).append(" mm high").append('\n');
        t.append("escape   X  ").append(params.escape.label).append(params.showEscape ? "  (shown)" : "").append('\n');
        t.append("camera   V  ").append(params.camera.label).append('\n');
        t.append("pickup   K  ").append(params.pickup.label).append('\n');
        t.append("light    L  ").append(params.rig.label).append('\n');
        t.append("shadows  O  ").append(params.shadows.label).append("   A  contact AO ").append(params.contactAo ? "on" : "off").append('\n');
        t.append("transl.  U  ").append(params.transShadow.label).append('\n');
        t.append("scale    Q  ").append(params.renderScale()).append('\n');
        Roll r = scene.roll;
        if (r != null) {
            t.append(String.format("playback: segment %d (%s), %s, %.2f s in%n", r.segments().size(),
                    r.segments().size() == 1 ? "Throw" : "Reroll Throw", scene.phase, scene.segmentTime(r)));
        }
        t.append(busy ? "pre-simulating..." : throwInfo);
        if (g != null && !g.matches(params)) t.append("\n(tray rebuilding)");
        return t.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Unattended capture: a scripted tour that saves one PNG per state, then exits.

    /**
     * One scripted state: a name, a caption, how to set it up, what to throw, and where to freeze.
     *
     * @param reroll  whether to follow the Throw with a Reroll Throw
     * @param freezeAt seconds into the last segment, or a negative number for "at rest, settled"
     */
    private record Shot(String name, String caption, Consumer<Params> setup, boolean reroll, float freezeAt) {
    }

    private void resetState() {
        params.apply(Params.Look.A);
        params.contactAo = true;
        params.showEscape = false;
        params.renderScaleIndex = 0;
        params.diceIndex = 3;
        scene.setIndex = Math.max(0, set("03"));
        scene.zoom = 1;
        scene.yaw = 0;
        scene.pitch = (float) Math.toRadians(50);
    }

    private List<Shot> shots() {
        List<Shot> out = new ArrayList<>();
        for (Params.Look l : Params.Look.values()) {
            out.add(new Shot("01-look-" + l + "-set", "look " + l + ": " + l.label + "\n7-piece set at rest", p -> p.apply(l), false, -1));
            out.add(new Shot("02-look-" + l + "-cap", "look " + l + ", 20 dice (the cap) at rest", p -> {
                p.apply(l);
                p.diceIndex = 5;
            }, false, -1));
            out.add(new Shot("03-look-" + l + "-tumble", "look " + l + ", 20 dice 0.15 s into the Throw", p -> {
                p.apply(l);
                p.diceIndex = 5;
            }, false, 0.15f));
        }
        for (Params.Surface s : Params.Surface.values()) {
            out.add(new Shot("04-surface-" + s, "surface " + s.label + " (look A otherwise)", p -> p.surface = s, false, -1));
            out.add(new Shot("04-surface-" + s + "-close", "surface " + s.label + ", close", p -> {
                p.surface = s;
                p.camera = Params.Camera.SETTLE;
            }, false, -1));
        }
        for (int i = 0; i < Params.RENDER_SCALES.length; i += 2) {
            int ii = i;
            out.add(new Shot("05-scale-" + Params.RENDER_SCALES[i], "render scale " + Params.RENDER_SCALES[i] + ", felt, whole tray", p -> p.renderScaleIndex = ii, false, -1));
        }
        for (Params.Rig rig : Params.Rig.values()) {
            out.add(new Shot("06-rig-" + rig, "light " + rig.label + " (look A otherwise)", p -> p.rig = rig, false, -1));
        }
        for (Params.Rig rig : new Params.Rig[]{Params.Rig.STUDIO, Params.Rig.LAMP, Params.Rig.WINDOW}) {
            for (Params.Shadows sh : Params.Shadows.values()) {
                out.add(new Shot("07-shadows-" + rig + "-" + sh, "shadows " + sh.label + ", " + rig + ", close", p -> {
                    p.rig = rig;
                    p.shadows = sh;
                    p.camera = Params.Camera.SETTLE;
                }, false, -1));
            }
        }
        out.add(new Shot("07-ao-off", "contact AO off (look A otherwise), close", p -> {
            p.contactAo = false;
            p.camera = Params.Camera.SETTLE;
        }, false, -1));
        for (String setPrefix : new String[]{"12", "13", "11"}) {
            for (Params.TransShadow ts : Params.TransShadow.values()) {
                out.add(new Shot("08-trans-" + setPrefix + "-" + ts, "translucent shadows " + ts.label + ", window", p -> {
                    p.transShadow = ts;
                    p.rig = Params.Rig.WINDOW;
                    scene.setIndex = Math.max(0, set(setPrefix));
                    p.camera = Params.Camera.SETTLE;
                }, false, -1));
            }
        }
        for (Params.Shape sh : Params.Shape.values()) {
            for (Params.WallMaterial wm : Params.WallMaterial.values()) {
                out.add(new Shot("09-tray-" + sh + "-" + wm, "tray " + sh.label + ", walls " + wm.label, p -> {
                    p.shape = sh;
                    p.wall = wm;
                }, false, -1));
            }
        }
        for (Params.Size sz : Params.Size.values()) {
            out.add(new Shot("10-size-" + sz, "tray " + sz.label + ", 20 dice at rest", p -> {
                p.size = sz;
                p.diceIndex = 5;
            }, false, -1));
        }
        for (Params.Escape es : Params.Escape.values()) {
            out.add(new Shot("11-escape-" + es, "15 mm walls, escape volume = " + es.label + " (shown)", p -> {
                p.wallHeightIndex = 0;
                p.escape = es;
                p.showEscape = true;
                p.diceIndex = 5;
            }, false, -1));
        }
        float[][] pickupTimes = {{0.35f}, {0.12f}, {0.3f, 0.95f}};
        for (Params.Pickup pk : Params.Pickup.values()) {
            for (float ft : pickupTimes[pk.ordinal()]) {
                out.add(new Shot("12-pickup-" + pk + "-" + ft, "Reroll Throw pickup " + pk.label + ", " + ft + " s in", p -> {
                    p.pickup = pk;
                    p.diceIndex = 4;
                }, true, ft));
            }
        }
        for (Params.Camera cam : new Params.Camera[]{Params.Camera.FOLLOW, Params.Camera.SETTLE, Params.Camera.TOP}) {
            out.add(new Shot("13-camera-" + cam + "-rest", "camera " + cam.label + ", at rest", p -> p.camera = cam, false, -1));
        }
        out.add(new Shot("13-camera-FOLLOW-tumble", "camera FOLLOW, 0.5 s into a 2d20 Throw", p -> {
            p.camera = Params.Camera.FOLLOW;
            p.diceIndex = 1;
        }, false, 0.5f));
        return out;
    }

    /**
     * Walks the shot list off the FX thread: set each state, pre-simulate its Throw (always seed 7, so
     * shots that share a tray share a Throw), freeze the playback, let the renderer save a settled frame
     * with its GPU time, then exit.
     */
    private void startCapture(Path dir) {
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(dir);
                List<String> only = List.of(System.getProperty("tray.shots", "").split(","));
                for (Shot s : shots()) {
                    if (!only.getFirst().isEmpty() && only.stream().noneMatch(s.name()::startsWith)) continue;
                    onFx(() -> {
                        resetState();
                        s.setup().accept(params);
                        rebuildTray();
                        scene.freezeAt = 0;
                    });
                    Roll roll = throwDice(7).get(120, TimeUnit.SECONDS);
                    if (s.reroll()) roll = reroll(8).get(120, TimeUnit.SECONDS);
                    float at = s.freezeAt() >= 0 ? s.freezeAt() : roll.last().endSeconds() + 1;
                    scene.freezeAt = at;
                    String caption = s.caption() + "\n" + throwInfo.lines().findFirst().orElse("");
                    renderer.capture(dir.resolve(s.name() + ".png"), caption).get(120, TimeUnit.SECONDS);
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
