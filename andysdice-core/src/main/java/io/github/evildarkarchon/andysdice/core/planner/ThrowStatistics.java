package io.github.evildarkarchon.andysdice.core.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * What a run of planned Rolls took to come to rest, as the soak measures it (ADR-0013, ADR-0014): the Attempts
 * each Throw needed, its simulated time from Release to rest, how many Cocked Rethrows were needed and how many
 * Rolls failed. The flat-rest prototype's statistics harness, over the real planner.
 *
 * <p>A Throw here is one the Roll asked for: its first Throw or a Reroll Throw. A Cocked Rethrow is the fallback
 * of the Throw before it, so it is counted on its own and adds no Throw; the Throw it follows keeps its own
 * Attempts and rest time. A Failed Roll adds no Throw either, since nothing of it would be shown.
 *
 * <p>Not thread-safe.
 */
public final class ThrowStatistics {

    /** The percentile the soak gates the time to rest on (ADR-0014). */
    private static final double REST_PERCENTILE = 0.95;

    private final List<Integer> attempts = new ArrayList<>();
    private final List<Float> restTimes = new ArrayList<>();
    private int cockedRethrows;
    private int failedRolls;

    /** Creates empty statistics. */
    public ThrowStatistics() {
    }

    /**
     * Adds one planned Roll: each of its Throws, its Cocked Rethrows, or its failure.
     *
     * @param plan the planner's answer for one Roll
     * @throws IllegalArgumentException if the plan is an invalid Roll Expression, which no soak should roll
     */
    public void add(RollPlan plan) {
        Objects.requireNonNull(plan, "plan");
        switch (plan) {
            case RollPlan.Invalid(var error) ->
                    throw new IllegalArgumentException("Not a Roll to measure: the expression is invalid: " + error);
            case RollPlan.Failed failed -> failedRolls++;
            case RollPlan.Planned planned -> {
                for (PlannedThrow step : planned.throwChain()) {
                    if (step.kind() == ThrowKind.COCKED_RETHROW) {
                        cockedRethrows++;
                    } else {
                        attempts.add(step.attempts());
                        restTimes.add(step.recording().restTime());
                    }
                }
            }
        }
    }

    /**
     * Returns how many Throws were measured.
     *
     * @return first Throws and Reroll Throws of every planned Roll
     */
    public int throwCount() {
        return attempts.size();
    }

    /**
     * Returns the mean Attempts per Throw.
     *
     * @return the mean, or NaN with no Throws
     */
    public double meanAttempts() {
        return attempts.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN);
    }

    /**
     * Returns the most Attempts any one Throw needed.
     *
     * @return the maximum, or 0 with no Throws
     */
    public int maxAttempts() {
        return attempts.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * Returns the 95th percentile of the Throws' simulated time from Release to rest, by nearest rank (the
     * smallest time at least 95% of Throws rested by), as the flat-rest prototype reported it.
     *
     * @return the time in seconds, or NaN with no Throws
     */
    public float p95RestTime() {
        if (restTimes.isEmpty()) {
            return Float.NaN;
        }
        float[] sorted = new float[restTimes.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = restTimes.get(i);
        }
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * REST_PERCENTILE) - 1];
    }

    /**
     * Returns the mean simulated time from Release to rest.
     *
     * @return the mean in seconds, or NaN with no Throws
     */
    public double meanRestTime() {
        return restTimes.stream().mapToDouble(Float::doubleValue).average().orElse(Double.NaN);
    }

    /**
     * Returns how many Cocked Rethrows the Rolls needed, in all.
     *
     * @return the count
     */
    public int cockedRethrows() {
        return cockedRethrows;
    }

    /**
     * Returns how many Rolls failed.
     *
     * @return the count of Failed Rolls
     */
    public int failedRolls() {
        return failedRolls;
    }
}
