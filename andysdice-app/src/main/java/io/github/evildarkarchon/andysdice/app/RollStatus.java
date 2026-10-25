package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.expression.RollResult;
import java.util.Objects;

/**
 * Where the current Roll stands, as the result strip shows it (spec §3.2): nothing rolled yet, rolling, at rest
 * with its Roll Result, refused before anything moved, or failed.
 */
sealed interface RollStatus {

    /** The text the result strip shows for this status. */
    String text();

    /** Nothing has been rolled yet: the first-run Tray is empty. */
    record Ready() implements RollStatus {
        @Override
        public String text() {
            return "Type 1d6 and press Enter.";
        }
    }

    /**
     * A Roll is being pre-simulated or played back; its Dice are still moving.
     *
     * @param expression what is being rolled
     */
    record Rolling(RollExpression expression) implements RollStatus {

        /** Checks the expression is present. */
        public Rolling {
            Objects.requireNonNull(expression, "expression");
        }

        @Override
        public String text() {
            return "Rolling…";
        }
    }

    /**
     * Every Die is at rest and the Roll Result is shown.
     *
     * @param expression what was rolled
     * @param result its Roll Result
     */
    record Rested(RollExpression expression, RollResult result) implements RollStatus {

        /** Checks both parts are present. */
        public Rested {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(result, "result");
        }

        @Override
        public String text() {
            return expression.canonical() + " = " + result.total();
        }
    }

    /**
     * The input wasn't rolled at all: nothing moved and the Tray keeps its Dice.
     *
     * @param input what was typed
     * @param message why it wasn't rolled
     */
    record Refused(String input, String message) implements RollStatus {

        /** Checks both parts are present. */
        public Refused {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String text() {
            return message;
        }
    }

    /**
     * A Failed Roll: its Throw never came to rest flat, or the physics worker failed. Nothing moved, no result
     * is shown and the Tray keeps its previous Dice (spec §3.1).
     *
     * @param expression what was being rolled
     * @param reason why it failed
     */
    record Failed(RollExpression expression, String reason) implements RollStatus {

        /** Checks both parts are present. */
        public Failed {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String text() {
            return "The Roll failed: " + reason;
        }
    }
}
