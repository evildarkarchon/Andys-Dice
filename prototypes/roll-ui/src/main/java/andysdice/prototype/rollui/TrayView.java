package andysdice.prototype.rollui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

import javafx.animation.AnimationTimer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * PROTOTYPE 2D stand-in for the GL tray. It plays a {@link RollPlan}'s Throw chain with the timings the tray
 * prototype measured (about 0.85 s release-to-rest, ADR-0010's glow-then-lift pickup of about 1.3 s before each
 * Reroll Throw, and the settle-in camera), and implements the skip, fast-forward and player-camera behaviours
 * the switcher offers. The motion is fake; the timing and the camera's moves are the point.
 */
final class TrayView extends Region {

    static final double W = 300;
    static final double H = 200;
    static final double WALL = 14;

    private static final double THROW = 0.85;
    private static final double SETTLE = 0.6;
    private static final double HOLD = 0.3;
    private static final double GLOW = 0.6;
    private static final double LIFT = 0.7;
    /** How long a new Throw takes to ease the player's orbit and zoom back to the automatic camera. */
    private static final double CAMERA_RESET = 0.4;

    private record Path(Point2D start, Point2D ctrl, Point2D end, double rot0, double spin) {
    }

    private record Cam(double zoom, double cx, double cy) {
        static final Cam FULL = new Cam(1, 0, 0);

        Cam lerp(Cam o, double s) {
            return new Cam(zoom + (o.zoom - zoom) * s, cx + (o.cx - cx) * s, cy + (o.cy - cy) * s);
        }
    }

    private final class DieNode extends Group {
        final RollPlan.PhysDie die;
        final Polygon shape;
        final Text label = new Text();
        final double size;

        DieNode(RollPlan.PhysDie die) {
            this.die = die;
            size = switch (die.shape()) {
                case D4, D12 -> 18;
                case D20 -> 20;
                default -> 16;
            };
            shape = polygon(die.shape(), die.tens(), size / 2);
            shape.setStrokeWidth(0.8);
            label.setFont(Font.font("Segoe UI", FontWeight.BOLD, die.shape() == Expr.Kind.D20 || die.shape() == Expr.Kind.D4 ? 6.5 : 7.5));
            label.setTextOrigin(VPos.CENTER);
            getChildren().addAll(shape, label);
        }

        void setFace(String text) {
            if (!text.equals(label.getText())) {
                label.setText(text);
                label.setX(-label.getLayoutBounds().getWidth() / 2);
                // Underline 6 and 9 like ADR-0008's numerals, so they read the right way up.
                label.setUnderline(text.equals("6") || text.equals("9"));
            }
        }
    }

    private final AppState state;
    private final Behaviour beh;
    private final Group world = new Group();
    private final Group floorLayer = new Group();
    private final Group diceLayer = new Group();
    private final Rectangle tint = new Rectangle();
    private final Label status = new Label();
    private final Label hint = new Label("The tray is empty. Type a Roll Expression and press Enter.");
    private final Label placeholder = new Label("placeholder tray: a 2D stand-in for the GL renderer");
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper(false);
    private final Map<Integer, DieNode> nodes = new HashMap<>();
    /** ADR-0003's second generator: release conditions only, never Face Values. */
    private final Random release = new Random();
    private final AnimationTimer timer;

    private RollPlan plan;
    private final Map<Integer, Map<Integer, Path>> paths = new HashMap<>();
    private final Map<Integer, List<Integer>> stepsOf = new HashMap<>();
    private final List<Cam> restCams = new ArrayList<>();
    private double[] throwS = new double[0];
    private double[] glowS = new double[0];
    private double[] liftS = new double[0];
    private double total;
    private double t;
    private Consumer<RollPlan> onReveal;

    private double yaw;
    private double tilt = 1;
    private double zoomMul = 1;
    private double[] resetFrom;
    private double resetT;
    private boolean ffKey;
    private boolean ffMouse;
    private double pressX;
    private double pressY;
    private boolean dragged;
    private long last;

