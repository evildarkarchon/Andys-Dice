package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The Release conditions of an Attempt: how its Dice leave the hand.
 *
 * <p>A Throw's Attempts normally use {@link Drawn}: the simulator draws each Attempt's Release from the
 * Release generator (ADR-0003), never the Face Value generator, so a retry never shifts a later Face Value.
 * {@link Given} states the conditions exactly, for scenario tests that need a particular Throw.
 */
public sealed interface Release {

    /**
     * A Release the simulator draws from the Release generator, following ADR-0013's one-layer, gentle
     * handful over the near end of the Tray.
     *
     * <p>The simulator advances the generator, so passing the same generator to successive Attempts gives
     * each its own Release. Generators aren't thread-safe; the caller must not use it while the simulator
     * runs.
     *
     * @param generator the Release generator
     */
    record Drawn(RandomGenerator generator) implements Release {

        /**
         * Checks the generator is present.
         *
         * @throws NullPointerException if it is {@code null}
         */
        public Drawn {
            Objects.requireNonNull(generator, "generator");
        }
    }

    /**
     * A Release stated exactly: one start per thrown Die, in the request's order.
     *
     * @param starts each thrown Die's start
     */
    record Given(List<DieStart> starts) implements Release {

        /** Copies the starts. */
        public Given {
            starts = List.copyOf(starts);
        }
    }
}
