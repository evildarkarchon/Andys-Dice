package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;

/**
 * When each Die of a Recording came to rest, read from its samples.
 *
 * <p>The Recording carries one rest time for the whole Throw, not one per Die. A Die that has gone to sleep
 * doesn't move (physics.md: sleepers' poses are unchanged), and an awake Die lying still moves by far less
 * than {@link #STILL}, so a Die is at rest from the first sample after which its pose stays within that of
 * its final pose. A Die struck back into motion moves again, so its rest is the later one.
 */
final class DieRest {

    /** How far, in cm and in quaternion components, a pose may differ from the final one and count as still. */
    static final float STILL = 1e-4f;

    private DieRest() {
    }

    /**
     * Returns each Die's rest time.
     *
     * @param recording the Recording
     * @return per Die, in the Recording's order, the time in seconds of the first sample from which it lay
     *     still; never after the Recording's rest time
     */
    static float[] of(Recording recording) {
        float[] rests = new float[recording.dice()];
        for (int die = 0; die < rests.length; die++) {
            Pose last = recording.finalPose(die);
            int sample = recording.samples() - 1;
            while (sample > 0 && still(recording.pose(die, sample - 1), last)) {
                sample--;
            }
            rests[die] = Math.min(recording.sampleTime(sample), recording.restTime());
        }
        return rests;
    }

    private static boolean still(Pose pose, Pose last) {
        return close(pose.position().x(), last.position().x())
                && close(pose.position().y(), last.position().y())
                && close(pose.position().z(), last.position().z())
                && close(pose.orientation().x(), last.orientation().x())
                && close(pose.orientation().y(), last.orientation().y())
                && close(pose.orientation().z(), last.orientation().z())
                && close(pose.orientation().w(), last.orientation().w());
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= STILL;
    }
}
