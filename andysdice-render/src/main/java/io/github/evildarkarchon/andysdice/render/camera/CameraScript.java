package io.github.evildarkarchon.andysdice.render.camera;

import io.github.evildarkarchon.andysdice.render.DiceScene;
import java.util.List;
import java.util.Objects;

/**
 * What the automatic camera does over one Roll's chain of Throws, on the chain's own timeline (seconds since
 * the first Throw's Release): a list of {@link Cue}s, each easing the camera to a {@link Shot}, and when each
 * Throw starts, which is when the Player's orbit and zoom ease back to the automatic camera (spec §3.2).
 *
 * <p>Immutable and safe on any thread.
 *
 * @param cues the cues, in time order
 * @param throwStarts when each Throw's Release is, in time order
 */
public record CameraScript(List<Cue> cues, List<Double> throwStarts) {

    /** A framing the automatic camera eases to. */
    public sealed interface Shot {

        /**
         * Returns the pose this shot frames.
         *
         * @param framing the Tray's framing
         * @param aspect the view's width over its height
         * @return the pose
         */
        CameraPose pose(Framing framing, double aspect);

        /** The whole Tray from the fixed 3/4 view, as while Dice tumble ({@link Framing#wholeTray}). */
        record WholeTray() implements Shot {
            @Override
            public CameraPose pose(Framing framing, double aspect) {
                return framing.wholeTray(aspect);
            }
        }

        /**
         * The close-up on Dice at rest ({@link Framing#resting}).
         *
         * @param dice the Dice as they lie, in the renderer's mm
         */
        record Resting(List<DiceScene.Die> dice) implements Shot {

            /** Copies the Dice, so the shot can't change after it is made. */
            public Resting {
                dice = List.copyOf(dice);
            }

            @Override
            public CameraPose pose(Framing framing, double aspect) {
                return framing.resting(dice, aspect);
            }
        }
    }

    /**
     * From a moment of the chain, ease from wherever the camera is to a shot.
     *
     * @param time when the ease starts, in seconds on the chain's timeline
     * @param shot where it ends
     * @param seconds how long it takes; 0 is a cut
     */
    public record Cue(double time, Shot shot, double seconds) {

        /**
         * Checks the parts.
         *
         * @throws NullPointerException if the shot is {@code null}
         * @throws IllegalArgumentException if a time is negative or not finite
         */
        public Cue {
            Objects.requireNonNull(shot, "shot");
            if (!(time >= 0) || !Double.isFinite(time) || !(seconds >= 0) || !Double.isFinite(seconds)) {
                throw new IllegalArgumentException("A cue's time and length must be finite and not negative: "
                        + time + ", " + seconds);
            }
        }
    }

    /**
     * Checks both lists are in time order and copies them.
     *
     * @throws IllegalArgumentException if either is out of order, or a Throw start is negative or not finite
     */
    public CameraScript {
        cues = List.copyOf(cues);
        throwStarts = List.copyOf(throwStarts);
        for (int i = 1; i < cues.size(); i++) {
            if (cues.get(i).time() < cues.get(i - 1).time()) {
                throw new IllegalArgumentException("Cues must be in time order: " + cues);
            }
        }
        for (int i = 0; i < throwStarts.size(); i++) {
            double start = throwStarts.get(i);
            if (!(start >= 0) || !Double.isFinite(start) || (i > 0 && start < throwStarts.get(i - 1))) {
                throw new IllegalArgumentException("Throw starts must be in time order and not negative: "
                        + throwStarts);
            }
        }
    }
}
