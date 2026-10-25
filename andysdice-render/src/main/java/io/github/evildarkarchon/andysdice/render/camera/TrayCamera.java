package io.github.evildarkarchon.andysdice.render.camera;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;

/**
 * The settle-in camera (spec §3.2 "Camera", ADR-0010), driven by the playback clock: it frames the whole Tray
 * from a fixed 3/4 view while Dice tumble, eases in on the Dice at rest, and eases back out before a Reroll Throw
 * or Cocked Rethrow, as a {@link CameraScript} says.
 *
 * <p>Every ease runs on {@link PlaybackClock#time()}, so Fast-forward speeds the camera up with the Dice, and
 * tests move it by advancing the clock by hand. The camera keeps no time of its own: each pose is worked out
 * from the clock's time when it is asked for. With reduced motion on (the supplier, which the app reads from
 * {@code Platform.Preferences.isReducedMotion()}), every ease is a cut at the moment it would have started.
 *
 * <p>Thread contract: confined to the thread that advances the clock (the JavaFX Application Thread in the app).
 * The renderer is handed the {@link DiceScene.Camera} it returns, never the camera itself.
 */
public final class TrayCamera {

    /** How long the camera takes to ease in on the Dice at rest, in playback seconds (a tunable). */
    public static final double SETTLE_SECONDS = 0.8;

    /** How long the camera takes to ease back out to the whole Tray before a Throw, in playback seconds. */
    public static final double EASE_OUT_SECONDS = 0.5;

    /** How long the Player's orbit and zoom take to ease back once the next Throw starts (spec: about 0.4 s). */
    public static final double RETURN_SECONDS = 0.4;

    /**
     * The highest the Player can orbit: straight down, to within half a degree. {@link DiceScene.Camera} looks
     * with +y up, which is undefined exactly overhead; this close, the view still keeps its yaw's heading.
     */
    public static final double MAX_PITCH_DEGREES = 89.5;

    /** The lowest the Player can orbit: about 20° above the floor. */
    public static final double MIN_PITCH_DEGREES = 20;

    /** The nearest the Player can zoom, as a factor on the automatic camera's distance. */
    public static final double MIN_ZOOM = 0.7;

    /** The furthest the Player can zoom, as a factor on the automatic camera's distance. */
    public static final double MAX_ZOOM = 3;

    private static final double DEFAULT_ASPECT = 16.0 / 10;

    /**
     * The Player's orbit and zoom, laid over the automatic camera.
     *
     * @param yaw degrees added to the yaw, in (-180, 180]
     * @param pitch degrees added to the pitch
     * @param zoom the factor on the distance
     */
    private record Adjustment(double yaw, double pitch, double zoom) {

        static final Adjustment NONE = new Adjustment(0, 0, 1);

        /** Part of the way back to no adjustment. */
        Adjustment towardNone(double s) {
            return new Adjustment(yaw * (1 - s), pitch * (1 - s), Math.pow(zoom, 1 - s));
        }
    }

    private final PlaybackClock clock;
    private Framing framing;
    private final BooleanSupplier reducedMotion;
    private double aspect = DEFAULT_ASPECT;

    /** The script playing, and when it started on the clock. */
    private CameraScript script;
    private double scriptStart;
    /** Where the camera was when the script started; {@code null} to start on the script's first shot. */
    private CameraPose origin;

    /** The Player's last orbit or zoom, and when on the clock it was made; it eases back from the next Throw. */
    private Adjustment adjustment = Adjustment.NONE;
    private double adjustedAt;

