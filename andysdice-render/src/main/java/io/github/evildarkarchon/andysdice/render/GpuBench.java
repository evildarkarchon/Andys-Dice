package io.github.evildarkarchon.andysdice.render;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionCaps;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.render.camera.Framing;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The GPU half of the dev-only {@code --bench} (ADR-0014, ticket #61): it draws the most expensive scene at
 * 1920 × 1080 and reports the GPU time each Quality Preset takes on it, against the budget of
 * {@value #BUDGET_MILLIS} ms for {@link #BUDGETED_PRESET Medium} on the reference RTX 4070.
 *
 * <p>The most expensive scene is 20 translucent Dice in the most expensive built-in Tray under the most expensive
 * Light Rig. Rather than guess which those are, the bench draws every candidate ({@link #scenes()}: each translucent
 * built-in Dice Set in each built-in Tray under each Light Rig, from both of the camera's shots) at Medium, measures
 * the dearest few again over more frames, takes the dearest of those, and then draws that one at every preset. Before
 * the search it draws for a second or two uncounted, so the GPU is at its working clocks. A scene's time is the median of its frames' GPU times, measured
 * by the render thread's own timer queries ({@link RenderThread#setOnGpuTime}); the bench never opens a timer query of
 * its own, since time-elapsed queries can't nest.
 *
 * <p>Frames are drawn back to back, as fast as the render thread will take them, so the GPU runs at its working
 * clocks rather than idling between 60 Hz frames. Running it is a release-checklist item, never a CI gate: CI's Mesa
 * times say nothing about any GPU (ADR-0005).
 *
 * <p>The app's {@code --bench} runs it on the app's own render thread and scene; {@link #main} runs it alone, with
 * a render thread of its own (dev: {@code mvn -pl andysdice-render -am process-classes -Pgpu-bench}).
 */
public final class GpuBench {

    /** ADR-0014's budget: GPU milliseconds per frame of the most expensive scene, on an RTX 4070, at Medium. */
    public static final double BUDGET_MILLIS = 0.5;

    /** The render target's width: ADR-0014's 1080p tray viewport. */
    public static final int WIDTH_PX = 1920;

    /** The render target's height. */
    public static final int HEIGHT_PX = 1080;

    /** The preset the budget is for: the default (ADR-0014 defines Medium as what meets it). */
    public static final QualityPreset BUDGETED_PRESET = QualityPreset.DEFAULT;

    /** Frames drawn after a change of scene or preset before any is counted: lazy bakes, late timer results. */
    static final int WARM_UP_FRAMES = 12;

    /** Frames counted per candidate scene while looking for the dearest. */
    static final int SEARCH_FRAMES = 40;

    /** Frames counted for each finalist at Medium, and for the dearest scene at the other presets. */
    static final int MEASURE_FRAMES = 240;

    /** How many of the search's dearest candidates are measured again over {@value #MEASURE_FRAMES} frames. */
    static final int FINALISTS = 3;

    /** Frames drawn and thrown away before the search, while the GPU clocks up from idle (a second or two). */
    static final int RAMP_FRAMES = 1200;

    // Between the resting Dice's centres: more than the bounding spheres of two d20s (1.6 inradii each) apart, and
    // the whole 5 x 4 grid well inside the leather hexagon's slanted walls.
    private static final double SPACING_X_MM = 36;
    private static final double SPACING_Z_MM = 34;
    private static final int COLUMNS = 5;
    // Each Die turned a little more about the vertical than the last, so no two are seen alike.
    private static final double YAW_STEP_DEGREES = 37;
    private static final double ASPECT = (double) WIDTH_PX / HEIGHT_PX;
    // How long any one frame's GPU time may take to come back before the bench gives up.
    private static final long FRAME_TIMEOUT_SECONDS = 30;

    /** Which of the camera's two framings a scene is seen in (#46). */
    public enum Shot {
        /** The whole Tray, as the Dice tumble: the most Tray on screen. */
        WHOLE_TRAY,
        /** The close-up on the Dice at rest: the most Dice on screen. */
        RESTING
    }

    /**
     * One candidate for the most expensive scene: 20 Dice of a translucent Dice Set resting in a Tray, under a Light
     * Rig, from one of the camera's shots.
     *
     * @param diceSet the Dice Set every Die is drawn in
     * @param tray the Tray
     * @param lightRig the Light Rig
     * @param shot the camera's framing
     */
    public record Scene(DiceSet diceSet, Tray tray, LightRig lightRig, Shot shot) {

        /** Checks that every part is there. */
        public Scene {
            Objects.requireNonNull(diceSet, "diceSet");
            Objects.requireNonNull(tray, "tray");
            Objects.requireNonNull(lightRig, "lightRig");
            Objects.requireNonNull(shot, "shot");
        }

        /**
         * Returns the scene's camera at the bench's 16:9 aspect.
         *
         * @return the whole-Tray shot or the close-up on {@link #dice()}
         */
        public DiceScene.Camera camera() {
            Framing framing = Framing.of(tray.geometry());
            return (shot == Shot.WHOLE_TRAY ? framing.wholeTray(ASPECT) : framing.resting(dice(), ASPECT)).camera();
        }

        /**
         * Returns a short description for the report.
         *
         * @return the Dice Set, Tray, Light Rig and shot, for example {@code 14-nebula / 01-green-felt / lamp /
         *     resting}
         */
        public String describe() {
            return diceSet.identity() + " / " + tray.identity() + " / " + lightRig.name().toLowerCase(Locale.ROOT)
                    + " / " + shot.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * The GPU time of one scene at one preset.
     *
     * @param scene the scene
     * @param preset the Quality Preset it was drawn at
     * @param millis the median GPU time of its frames, in ms
     * @param frames how many frames were counted
     */
    public record GpuTime(Scene scene, QualityPreset preset, double millis, int frames) {

        /** Checks the parts. */
        public GpuTime {
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(preset, "preset");
        }

        /**
         * Sums up the frames' GPU times as their median, which a stray slow frame (a driver hiccup, a background
         * load) doesn't move.
         *
         * @param scene the scene
         * @param preset the Quality Preset
         * @param millis each counted frame's GPU time, in ms
         * @return the median of the times
         * @throws IllegalArgumentException if there are no times
         */
        public static GpuTime of(Scene scene, QualityPreset preset, Collection<Double> millis) {
            if (millis.isEmpty()) {
                throw new IllegalArgumentException("no frames were timed");
            }
            double[] sorted = millis.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            int middle = sorted.length / 2;
            double median = sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
            return new GpuTime(scene, preset, median, sorted.length);
        }
    }

    /**
     * What the bench found.
     *
     * @param search every candidate scene's time at {@link #BUDGETED_PRESET}
     * @param byPreset the dearest scene's time at each Quality Preset
     */
    public record Report(List<GpuTime> search, Map<QualityPreset, GpuTime> byPreset) {

        /**
         * Copies the parts.
         *
         * @throws IllegalArgumentException if the search is empty or a preset wasn't measured
         */
        public Report {
            search = List.copyOf(search);
            byPreset = Map.copyOf(byPreset);
            if (search.isEmpty()) {
                throw new IllegalArgumentException("no scene was measured");
            }
            for (QualityPreset preset : QualityPreset.values()) {
                if (!byPreset.containsKey(preset)) {
                    throw new IllegalArgumentException("no time for " + preset);
                }
            }
        }

        /**
         * Returns the dearest scene's time at Medium: the scene every preset was measured on.
         *
         * @return the same as {@link #budgeted()}
         */
        public GpuTime worst() {
            return budgeted();
        }

        /**
         * Returns the budgeted preset's time on the dearest scene, measured over {@value #MEASURE_FRAMES} frames.
         *
         * @return Medium's time
         */
        public GpuTime budgeted() {
            return byPreset.get(BUDGETED_PRESET);
        }

        /**
         * Tells whether the budget is met: Medium draws the dearest scene in at most {@value #BUDGET_MILLIS} ms.
         * Low and High aren't budgeted.
         *
         * @return whether the release may go ahead on this count
         */
        public boolean meetsBudget() {
            return budgeted().millis() <= BUDGET_MILLIS;
        }

        /**
         * Prints the dearest few candidates and every preset's time on the dearest, with the verdict.
         *
         * @param out where to print
         */
        public void print(PrintStream out) {
            out.println("GPU time at " + WIDTH_PX + "x" + HEIGHT_PX + ", median of each scene's frames:");
            List<GpuTime> dearest = search.stream().sorted(Comparator.comparingDouble(GpuTime::millis).reversed())
                    .limit(5).toList();
            out.println("  dearest of " + search.size() + " candidate scenes at " + BUDGETED_PRESET.displayName()
                    + ", in the search:");
            for (GpuTime time : dearest) {
                out.println(String.format(Locale.ROOT, "    %.3f ms over %d frames  %s", time.millis(), time.frames(),
                        time.scene().describe()));
            }
            out.println("  the dearest scene, once the top " + FINALISTS + " were measured again ("
                    + worst().scene().describe() + "), at each preset:");
            for (QualityPreset preset : QualityPreset.values()) {
                GpuTime time = byPreset.get(preset);
                out.println(String.format(Locale.ROOT, "    %-6s %.3f ms over %d frames%s", preset.displayName(),
                        time.millis(), time.frames(), preset == BUDGETED_PRESET ? String.format(Locale.ROOT,
                                "  (budget %.2f ms: %s)", BUDGET_MILLIS, meetsBudget() ? "met" : "MISSED") : ""));
            }
        }
    }

    private GpuBench() {}

    /**
     * Returns every candidate for the most expensive scene: each translucent built-in Dice Set (translucency is the
     * dearest path of the uber-shader, ADR-0009) in each built-in Tray, under each Light Rig, from each shot.
     *
     * @return the candidates, Dice Set by Dice Set in catalogue order, then Tray, Light Rig and shot
     */
    public static List<Scene> scenes() {
        List<Scene> scenes = new ArrayList<>();
        for (DiceSet set : DiceSetCatalogue.builtIn().diceSets()) {
            if (!set.translucent()) {
                continue;
            }
            for (Tray tray : BuiltInTrays.all()) {
                for (LightRig rig : LightRig.values()) {
                    for (Shot shot : Shot.values()) {
                        scenes.add(new Scene(set, tray, rig, shot));
                    }
                }
            }
        }
        return List.copyOf(scenes);
    }

    /**
     * Returns the bench's Dice: as many as one Throw may hold, every Die Kind in turn, resting face up on the floor
     * in a 5 × 4 grid round the Tray's middle, each turned differently about the vertical.
     *
     * @return {@value RollExpressionCaps#MAX_DICE_PER_THROW} Dice, in mm
     */
    public static List<DiceScene.Die> dice() {
        DieKind[] kinds = DieKind.values();
        int count = RollExpressionCaps.MAX_DICE_PER_THROW;
        int rows = (count + COLUMNS - 1) / COLUMNS;
        List<DiceScene.Die> dice = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            DieKind kind = kinds[i % kinds.length];
            double x = (i % COLUMNS - (COLUMNS - 1) / 2.0) * SPACING_X_MM;
            double z = (i / COLUMNS - (rows - 1) / 2.0) * SPACING_Z_MM;
            Quaterniond orientation = new Quaterniond().rotationY(Math.toRadians(i * YAW_STEP_DEGREES))
                    .mul(Gallery.orientation(kind, Gallery.Pose.FACE_UP));
            dice.add(new DiceScene.Die(kind, new Vector3d(x, kind.inradius(), z), orientation));
        }
        return List.copyOf(dice);
    }

    /**
     * Runs the whole GPU bench on a running render thread drawing {@code scene}: the search at Medium over every
     * candidate, the dearest {@value #FINALISTS} again, then the dearest of those at every preset. It sizes the render target to {@value #WIDTH_PX} ×
     * {@value #HEIGHT_PX}, sets the scene's Dice, Dice Set, Tray, Light Rig, camera and preset as it goes, and takes
     * over the render thread's GPU-time listener (left unset when it returns). Blocks the caller, which must not be
     * the render thread (nor, in the app, the JavaFX thread, which presents the frames).
     *
     * @param renderer the running render thread
     * @param scene the scene it draws
     * @return what the bench found
     * @throws IllegalStateException if a frame's time didn't come back in time
     */
    public static Report run(RenderThread renderer, DiceScene scene) {
        return run(renderer, scene, scenes(), RAMP_FRAMES, WARM_UP_FRAMES, SEARCH_FRAMES, MEASURE_FRAMES);
    }

    /**
     * {@link #run(RenderThread, DiceScene)} over given candidates and frame counts, for tests (CI's software
     * rasteriser takes a good fraction of a second per 1080p frame).
     *
     * @param renderer the running render thread
     * @param scene the scene it draws
     * @param candidates the scenes to search
     * @param rampFrames frames drawn uncounted before the search, while the GPU comes up to its working clocks
     * @param warmUpFrames frames drawn uncounted after each change of scene or preset
     * @param searchFrames frames counted per candidate
     * @param measureFrames frames counted for each finalist at Medium and for the dearest at the other presets
     * @return what the bench found
     */
    static Report run(RenderThread renderer, DiceScene scene, List<Scene> candidates, int rampFrames,
            int warmUpFrames, int searchFrames, int measureFrames) {
        renderer.resize(WIDTH_PX, HEIGHT_PX);
        scene.setDice(dice());
        // Thrown away: an idle GPU takes a moment to clock up, and the first candidate measured would otherwise look
        // dearer than it is (the bench once picked a whole-Tray shot at 0.66 ms that re-measured at 0.38).
        measure(renderer, scene, candidates.getFirst(), BUDGETED_PRESET, 0, rampFrames);
        List<GpuTime> search = new ArrayList<>();
        for (Scene candidate : candidates) {
            search.add(measure(renderer, scene, candidate, BUDGETED_PRESET, warmUpFrames, searchFrames));
        }
        // The search's short medians are a few hundredths apart at the top, about the run-to-run noise, so the
        // dearest few are measured again over many more frames and the dearest of those is the scene.
        GpuTime dearest = null;
        for (GpuTime finalist : search.stream().sorted(Comparator.comparingDouble(GpuTime::millis).reversed())
                .limit(FINALISTS).toList()) {
            GpuTime again = measure(renderer, scene, finalist.scene(), BUDGETED_PRESET, warmUpFrames, measureFrames);
            if (dearest == null || again.millis() > dearest.millis()) {
                dearest = again;
            }
        }
        Map<QualityPreset, GpuTime> byPreset = new EnumMap<>(QualityPreset.class);
        byPreset.put(BUDGETED_PRESET, dearest);
        for (QualityPreset preset : QualityPreset.values()) {
            if (preset != BUDGETED_PRESET) {
                byPreset.put(preset, measure(renderer, scene, dearest.scene(), preset, warmUpFrames, measureFrames));
            }
        }
        return new Report(search, byPreset);
    }

    /** Draws one scene at one preset and times it: {@code warmUp} frames uncounted, then {@code frames} counted. */
    private static GpuTime measure(RenderThread renderer, DiceScene scene, Scene candidate, QualityPreset preset,
            int warmUp, int frames) {
        scene.setDiceSet(candidate.diceSet());
        scene.setTray(candidate.tray());
        scene.setLightRig(candidate.lightRig());
        scene.setCamera(candidate.camera());
        scene.setQualityPreset(preset);
        // Set before the first frame of the scene is drawn; the warm-up's times are dropped below, which also drops
        // any late result of a frame drawn before the change.
        List<Double> times = new CopyOnWriteArrayList<>();
        renderer.setOnGpuTime(times::add);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FRAME_TIMEOUT_SECONDS);
            int wanted = warmUp + frames;
            while (times.size() < wanted) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("the GPU bench timed " + times.size() + " of " + wanted
                            + " frames of " + candidate.describe() + " in " + FRAME_TIMEOUT_SECONDS + " s");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("the GPU bench was interrupted");
                }
                // Asked for over and over, so the render thread draws back to back. Requests coalesce into one
                // pending frame and one pending wake-up, so none pile up; Thread.sleep would be no help, as on
                // Windows it may sleep a whole 15.6 ms timer tick.
                renderer.requestFrame();
                Thread.onSpinWait();
            }
        } finally {
            renderer.setOnGpuTime(null);
        }
        // A snapshot: the render thread may still be adding a result it began handing over before the listener was
        // unset.
        return GpuTime.of(candidate, preset, List.copyOf(times).subList(warmUp, warmUp + frames));
    }

    /**
     * Runs the GPU bench alone, with a render thread of its own whose frames go nowhere, prints the report and exits
     * non-zero if Medium misses the budget. The app's {@code --bench} also times Enter to first motion.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        PacingTransport nowhere = new PacingTransport();
        DiceScene scene = new DiceScene();
        Report report;
        try (RenderThread renderer = RenderThread.start(nowhere, scene)) {
            System.out.println("GPU bench on " + renderer.driver());
            report = run(renderer, scene);
        } catch (GlUnavailableException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        report.print(System.out);
        System.exit(report.meetsBudget() ? 0 : 1);
    }
}
