package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.render.QualityPreset;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The slow-GPU Notice (ADR-0014, spec §3.2 "Notices"): during playback the render thread reports each frame's GPU
 * time, and if every frame drawn across a Throw (from its Release until its Dice rest) took more than
 * {@value #THRESHOLD_MILLIS} ms, one Notice behind the bell suggests the next lower Quality Preset. Once per run.
 *
 * <p>It only ever reads the preset, to name the one below it: the preset is the Player's persisted choice and is
 * never changed automatically (ADR-0014, ADR-0007). Under Low, which has nothing below it, nothing is posted and the
 * Notice is still owed.
 *
 * <p>A Throw needs at least {@value #MIN_FRAMES} measured frames to be judged, so a Throw played in a few frames
 * (Fast-forward at ×4, a short tumble) can't post it on a handful of samples. Frame times arrive a frame or two
 * late, so the first few frames counted toward a Throw were drawn just before its Release, from the same scene.
 *
 * <p>Thread contract: {@link #frame} is called on the render thread and {@link #showing} on the JavaFX Application
 * Thread, so both are synchronized; the Notice is posted from whichever thread ends the Throw, which
 * {@link Notices#post} allows.
 */
final class SlowGpuWatch {

    /** A frame slower than this, in GPU milliseconds, is slow (spec §4: a tunable). */
    static final double THRESHOLD_MILLIS = 14.0;

    /** The fewest measured frames a Throw is judged on (a tunable). */
    static final int MIN_FRAMES = 8;

    private final Consumer<Notice> post;
    private final Supplier<QualityPreset> preset;

    // The Throw in motion now, or null; its measured frames so far, and whether any was fast.
    private Object current;
    private int frames;
    private boolean anyFast;
    private boolean posted;

    /**
     * Makes a watch that has seen no Throw yet.
     *
     * @param post where the Notice goes ({@code notices::post} in the app)
     * @param preset the Quality Preset in force, read when a slow Throw ends
     */
    SlowGpuWatch(Consumer<Notice> post, Supplier<QualityPreset> preset) {
        this.post = Objects.requireNonNull(post, "post");
        this.preset = Objects.requireNonNull(preset, "preset");
    }

    /**
     * Counts one frame's GPU time toward the Throw in motion; ignored while none is.
     *
     * @param gpuMillis the frame's GPU time in milliseconds
     */
    synchronized void frame(double gpuMillis) {
        if (current == null) {
            return;
        }
        frames++;
        if (!(gpuMillis > THRESHOLD_MILLIS)) {
            anyFast = true;
        }
    }

    /**
     * Says which Throw is in motion at the frame being drawn. A change ends the Throw that was (judging it) and
     * starts counting for the new one.
     *
     * @param throwInMotion anything that identifies the Throw by {@code equals}, or {@code null} when no Throw is in
     *     motion (between Throws, at rest, idle)
     */
    synchronized void showing(Object throwInMotion) {
        if (Objects.equals(throwInMotion, current)) {
            return;
        }
        if (current != null) {
            judge();
        }
        current = throwInMotion;
        frames = 0;
        anyFast = false;
    }

    /** Posts the Notice if the Throw just ended was slow throughout, it is still owed, and there is a lower preset. */
    private void judge() {
        if (posted || frames < MIN_FRAMES || anyFast) {
            return;
        }
        Optional<QualityPreset> lower = preset.get().lower();
        if (lower.isEmpty()) {
            return;
        }
        posted = true;
        post.accept(new Notice("Slow graphics",
                "Each frame of the last Throw took the graphics card over " + Math.round(THRESHOLD_MILLIS)
                        + " ms to draw. The " + lower.get().displayName()
                        + " Quality Preset, in the Settings tab, may play more smoothly."));
    }
}