    TrayView(AppState state, Behaviour beh) {
        this.state = state;
        this.beh = beh;
        getStyleClass().add("tray-view");
        setStyle("-fx-background-color: #17191c;");
        world.getChildren().addAll(floorLayer, diceLayer);
        tint.setMouseTransparent(true);
        status.setStyle("-fx-text-fill: #d7dbe0; -fx-font-size: 11px; -fx-background-color: rgba(0,0,0,0.45);"
                + " -fx-padding: 3 7; -fx-background-radius: 4;");
        status.setMouseTransparent(true);
        hint.setStyle("-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 15px;");
        hint.setMouseTransparent(true);
        placeholder.setStyle("-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 10px;");
        placeholder.setMouseTransparent(true);
        getChildren().addAll(world, tint, hint, status, placeholder);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        buildFloor();
        state.tray.addListener((o, a, b) -> buildFloor());
        state.lightRig.addListener((o, a, b) -> buildFloor());
        state.diceSet.addListener((o, a, b) -> nodes.values().forEach(this::paint));

        setOnMousePressed(e -> {
            pressX = e.getX();
            pressY = e.getY();
            dragged = false;
            if (busy.get() && beh.skip.get() == Behaviour.Skip.FAST_FORWARD) {
                ffMouse = true;
            }
        });
        setOnMouseDragged(e -> {
            if (beh.camera.get() == Behaviour.Camera.ORBIT && !ffMouse) {
                yaw += (e.getX() - pressX) * 0.4;
                tilt = Math.clamp(tilt - (e.getY() - pressY) * 0.004, 0.55, 1.0);
                resetFrom = null;
            }
            if (Math.abs(e.getX() - pressX) + Math.abs(e.getY() - pressY) > 3) {
                dragged = true;
            }
            pressX = e.getX();
            pressY = e.getY();
        });
        setOnMouseReleased(e -> {
            boolean wasFf = ffMouse;
            ffMouse = false;
            if (!dragged && !wasFf && busy.get() && e.getClickCount() == 1) {
                skipByMode();
            }
        });
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !busy.get()) {
                yaw = 0;
                tilt = 1;
                zoomMul = 1;
            }
        });
        setOnScroll(e -> {
            if (beh.camera.get() != Behaviour.Camera.AUTO) {
                zoomMul = Math.clamp(zoomMul * Math.exp(e.getDeltaY() * 0.0025), 0.7, 3.0);
                resetFrom = null;
            }
        });

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double dt = last == 0 ? 0 : Math.min((now - last) / 1e9, 0.05);
                last = now;
                tick(dt);
            }
        };
        timer.start();
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    boolean isBusy() {
        return busy.get();
    }

    void stop() {
        timer.stop();
    }

    void setFastForwardKey(boolean down) {
        ffKey = down;
    }

    /**
     * Starts playing {@code plan}'s Throw chain. The previous Roll's Dice leave the tray (ADR-0007: they lie
     * there only until the next Roll). {@code reveal} runs once, when the last Throw is at rest or on a skip.
     */
    void play(RollPlan plan, Consumer<RollPlan> reveal) {
        this.plan = plan;
        this.onReveal = reveal;
        nodes.clear();
        diceLayer.getChildren().clear();
        paths.clear();
        stepsOf.clear();
        restCams.clear();
        for (RollPlan.PhysDie d : plan.phys) {
            DieNode n = new DieNode(d);
            paint(n);
            nodes.put(d.id(), n);
            diceLayer.getChildren().add(n);
        }

        int steps = plan.steps.size();
        throwS = new double[steps];
        glowS = new double[steps];
        liftS = new double[steps];
        double at = 0;
        Map<Integer, Point2D> rest = new HashMap<>();
        for (int k = 0; k < steps; k++) {
            if (k > 0) {
                glowS[k] = at + HOLD;
                liftS[k] = glowS[k] + GLOW;
                at = liftS[k] + LIFT;
            }
            throwS[k] = at;
            at += THROW + SETTLE;

            List<Integer> thrown = plan.steps.get(k).thrown();
            for (int id : thrown) {
                rest.remove(id);
            }
            // Released from the front (the camera's side) or a short side, like a hand over the near wall.
            int side = release.nextInt(3);
            for (int id : thrown) {
                Point2D end = place(rest, nodes.get(id).size / 2 + 1);
                rest.put(id, end);
                Point2D start = switch (side) {
                    case 0 -> new Point2D(-W / 2 + release.nextDouble() * W, H / 2 + 45);
                    case 1 -> new Point2D(-W / 2 - 45, -H / 2 + release.nextDouble() * H);
                    default -> new Point2D(W / 2 + 45, -H / 2 + release.nextDouble() * H);
                };
                Point2D ctrl = new Point2D((release.nextDouble() - 0.5) * W * 0.9, (release.nextDouble() - 0.5) * H * 0.9);
                double spin = (release.nextBoolean() ? 1 : -1) * (540 + release.nextDouble() * 720);
                paths.computeIfAbsent(id, x -> new HashMap<>())
                        .put(k, new Path(start, ctrl, end, release.nextDouble() * 360, spin));
                stepsOf.computeIfAbsent(id, x -> new ArrayList<>()).add(k);
            }
            restCams.add(camOver(rest.values()));
        }
        total = at;
        t = 0;
        resetFrom = new double[] {yaw, tilt, zoomMul};
        resetT = 0;
        busy.set(true);
        hint.setVisible(false);
        render();
    }

    /** Ends the chain now: every Die snaps to its final rest, and the Roll Result is revealed. */
    void finishNow() {
        if (busy.get()) {
            t = total;
            render();
            reveal();
        }
    }

    /** Ends the current Throw now (or the next one, if the dice are mid-pickup); the chain carries on. */
    void skipThrow() {
        if (!busy.get()) {
            return;
        }
        for (double s : throwS) {
            if (s + THROW > t + 1e-6) {
                t = s + THROW;
                render();
                return;
            }
        }
        finishNow();
    }

    void skipByMode() {
        switch (beh.skip.get()) {
            case SKIP_ALL -> finishNow();
            case SKIP_THROW -> skipThrow();
            default -> {
                // Fast-forward and no-skip modes ignore a click: fast-forward is press-and-hold.
            }
        }
    }

    /** Capture tour only: jump the playback clock. */
    void seek(double seconds) {
        if (busy.get()) {
            t = Math.min(seconds, total);
            render();
        }
    }

    private void reveal() {
        busy.set(false);
        Consumer<RollPlan> r = onReveal;
        onReveal = null;
        if (r != null) {
            r.accept(plan);
        }
    }

    private void tick(double dt) {
        boolean ff = (ffKey || ffMouse) && beh.skip.get() == Behaviour.Skip.FAST_FORWARD;
        if (busy.get()) {
            t = Math.min(total, t + dt * (ff ? 4 : 1));
        }
        if (resetFrom != null) {
            resetT += dt;
            double s = smooth(resetT / CAMERA_RESET);
            yaw = resetFrom[0] * (1 - s);
            tilt = resetFrom[1] + (1 - resetFrom[1]) * s;
            zoomMul = resetFrom[2] + (1 - resetFrom[2]) * s;
            if (s >= 1) {
                resetFrom = null;
            }
        }
        render();
        if (busy.get() && t >= total) {
            reveal();
        }
    }

    private void render() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Cam cam = autoCam();
        double k = Math.min(w / (W + 2 * WALL + 24), h / (H + 2 * WALL + 24)) * cam.zoom * zoomMul;
        world.getTransforms().setAll(new Translate(w / 2, h / 2), new Scale(k, k * tilt), new Rotate(yaw),
                new Translate(-cam.cx, -cam.cy));

        String phase;
        if (plan != null) {
            for (DieNode n : nodes.values()) {
                pose(n);
            }
            phase = phaseText();
        } else {
            phase = "Tray empty";
        }
        boolean ff = (ffKey || ffMouse) && busy.get() && beh.skip.get() == Behaviour.Skip.FAST_FORWARD;
        String camText = "Camera: " + (busy.get() && plan != null && isFlying() ? "whole tray" : "settled in");
        if (Math.abs(yaw) > 0.5 || Math.abs(tilt - 1) > 0.01 || Math.abs(zoomMul - 1) > 0.01) {
            camText += String.format(" · player yaw %+.0f° tilt %.2f zoom %.2f×", yaw, tilt, zoomMul);
        }
        status.setText(phase + (ff ? "  ·  ×4 fast-forward" : "") + "\n" + camText);
    }

    private boolean isFlying() {
        for (double s : throwS) {
            if (t >= s && t < s + THROW) {
                return true;
            }
        }
        return false;
    }

    private String phaseText() {
        int steps = throwS.length;
        for (int k = steps - 1; k >= 0; k--) {
            if (k > 0 && t >= glowS[k] - HOLD && t < throwS[k]) {
                int n = plan.steps.get(k).thrown().size();
                return "Picking up " + n + (n == 1 ? " Die" : " Dice") + " for Reroll Throw " + (k + 1) + " of " + steps
                        + (t < liftS[k] ? " (glow)" : " (lift)");
            }
            if (t >= throwS[k] && t < throwS[k] + THROW) {
                return (k == 0 ? "Throw 1" : "Reroll Throw " + (k + 1)) + " of " + steps + " · "
                        + plan.steps.get(k).thrown().size() + " Dice tumbling";
            }
            if (t >= throwS[k] + THROW && t < throwS[k] + THROW + SETTLE) {
                return "Throw " + (k + 1) + " of " + steps + " at rest · camera settling in";
            }
        }
        return busy.get() ? "Settling" : "At rest · " + steps + (steps == 1 ? " Throw" : " Throws");
    }

    private void pose(DieNode n) {
        List<Integer> steps = stepsOf.get(n.die.id());
        int s = steps.getFirst();
        int next = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (throwS[steps.get(i)] <= t) {
                s = steps.get(i);
                next = i + 1 < steps.size() ? steps.get(i + 1) : -1;
            }
        }
        Path p = paths.get(n.die.id()).get(s);
        double local = t - throwS[s];
        double x;
        double y;
        double rot;
        double scale = 1;
        double opacity = 1;
        String face;
        if (local < THROW) {
            double q = local / THROW;
            double e = 1 - Math.pow(1 - q, 3);
            double a = 1 - e;
            x = a * a * p.start.getX() + 2 * a * e * p.ctrl.getX() + e * e * p.end.getX();
            y = a * a * p.start.getY() + 2 * a * e * p.ctrl.getY() + e * e * p.end.getY();
            rot = p.rot0 + p.spin * e;
            scale = 1 + 0.35 * Math.abs(Math.sin(q * Math.PI * 4)) * (1 - q) * (1 - q);
            if (q < 0.8) {
                int tick = (int) (local * 16);
                // A face flicker while it tumbles; any face will do, so a cheap hash of time and die.
                int v = 1 + Math.floorMod(tick * 7 + n.die.id() * 13, n.die.shape().faces);
                face = RollPlan.faceLabel(n.die, v);
            } else {
                face = plan.steps.get(s).faces().get(n.die.id());
            }
        } else {
            x = p.end.getX();
            y = p.end.getY();
            rot = p.rot0 + p.spin;
            face = plan.steps.get(s).faces().get(n.die.id());
        }
        boolean glow = false;
        if (next >= 0 && t >= glowS[next]) {
            if (t < liftS[next]) {
                glow = true;
            } else {
                double q = Math.min(1, (t - liftS[next]) / LIFT);
                scale = 1 + 0.6 * q;
                opacity = 1 - q;
                glow = q < 0.5;
            }
        }
        n.setTranslateX(x);
        n.setTranslateY(y);
        n.setRotate(rot);
        n.setScaleX(scale);
        n.setScaleY(scale);
        n.setOpacity(opacity);
        n.setFace(face);
        n.shape.setEffect(glow ? new DropShadow(BlurType.GAUSSIAN, Color.web("#ffd54a"), 14, 0.55, 0, 0) : shadow());
    }

    private Cam autoCam() {
        if (plan == null) {
            return Cam.FULL;
        }
        if (!busy.get()) {
            return restCams.getLast();
        }
        for (int k = throwS.length - 1; k >= 0; k--) {
            if (k > 0 && t < throwS[k] && t >= glowS[k] - HOLD) {
                return t < liftS[k] ? restCams.get(k - 1)
                        : restCams.get(k - 1).lerp(Cam.FULL, smooth((t - liftS[k]) / LIFT));
            }
            if (t >= throwS[k]) {
                double since = t - throwS[k] - THROW;
                return since < 0 ? Cam.FULL : Cam.FULL.lerp(restCams.get(k), smooth(since / SETTLE));
            }
        }
        return Cam.FULL;
    }

    private Cam camOver(Iterable<Point2D> pts) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Point2D p : pts) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
        }
        double zoom = Math.clamp(Math.min(W / (maxX - minX + 60), H / (maxY - minY + 60)), 1.0, 2.4);
        return new Cam(zoom, (minX + maxX) / 2, (minY + maxY) / 2);
    }

    private Point2D place(Map<Integer, Point2D> taken, double r) {
        Point2D best = null;
        double bestGap = -1;
        for (int i = 0; i < 300; i++) {
            Point2D c = new Point2D((release.nextDouble() - 0.5) * (W - 2 * r - 6), (release.nextDouble() - 0.5) * (H - 2 * r - 6));
            double gap = Double.MAX_VALUE;
            for (Point2D o : taken.values()) {
                gap = Math.min(gap, c.distance(o));
            }
            if (gap > 2 * r + 4) {
                return c;
            }
            if (gap > bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private void paint(DieNode n) {
        AppState.DiceSetInfo set = state.diceSet.get();
        n.shape.setFill(set.body());
        n.shape.setStroke(set.body().darker());
        n.label.setFill(set.ink());
    }

    private DropShadow shadow() {
        return switch (state.lightRig.get()) {
            case "Lamp" -> new DropShadow(BlurType.GAUSSIAN, Color.rgb(0, 0, 0, 0.75), 4, 0.2, 3.5, 3.5);
            case "Window" -> new DropShadow(BlurType.GAUSSIAN, Color.rgb(0, 0, 0, 0.55), 6, 0.1, 7, 2);
            default -> new DropShadow(BlurType.GAUSSIAN, Color.rgb(0, 0, 0, 0.5), 7, 0.0, 1.5, 2.5);
        };
    }

    private void buildFloor() {
        AppState.TrayInfo tr = state.tray.get();
        floorLayer.getChildren().clear();
        Rectangle wall = new Rectangle(-W / 2 - WALL, -H / 2 - WALL, W + 2 * WALL, H + 2 * WALL);
        wall.setArcWidth(10);
        wall.setArcHeight(10);
        wall.setFill(tr.wall());
        floorLayer.getChildren().add(wall);
        if (tr.hex()) {
            Polygon hex = new Polygon(-W / 2 + 50, -H / 2, W / 2 - 50, -H / 2, W / 2, 0, W / 2 - 50, H / 2, -W / 2 + 50, H / 2, -W / 2, 0);
            hex.setFill(tr.floor());
            floorLayer.getChildren().add(hex);
        } else {
            Rectangle floor = new Rectangle(-W / 2, -H / 2, W, H);
            floor.setFill(tr.floor());
            floorLayer.getChildren().add(floor);
        }
        tint.setFill(switch (state.lightRig.get()) {
            case "Lamp" -> Color.rgb(255, 160, 70, 0.10);
            case "Window" -> Color.rgb(120, 170, 255, 0.09);
            default -> Color.TRANSPARENT;
        });
        nodes.values().forEach(n -> n.shape.setEffect(shadow()));
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        tint.setWidth(w);
        tint.setHeight(h);
        hint.autosize();
        hint.relocate((w - hint.getWidth()) / 2, (h - hint.getHeight()) / 2);
        status.autosize();
        status.relocate(8, 8);
        placeholder.autosize();
        placeholder.relocate(w - placeholder.getWidth() - 8, h - placeholder.getHeight() - 4);
        render();
    }

    private static double smooth(double x) {
        double c = Math.clamp(x, 0, 1);
        return c * c * (3 - 2 * c);
    }

    private static Polygon polygon(Expr.Kind kind, boolean tens, double r) {
        int sides;
        double offset;
        switch (kind) {
            case D4 -> {
                sides = 3;
                offset = -90;
            }
            case D6 -> {
                sides = 4;
                offset = 45;
            }
            case D8 -> {
                sides = 4;
                offset = 0;
            }
            case D12 -> {
                sides = 5;
                offset = -90;
            }
            case D20 -> {
                sides = 6;
                offset = 0;
            }
            default -> {
                // d10 and the percentile d10: a kite, the percentile one a little wider so the two read apart.
                double wide = tens ? 0.95 : 0.8;
                return new Polygon(0, -r, r * wide, -r * 0.15, 0, r, -r * wide, -r * 0.15);
            }
        }
        Polygon p = new Polygon();
        for (int i = 0; i < sides; i++) {
            double a = Math.toRadians(offset + i * 360.0 / sides);
            p.getPoints().addAll(Math.cos(a) * r, Math.sin(a) * r);
        }
        return p;
    }
}
