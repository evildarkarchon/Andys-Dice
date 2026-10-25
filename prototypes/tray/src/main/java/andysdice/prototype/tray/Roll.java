package andysdice.prototype.tray;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * PROTOTYPE. One Roll as the renderer plays it: the first Throw, then any Reroll Throws (ADR-0006), each
 * a {@link Segment} holding a pre-simulated recording of just the dice that move. Immutable: a reroll
 * makes a new Roll with one more segment, so the render thread never sees a half-built one.
 */
record Roll(List<DieKind> kinds, List<Segment> segments) {
    /**
     * One Throw or Reroll Throw.
     *
     * @param moving    indices into {@link #kinds} of the dice this segment throws
     * @param before    every die's pose when the segment starts (null for the first Throw)
     * @param pickup    how the moving dice leave their resting places (null for the first Throw)
     * @param startNanos {@link System#nanoTime()} at which the segment starts playing
     */
    record Segment(int[] moving, Throws.Result result, Throws.Pose[] before, Params.Pickup pickup, long startNanos) {
        /** Seconds spent getting the rerolled dice out of the way before the recording starts. */
        float pickupSeconds() {
            if (pickup == null) return 0;
            return switch (pickup) {
                case LIFT -> 0.7f;
                case POP -> 0.45f;
                case GLOW_LIFT -> 1.3f;
            };
        }

        /** Seconds from the segment's start until its last die is at rest. */
        float endSeconds() {
            return pickupSeconds() + result.rec().restTime();
        }

        int slot(int die) {
            for (int j = 0; j < moving.length; j++) if (moving[j] == die) return j;
            return -1;
        }
    }

    /** One die this frame: pose, scale (POP shrinks it away) and highlight (GLOW_LIFT). */
    record DieState(Throws.Pose pose, float scale, float glow, boolean moving) {
    }

    Segment last() {
        return segments.getLast();
    }

    /** Every die's resting pose at the end of the last segment. */
    Throws.Pose[] restPoses() {
        Segment s = last();
        Throws.Pose[] out = new Throws.Pose[kinds.size()];
        for (int i = 0; i < out.length; i++) {
            int j = s.slot(i);
            out[i] = j >= 0 ? s.result().rec().sample(j, 1e6f) : s.before()[i].copy();
        }
        return out;
    }

    /** The Face Value each die shows at the end of the last segment. */
    List<String> faces() {
        List<String> out = new ArrayList<>();
        Throws.Pose[] rest = restPoses();
        for (int i = 0; i < rest.length; i++) {
            String[] f = new String[1];
            Throws.upFace(kinds.get(i), rest[i].rot(), f);
            out.add(f[0]);
        }
        return out;
    }

    /** The state of every die {@code t} seconds into the last segment. */
    List<DieState> stateAt(float t) {
        Segment s = last();
        float p = s.pickupSeconds();
        List<DieState> out = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            int j = s.slot(i);
            if (j < 0) {
                out.add(new DieState(s.before()[i], 1, 0, false));
                continue;
            }
            Throws.Pose release = s.result().rec().sample(j, 0);
            if (t >= p) {
                float tt = t - p;
                // POP: the die re-enters with the throw, growing back over its first fifth of a second.
                float scale = s.pickup() == Params.Pickup.POP ? Math.min(1, tt / 0.2f) : 1;
                out.add(new DieState(s.result().rec().sample(j, tt), scale, 0, true));
                continue;
            }
            Throws.Pose from = s.before()[i];
            switch (s.pickup()) {
                case LIFT -> out.add(new DieState(arc(from, release, t / p), 1, 0, true));
                case POP -> {
                    float k = 1 - Math.min(1, t / 0.25f);
                    out.add(new DieState(from, k * k, 0, true));
                }
                case GLOW_LIFT -> {
                    float glowEnd = 0.6f;
                    if (t < glowEnd) {
                        float g = (float) (0.5 - 0.5 * Math.cos(t / glowEnd * 2 * Math.PI * 1.5));
                        out.add(new DieState(from, 1, g, true));
                    } else {
                        out.add(new DieState(arc(from, release, (t - glowEnd) / (p - glowEnd)), 1, 0.4f * (1 - (t - glowEnd) / (p - glowEnd)), true));
                    }
                }
            }
        }
        return out;
    }

    /** A hand-like lift: up out of the tray first, over to the release point, eased at both ends. */
    private static Throws.Pose arc(Throws.Pose from, Throws.Pose to, float u) {
        float e = u * u * (3 - 2 * u);
        Vector3f p = new Vector3f(from.pos()).lerp(to.pos(), e);
        p.y += (float) Math.sin(Math.PI * Math.min(1, u * 1.3)) * 55;
        Quaternionf q = new Quaternionf(from.rot()).slerp(to.rot(), e);
        return new Throws.Pose(p, q);
    }
}
