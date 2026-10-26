package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.expression.RollResult;
import io.github.evildarkarchon.andysdice.core.planner.FailureReason;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the current Roll stands, as the result strip shows it (spec §3.2): nothing rolled yet, pre-simulating,
 * playing, at rest with its Roll Result, failed, or lost when a runtime failure ended the session under it. The strip
 * reads "Rolling…" both while a Roll pre-simulates and while it plays, and nothing for a Lost Roll. A Roll Expression
 * that doesn't parse never gets this far: entry in the box shows its error and rolls nothing.
 */
sealed interface RollStatus {

    /** The text the result strip shows for this status. */
    String text();

    /** Nothing has been rolled yet: the first-run Tray is empty (spec §3.2 "First run"). */
    record Ready() implements RollStatus {
        @Override
        public String text() {
            return "Type a Roll Expression, or click a die above.";
        }
    }

    /** What the result strip shows while a Roll is under way, pre-simulating or playing alike. */
    String ROLLING = "Rolling…";

    /**
     * A Roll under way: from its start until it rests, fails or is lost, which is while it pre-simulates or plays.
     * This is the one place that says which statuses those are; camera gestures are blocked, a new Roll queues and
     * an abandoned Roll is lost exactly while the status is one.
     */
    sealed interface UnderWay extends RollStatus permits PreSimulating, Playing {

        /** @return what is being rolled */
        RollExpression expression();
    }

    /**
     * A Roll is being pre-simulated on the physics worker: its whole chain of Throws is planned before anything
     * moves, so the Tray still holds the previous Roll's Dice.
     *
     * @param expression what is being rolled
     */
    record PreSimulating(RollExpression expression) implements UnderWay {

        /** Checks the expression is present. */
        public PreSimulating {
            Objects.requireNonNull(expression, "expression");
        }

        @Override
        public String text() {
            return ROLLING;
        }
    }

    /**
     * A Roll's chain of Throws is playing back on the Tray; its Dice are moving. The dice sound starts from this.
     *
     * @param expression what is being rolled
     * @param chain the pre-simulated chain of Throws now playing
     * @param start the playback clock's time at the chain's first Release
     */
    record Playing(RollExpression expression, ChainPlayback chain, double start) implements UnderWay {

        /** Checks the parts are present. */
        public Playing {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(chain, "chain");
        }

        @Override
        public String text() {
            return ROLLING;
        }
    }

    /**
     * Every Die is at rest and the Roll Result is shown.
     *
     * @param expression what was rolled
     * @param result its Roll Result
     * @param throwCount how many Throws the Roll's chain played, every one counted: the first Throw, each Reroll
     *     Throw and each Cocked Rethrow (spec §3.2); at least 1
     * @param savedRollName the Saved Roll's name if the Roll came from one (#56), else empty
     */
    record Rested(RollExpression expression, RollResult result, int throwCount, Optional<String> savedRollName)
            implements RollStatus {

        /**
         * Checks the parts.
         *
         * @throws IllegalArgumentException if {@code throwCount} is less than 1
         */
        public Rested {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(savedRollName, "savedRollName");
            if (throwCount < 1) {
                throw new IllegalArgumentException("a Roll at rest played at least 1 Throw: " + throwCount);
            }
        }

        @Override
        public String text() {
            return expression.canonical() + " = " + result.total();
        }
    }

    /**
     * A Failed Roll: its Dice wouldn't settle, it needed too many rerolls, or the physics worker failed. Nothing
     * moved, no result is shown and the Tray keeps its previous Dice (spec §3.1).
     *
     * @param expression what was being rolled
     * @param message what the result strip shows in place of the total
     */
    record Failed(RollExpression expression, String message) implements RollStatus {

        /** Checks both parts are present. */
        public Failed {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(message, "message");
        }

        /**
         * The Failed Roll the planner reported, with the spec's message for its reason (spec §3.2).
         *
         * @param expression what was being rolled
         * @param reason why the planner failed it
         * @return the status
         */
        static Failed of(RollExpression expression, FailureReason reason) {
            return new Failed(expression, switch (reason) {
                case WOULDNT_SETTLE -> "The Dice wouldn't settle. Nothing was rolled. Press Enter to try again.";
                case TOO_MANY_REROLLS -> "Too many rerolls. Nothing was rolled.";
            });
        }

        /**
         * A Roll that failed because pre-simulation itself broke. #53's runtime-failure panel takes these over.
         *
         * @param expression what was being rolled
         * @param cause what went wrong, in a few words
         * @return the status
         */
        static Failed broken(RollExpression expression, String cause) {
            return new Failed(expression, "The Roll failed: " + cause);
        }

        @Override
        public String text() {
            return message;
        }
    }

    /**
     * A Lost Roll: a runtime failure ended the session (the controller was {@linkplain RollController#abandon
     * abandoned}) while this Roll pre-simulated or played, so it stopped where it was and its Roll Result was never
     * revealed. It never joins the Roll History (ADR-0007). It is terminal: nothing rolls after it.
     *
     * @param expression what was being rolled
     */
    record Lost(RollExpression expression) implements RollStatus {

        /** Checks the expression is present. */
        public Lost {
            Objects.requireNonNull(expression, "expression");
        }

        /** Nothing: the failure panel says what happened, and nothing is still coming. */
        @Override
        public String text() {
            return "";
        }
    }
}
