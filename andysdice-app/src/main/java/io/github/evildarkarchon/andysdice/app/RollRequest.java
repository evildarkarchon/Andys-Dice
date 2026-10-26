package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import java.util.Objects;
import java.util.Optional;

/**
 * A Roll asked for, from the box, a Saved Roll or a Roll History entry: its Roll Expression and the Saved Roll it came
 * from, if any (#56). The roll controller carries one from the request to the reveal, whether it rolls at once or
 * first waits as the Queued Roll ({@link RollState#queued}), so the Rested status records the Saved Roll's name.
 *
 * @param expression the Roll Expression asked for, already parsed
 * @param savedRollName the Saved Roll's name, or empty for a Roll typed or taken from the History
 */
record RollRequest(RollExpression expression, Optional<String> savedRollName) {

    /** Checks the parts are present. */
    RollRequest {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(savedRollName, "savedRollName");
    }
}
