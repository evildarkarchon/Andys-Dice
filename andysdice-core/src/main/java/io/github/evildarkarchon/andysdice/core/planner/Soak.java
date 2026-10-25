package io.github.evildarkarchon.andysdice.core.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The soak: its workload and its gates (spec §4, ADR-0005, ADR-0013, ADR-0014). The {@code slow}-tagged soak
 * test in {@code physics} throws {@link #EXPRESSION} {@link #THROWS_PER_TRAY} times into each built-in Tray
 * through the real planner and Jolt, and fails on any of {@link #violations}. The gates are tunables, so the
 * build may change them here, and only here.
 */
public final class Soak {

    /** Throws per built-in Tray (spec §4: a tunable gate, ADR-0014). */
    public static final int THROWS_PER_TRAY = 1_000;

    /** The most the 95th percentile of simulated time from Release to rest may be, in seconds (ADR-0014). */
    public static final float P95_REST_SECONDS = 1.5f;

    /** The most the mean Attempts per Throw may be (ADR-0013). */
    public static final double MEAN_ATTEMPTS = 2.5;

    /** How many Cocked Rethrows a whole run may need (ADR-0013: none). */
    public static final int COCKED_RETHROWS = 0;

    /**
     * The Roll every soak Throw makes: the flat-rest prototype's 20-Dice cap mix ({@code Params.DICE[5]}: d20 ×3,
     * d12 ×2, d10 ×2, a percentile d10, d8 ×3, d6 ×6, d4 ×3), whose percentile d10 and one d10 are a Percentile
     * Roll here. No rerolls, so each Roll is one first Throw of exactly the Dice-per-Throw cap.
     */
    public static final String EXPRESSION = "3d20+2d12+d10+d%+3d8+6d6+3d4";

    private Soak() {
    }

    /**
     * Returns every gate a run missed. A Failed Roll misses a gate too: nothing of it could have been shown.
     *
     * @param stats the run's statistics
     * @return one line per missed gate; empty if the run passes
     */
    public static List<String> violations(ThrowStatistics stats) {
        List<String> missed = new ArrayList<>();
        if (stats.throwCount() < THROWS_PER_TRAY) {
            missed.add(format("ran %d Throws; the soak needs %d", stats.throwCount(), THROWS_PER_TRAY));
        }
        // Negated comparisons, so a NaN (no Throws at all) also misses the gate.
        if (!(stats.p95RestTime() <= P95_REST_SECONDS)) {
            missed.add(format("p95 time to rest %.3f s exceeds %.1f s", stats.p95RestTime(), P95_REST_SECONDS));
        }
        if (!(stats.meanAttempts() <= MEAN_ATTEMPTS)) {
            missed.add(format("mean Attempts %.2f exceeds %.1f", stats.meanAttempts(), MEAN_ATTEMPTS));
        }
        if (stats.cockedRethrows() > COCKED_RETHROWS) {
            missed.add(format("%d Cocked Rethrow(s); the soak allows %d", stats.cockedRethrows(), COCKED_RETHROWS));
        }
        if (stats.failedRolls() > 0) {
            missed.add(format("%d Failed Roll(s)", stats.failedRolls()));
        }
        return missed;
    }

    /**
     * Returns a one-line report of a run's measured numbers beside its gates.
     *
     * @param tray the name of the Tray the run threw into
     * @param stats the run's statistics
     * @return the report line
     */
    public static String report(String tray, ThrowStatistics stats) {
        return format("%s: %d Throws, p95 time to rest %.3f s (gate <= %.1f s), mean Attempts %.2f (gate <= %.1f),"
                        + " Cocked Rethrows %d (gate <= %d), Failed Rolls %d; max Attempts %d, mean time to rest %.3f s",
                tray, stats.throwCount(), stats.p95RestTime(), P95_REST_SECONDS, stats.meanAttempts(), MEAN_ATTEMPTS,
                stats.cockedRethrows(), COCKED_RETHROWS, stats.failedRolls(), stats.maxAttempts(),
                stats.meanRestTime());
    }

    private static String format(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }
}
