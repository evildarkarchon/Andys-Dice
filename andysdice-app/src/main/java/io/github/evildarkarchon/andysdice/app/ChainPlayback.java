package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.geometry.NumberingRotation;
import io.github.evildarkarchon.andysdice.core.planner.PlannedThrow;
import io.github.evildarkarchon.andysdice.core.planner.RollPlan;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.ThrowPlayback;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A planned Roll's whole chain of Throws laid out on one playback timeline (spec §3.1 step 4): the first Throw,
 * then each Reroll Throw and Cocked Rethrow in the planner's order, each played through its own
 * {@link ThrowPlayback}. While a Throw plays, every Die it doesn't throw is drawn exactly where the last Throw
 * that moved it left it, turned by that Throw's Numbering Rotation, so a Frozen Die never moves on screen, struck
 * or not (ADR-0006: the simulator holds it static too).
 *
 * <p>Each Throw starts {@value #HOLD_SECONDS} s after the one before it came to rest, so the Dice are seen at
 * rest before the next Throw takes some of them up. That hold is a stand-in: #47's glow and lift fill the gap
 * between Throws. The Roll is at rest at {@link #restTime()}, when the last Throw's Dice are.
 *
 * <p>Immutable and safe on any thread.
 */
final class ChainPlayback {

    /** The pause between one Throw at rest and the next Throw's Release, until #47's reroll pickup replaces it. */
    static final double HOLD_SECONDS = 0.3;

    private final RollPlan.Planned plan;
    private final List<ThrowPlayback> throwPlaybacks;
    /** When each Throw's Release is, on the chain's timeline. */
    private final double[] starts;
    /** Where every Die lies as each Throw starts; null for a Die no Throw has moved yet. */
    private final List<List<DiceScene.Die>> lyingAtStart;
    private final double restTime;

    /**
     * Lays out a planned Roll's chain.
     *
     * @param plan the planned Roll
     */
    ChainPlayback(RollPlan.Planned plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
        List<PlannedThrow> chain = plan.throwChain();
        List<ThrowPlayback> playbacks = new ArrayList<>(chain.size());
        List<List<DiceScene.Die>> lying = new ArrayList<>(chain.size());
        starts = new double[chain.size()];
        DiceScene.Die[] now = new DiceScene.Die[plan.dice().size()];
        double start = 0;
        for (int k = 0; k < chain.size(); k++) {
            PlannedThrow planned = chain.get(k);
            ThrowPlayback playback = new ThrowPlayback(planned.recording(),
                    planned.thrown().stream().map(die -> plan.dice().get(die).kind()).toList(),
                    planned.rotations().stream().map(NumberingRotation::rotation).toList());
            playbacks.add(playback);
            starts[k] = start;
            // A snapshot (List.copyOf refuses the nulls of Dice not yet thrown, which only the first Throw has).
            lying.add(Collections.unmodifiableList(Arrays.asList(now.clone())));
            // The Recording's last sample is where each thrown Die lies from then on (sleeping Dice don't move).
            // Read it well past the end: duration() is a float, and reading at it can land a hair short of the
            // last sample and interpolate, so the Die would jump by a rounding error when the next Throw starts.
            List<DiceScene.Die> ended = playback.diceAt(planned.recording().duration() + 1);
            for (int slot = 0; slot < ended.size(); slot++) {
                now[planned.thrown().get(slot)] = ended.get(slot);
            }
            if (k < chain.size() - 1) {
                start += planned.recording().restTime() + HOLD_SECONDS;
            }
        }
        throwPlaybacks = List.copyOf(playbacks);
        lyingAtStart = List.copyOf(lying);
        restTime = starts[chain.size() - 1] + chain.getLast().recording().restTime();
    }

    /**
     * Returns the plan this plays.
     *
     * @return the planned Roll
     */
    RollPlan.Planned plan() {
        return plan;
    }

    /**
     * Returns when the whole Roll is at rest: the last Throw's rest time on the chain's timeline.
     *
     * @return seconds since the first Throw's Release
     */
    double restTime() {
        return restTime;
    }

    /**
     * Returns when a Throw's Release is on the chain's timeline.
     *
     * @param throwIndex an index into the plan's Throw chain
     * @return seconds since the first Throw's Release
     */
    double throwStart(int throwIndex) {
        return starts[Objects.checkIndex(throwIndex, starts.length)];
    }

    /**
     * Returns which Throw is on the Tray at a moment: the last one released by then, including the hold after it
     * comes to rest. Before the first Release it is the first Throw.
     *
     * @param time seconds since the first Throw's Release
     * @return an index into the plan's Throw chain
     */
    int throwAt(double time) {
        int k = 0;
        while (k + 1 < starts.length && starts[k + 1] <= time) {
            k++;
        }
        return k;
    }

    /**
     * Returns every Die of the Roll as it is drawn at a moment of the chain: the playing Throw's Dice
     * interpolated from its Recording, every other Die lying where it last came to rest.
     *
     * @param time seconds since the first Throw's Release; clamped to the first Throw's Release before it, and
     *             to where everything rests after {@link #restTime()}
     * @return one Die per planned Die, in the plan's Die order, in the renderer's mm
     */
    List<DiceScene.Die> diceAt(double time) {
        int k = throwAt(time);
        PlannedThrow playing = plan.throwChain().get(k);
        List<DiceScene.Die> dice = new ArrayList<>(lyingAtStart.get(k));
        List<DiceScene.Die> moving = throwPlaybacks.get(k).diceAt(time - starts[k]);
        for (int slot = 0; slot < moving.size(); slot++) {
            dice.set(playing.thrown().get(slot), moving.get(slot));
        }
        return List.copyOf(dice);
    }
}
