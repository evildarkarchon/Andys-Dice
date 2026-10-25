package io.github.evildarkarchon.andysdice.app;

import java.util.List;
import java.util.Objects;

/**
 * The toolbar's die strip (spec §3.2 "Layout"): one button per Die Kind a Roll Expression can name, each
 * appending {@code +1dX} to the box, or {@code 1dX} when the box is empty. {@link RollUi} builds the buttons; this
 * is the text rule they follow, which never checks the box (errors are checked on Enter only).
 *
 * <p>Pure; safe on any thread.
 */
final class DieStrip {

    /** The strip's dice, in order, as they are written in a Roll Expression after the count. */
    static final List<String> DICE = List.of("d4", "d6", "d8", "d10", "d12", "d20", "d%");

    private DieStrip() {}

    /**
     * Returns the box's text with one more Die appended.
     *
     * @param expression what the box holds, valid or not
     * @param die one of {@link #DICE}
     * @return {@code 1dX} if the box is empty or only spaces; otherwise its text without trailing spaces, then
     *     {@code +1dX}
     */
    static String append(String expression, String die) {
        Objects.requireNonNull(expression, "expression");
        if (!DICE.contains(die)) {
            throw new IllegalArgumentException("not a die on the strip: " + die);
        }
        String kept = expression.stripTrailing();
        return kept.isEmpty() ? "1" + die : kept + "+1" + die;
    }
}
