package io.github.evildarkarchon.andysdice.core.planner;

import io.github.evildarkarchon.andysdice.core.expression.FaceValueChain;
import io.github.evildarkarchon.andysdice.core.expression.ParseError;
import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.expression.RollResult;
import java.util.List;
import java.util.Objects;

/**
 * What the Roll pipeline gives for a Roll Expression (spec §3.1), all worked out before anything moves: the
 * expression didn't parse, the Roll failed, or the whole Roll is planned.
 */
public sealed interface RollPlan permits RollPlan.Invalid, RollPlan.Failed, RollPlan.Planned {

    /**
     * The Roll Expression is invalid, so nothing was chosen or simulated.
     *
     * @param error the parse error, with its span
     */
    record Invalid(ParseError error) implements RollPlan {

        /** Checks the error is present. */
        public Invalid {
            Objects.requireNonNull(error, "error");
        }
    }

    /**
     * A Failed Roll: it has no Roll Result, nothing of it is shown, and it never joins the Roll History.
     *
     * @param expression the Roll Expression that failed
     * @param reason     why it failed
     */
    record Failed(RollExpression expression, FailureReason reason) implements RollPlan {

        /** Checks both parts are present. */
        public Failed {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * A planned Roll: every Die, every Face Value Chain, the whole chain of Throws with their Recordings, and the
     * Roll Result, ready to play.
     *
     * @param expression the Roll Expression rolled
     * @param dice       the physical Dice in the Tray, in roll order (a Percentile Roll's percentile d10, then its
     *                   d10); every Throw indexes into this list
     * @param chains     each roll's Face Value Chain, in the evaluator's order
     * @param throwChain the Throws in the order they play: the first Throw, then Reroll Throws, each followed by
     *                   any Cocked Rethrows it needed
     * @param result     the Roll Result of the chains
     */
    record Planned(RollExpression expression, List<PlannedDie> dice, List<FaceValueChain> chains,
            List<PlannedThrow> throwChain, RollResult result) implements RollPlan {

        /**
         * Copies the lists and checks the chain starts with a first Throw.
         *
         * @throws IllegalArgumentException if the chain is empty or doesn't start with a first Throw
         */
        public Planned {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(result, "result");
            dice = List.copyOf(dice);
            chains = List.copyOf(chains);
            throwChain = List.copyOf(throwChain);
            if (throwChain.isEmpty() || throwChain.getFirst().kind() != ThrowKind.FIRST) {
                throw new IllegalArgumentException("a planned Roll's chain starts with its first Throw");
            }
        }

        /**
         * Returns how many Cocked Rethrows the Roll needed, across every Throw (the soak gates on zero).
         *
         * @return the count of {@link ThrowKind#COCKED_RETHROW} Throws
         */
        public int cockedRethrows() {
            return (int) throwChain.stream().filter(t -> t.kind() == ThrowKind.COCKED_RETHROW).count();
        }
    }
}