    /**
     * Creates a camera framing the whole Tray, still.
     *
     * @param clock the playback clock every ease runs on
     * @param framing the Tray's framing
     * @param reducedMotion tells whether the system asks for reduced motion; asked each time a pose is worked out
     */
    public TrayCamera(PlaybackClock clock, Framing framing, BooleanSupplier reducedMotion) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.framing = Objects.requireNonNull(framing, "framing");
        this.reducedMotion = Objects.requireNonNull(reducedMotion, "reducedMotion");
        script = new CameraScript(List.of(new CameraScript.Cue(0, new CameraScript.Shot.WholeTray(), 0)), List.of());
        scriptStart = clock.time();
    }

    /**
     * Sets the view's shape, which every framing fits. The camera is reframed at once.
     *
     * @param widthOverHeight the view's width over its height
     * @throws IllegalArgumentException if it isn't positive and finite
     */
    public void setAspect(double widthOverHeight) {
        if (!(widthOverHeight > 0) || !Double.isFinite(widthOverHeight)) {
            throw new IllegalArgumentException("A view's aspect ratio must be positive: " + widthOverHeight);
        }
        aspect = widthOverHeight;
    }

    /**
     * Sets the Tray every shot frames, as when the Player picks another Tray (its walls may stand taller). Like
     * {@link #setAspect}, the camera is reframed at once, without an ease.
     *
     * @param tray the new Tray's framing
     */
    public void setFraming(Framing tray) {
        framing = Objects.requireNonNull(tray, "tray");
    }

    /**
     * Starts a Roll's script now, at the clock's current time. Its first cue eases from wherever the camera is,
     * so a new Roll eases out from the last one's close-up without a jump.
     *
     * @param next the script, on a timeline starting now
     */
    public void play(CameraScript next) {
        Objects.requireNonNull(next, "next");
        double now = clock.time();
        origin = automatic(now);
        // Settle any easing back under the old script, so the orbit left now eases back from the new one's start.
        adjustment = playerAt(now);
        adjustedAt = now;
        script = next;
        scriptStart = now;
    }

    /**
     * Orbits the camera around what it looks at, as a drag on the Tray does, at any time. Yaw is free all the
     * way round; pitch stops at {@value #MIN_PITCH_DEGREES}° and {@value #MAX_PITCH_DEGREES}°, and dragging on
     * past a stop is not stored up. The orbit stays until the next Throw starts.
     *
     * @param yawDegrees degrees to swing the eye round, positive towards +x from the near end
     * @param pitchDegrees degrees to raise the eye, positive towards straight down
     */
    public void orbit(double yawDegrees, double pitchDegrees) {
        if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)) {
            throw new IllegalArgumentException("An orbit must be finite: " + yawDegrees + ", " + pitchDegrees);
        }
        double now = clock.time();
        Adjustment current = playerAt(now);
        double automaticPitch = automatic(now).pitchDegrees();
        double pitch = clampPitch(clampPitch(automaticPitch + current.pitch()) + pitchDegrees);
        adjustment = new Adjustment(wrap(current.yaw() + yawDegrees), pitch - automaticPitch, current.zoom());
        adjustedAt = now;
    }

    /**
     * Zooms the camera, as the wheel does, at any time: the distance is multiplied by the factor, within
     * {@value #MIN_ZOOM}× to {@value #MAX_ZOOM}× of the automatic camera's. The zoom stays until the next Throw
     * starts.
     *
     * @param factor what to multiply the distance by: below 1 moves in, above 1 moves out
     * @throws IllegalArgumentException if it isn't positive and finite
     */
    public void zoom(double factor) {
        if (!(factor > 0) || !Double.isFinite(factor)) {
            throw new IllegalArgumentException("A zoom factor must be positive and finite: " + factor);
        }
        double now = clock.time();
        Adjustment current = playerAt(now);
        adjustment = new Adjustment(current.yaw(), current.pitch(),
                Math.clamp(current.zoom() * factor, MIN_ZOOM, MAX_ZOOM));
        adjustedAt = now;
    }

    /** Drops the Player's orbit and zoom at once, as a double-click on the resting Tray does (no ease). */
    public void reset() {
        adjustment = Adjustment.NONE;
        adjustedAt = clock.time();
    }

    /**
     * Tells whether the Player's orbit or zoom is in effect now, including while it eases back.
     *
     * @return {@code false} once the camera is the automatic one
     */
    public boolean adjusted() {
        return !playerAt(clock.time()).equals(Adjustment.NONE);
    }

    /**
     * Returns where the camera is now: the automatic camera with the Player's orbit and zoom laid over it.
     *
     * @return the pose at the clock's current time
     */
    public CameraPose pose() {
        double now = clock.time();
        CameraPose automatic = automatic(now);
        Adjustment player = playerAt(now);
        if (player.equals(Adjustment.NONE)) {
            return automatic;
        }
        return new CameraPose(automatic.target(), automatic.yawDegrees() + player.yaw(),
                clampPitch(automatic.pitchDegrees() + player.pitch()), automatic.distance() * player.zoom());
    }

    /**
     * Returns the camera as the renderer takes it.
     *
     * @return the pose at the clock's current time, as a {@link DiceScene.Camera}
     */
    public DiceScene.Camera camera() {
        return pose().camera();
    }

    /**
     * Tells whether the camera has more moving to do: an ease under way or a cue still to come. Playback keeps
     * the clock running until it hasn't, so the settle-in after the last Throw rests is seen.
     *
     * @return {@code true} while the camera would look different at a later time
     */
    public boolean moving() {
        double now = clock.time();
        boolean cut = reducedMotion.getAsBoolean();
        for (CameraScript.Cue cue : script.cues()) {
            if (scriptStart + cue.time() + (cut ? 0 : cue.seconds()) > now) {
                return true;
            }
        }
        if (adjustment.equals(Adjustment.NONE)) {
            return false;
        }
        // An orbit waiting for a Throw still to come, or easing back from one.
        double returnStart = returnStart();
        return !Double.isNaN(returnStart) && returnStart + (cut ? 0 : RETURN_SECONDS) > now;
    }

    /** The Player's orbit and zoom at a time: as left, or easing back once a Throw has started since. */
    private Adjustment playerAt(double time) {
        if (adjustment.equals(Adjustment.NONE)) {
            return adjustment;
        }
        double returnStart = returnStart();
        if (Double.isNaN(returnStart) || returnStart > time) {
            return adjustment;
        }
        double progress = reducedMotion.getAsBoolean() ? 1 : (time - returnStart) / RETURN_SECONDS;
        return progress >= 1 ? Adjustment.NONE : adjustment.towardNone(smoothstep(progress));
    }

    /** When the first Throw at or after the last orbit or zoom starts, on the clock; NaN if none is to come. */
    private double returnStart() {
        for (double start : script.throwStarts()) {
            if (scriptStart + start >= adjustedAt) {
                return scriptStart + start;
            }
        }
        return Double.NaN;
    }

    private static double clampPitch(double degrees) {
        return Math.clamp(degrees, MIN_PITCH_DEGREES, MAX_PITCH_DEGREES);
    }

    /** The automatic camera at a time: each cue in turn eases from where the cue before it had got to. */
    private CameraPose automatic(double time) {
        boolean cut = reducedMotion.getAsBoolean();
        CameraPose from = origin;
        CameraScript.Cue easing = null;
        for (CameraScript.Cue cue : script.cues()) {
            double start = scriptStart + cue.time();
            if (start > time) {
                break;
            }
            if (easing != null) {
                from = ease(from, easing, start, cut);
            }
            easing = cue;
        }
        if (easing == null) {
            return from != null ? from : framing.wholeTray(aspect);
        }
        return ease(from, easing, time, cut);
    }

    /** Where one cue's ease has got to at a time, starting from a pose ({@code null}: already at its shot). */
    private CameraPose ease(CameraPose from, CameraScript.Cue cue, double time, boolean cut) {
        CameraPose to = cue.shot().pose(framing, aspect);
        if (from == null || cut || cue.seconds() == 0) {
            return to;
        }
        double progress = (time - scriptStart - cue.time()) / cue.seconds();
        // Exactly the shot once the ease is over: interpolating at 1 would leave rounding errors in the pose.
        return progress >= 1 ? to : between(from, to, smoothstep(progress));
    }

    /** A pose part of the way from one to another: the distance geometrically, so zooming feels even. */
    private static CameraPose between(CameraPose from, CameraPose to, double s) {
        Vector3d target = new Vector3d(from.target()).lerp(to.target(), s);
        double yaw = from.yawDegrees() + wrap(to.yawDegrees() - from.yawDegrees()) * s;
        double pitch = from.pitchDegrees() + (to.pitchDegrees() - from.pitchDegrees()) * s;
        double distance = from.distance() * Math.pow(to.distance() / from.distance(), s);
        return new CameraPose(target, yaw, pitch, distance);
    }

    /** An angle in degrees brought into (-180, 180], so an ease of yaw goes the short way round. */
    static double wrap(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped > 180) {
            wrapped -= 360;
        } else if (wrapped <= -180) {
            wrapped += 360;
        }
        return wrapped;
    }

    /** Smoothstep on [0, 1], clamped: an ease that starts and ends at rest. */
    private static double smoothstep(double u) {
        double t = Math.clamp(u, 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }
}
