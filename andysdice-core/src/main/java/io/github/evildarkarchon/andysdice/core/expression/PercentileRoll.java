package io.github.evildarkarchon.andysdice.core.expression;

/**
 * How a Percentile Roll's two Dice read (GLOSSARY.md): the percentile d10 shows the tens (00, 10, ... 90), the
 * d10 shows the units (0-9), and the value is their sum, except that 00 + 0 reads as 100. So every value from 1
 * to 100 comes from exactly one pair of faces.
 */
public final class PercentileRoll {

    /** The highest value of a Percentile Roll; the lowest is 1. */
    public static final int MAX_VALUE = 100;

    private PercentileRoll() {
    }

    /**
     * Returns the value the two faces read as, from 1 to 100.
     *
     * @param tensFace  the percentile d10's face: 0 (shown {@code 00}), 10, 20 ... 90
     * @param unitsFace the d10's face, 0 to 9
     * @throws IllegalArgumentException if either face is not one its Die has
     */
    public static int value(int tensFace, int unitsFace) {
        if (tensFace < 0 || tensFace > 90 || tensFace % 10 != 0) {
            throw new IllegalArgumentException("a percentile d10 shows 00 to 90 in tens: " + tensFace);
        }
        if (unitsFace < 0 || unitsFace > 9) {
            throw new IllegalArgumentException("a d10 in a Percentile Roll shows 0 to 9: " + unitsFace);
        }
        int sum = tensFace + unitsFace;
        return sum == 0 ? MAX_VALUE : sum;
    }

    /**
     * Returns the percentile d10's face for {@code value}: 0 (shown {@code 00}) to 90 in tens.
     *
     * @throws IllegalArgumentException if {@code value} is outside 1 to 100
     */
    public static int tensFace(int value) {
        checkValue(value);
        return value == MAX_VALUE ? 0 : value / 10 * 10;
    }

    /**
     * Returns the d10's face for {@code value}, 0 to 9.
     *
     * @throws IllegalArgumentException if {@code value} is outside 1 to 100
     */
    public static int unitsFace(int value) {
        checkValue(value);
        return value % 10;
    }

    private static void checkValue(int value) {
        if (value < 1 || value > MAX_VALUE) {
            throw new IllegalArgumentException("a Percentile Roll is 1 to 100: " + value);
        }
    }
}
