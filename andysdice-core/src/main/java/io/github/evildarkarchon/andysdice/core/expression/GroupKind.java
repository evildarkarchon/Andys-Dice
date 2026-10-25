package io.github.evildarkarchon.andysdice.core.expression;

/**
 * The Die Kind a Dice Group throws, as the grammar names it: one of the 7-piece set, where {@code d%} (or
 * {@code d100}) is a Percentile Roll.
 *
 * <p>This is the grammar's view, not the solids': a Percentile Roll is one value from 1 to 100 here, but two
 * physical Dice (a percentile d10 and a d10) in the Tray. It is named apart from the solids' Die Kind so the
 * two never clash in an import.
 */
public enum GroupKind {
    /** A d4. */
    D4(4, "d4", 1),
    /** A d6. */
    D6(6, "d6", 1),
    /** A d8. */
    D8(8, "d8", 1),
    /** A d10, read 1 to 10. */
    D10(10, "d10", 1),
    /** A d12. */
    D12(12, "d12", 1),
    /** A d20. */
    D20(20, "d20", 1),
    /** A Percentile Roll ({@code d%} or {@code d100}): a percentile d10 for the tens plus a d10 for the units. */
    PERCENTILE(100, "d%", 2);

    private final int faces;
    private final String label;
    private final int physicalDice;

    GroupKind(int faces, String label, int physicalDice) {
        this.faces = faces;
        this.label = label;
        this.physicalDice = physicalDice;
    }

    /** Returns the highest value one roll of this kind can give; every value from 1 to it is possible. */
    public int faces() {
        return faces;
    }

    /** Returns the canonical way to write the kind, such as {@code d6} or {@code d%}. */
    public String label() {
        return label;
    }

    /** Returns how many physical Dice one roll of this kind puts in the Tray: 2 for a Percentile Roll, else 1. */
    public int physicalDice() {
        return physicalDice;
    }
}
