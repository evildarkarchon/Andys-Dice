package io.github.evildarkarchon.andysdice.core.facevalue;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.util.Objects;

/**
 * How Face Values map onto the faces whose Numerals show them, one-to-one for every Die Kind (ADR-0005).
 *
 * <p>A d4, d6, d8, d12 or d20 rolls 1 to N and shows the value itself. A d10 rolls 1 to 10 and shows its 10 as
 * "0". A Percentile Roll is one value from 1 to 100 split across two Dice: the percentile d10 shows the tens
 * ("00" to "90") and the d10 the units ("0" to "9"), and 00 + 0 reads as 100 (GLOSSARY). The percentile d10
 * therefore has no Face Value of its own.
 *
 * <p>Faces are indices into {@link DieKind#solid()}'s faces, which carry the Numerals {@link DieKind#numeral}
 * gives; a Numbering Rotation then puts the chosen face's Numeral on the face that landed.
 */
public final class FaceValues {

    /** The highest value of a Percentile Roll; the lowest is 1. */
    public static final int PERCENTILE_HIGHEST = 100;

    private FaceValues() {
    }

    /**
     * The highest Face Value a Die rolled on its own can show; the lowest is 1.
     *
     * @param kind any Die Kind but the percentile d10
     * @return 4, 6, 8, 10, 12 or 20: the Die's face count
     * @throws IllegalArgumentException if {@code kind} is the percentile d10
     */
    public static int highest(DieKind kind) {
        requireOnItsOwn(kind);
        return kind.faceCount();
    }

    /**
     * The face whose Numeral shows a Face Value.
     *
     * @param kind  any Die Kind but the percentile d10
     * @param value the Face Value, from 1 to {@link #highest(DieKind)}
     * @return the face index; on a d4, the face named by the corner that shows the value
     * @throws IllegalArgumentException if {@code kind} is the percentile d10 or {@code value} is out of range
     */
    public static int face(DieKind kind, int value) {
        if (value < 1 || value > highest(kind)) {
            throw new IllegalArgumentException("a " + kind + " shows 1 to " + highest(kind) + ": " + value);
        }
        // The d10's faces are numbered 0..9 with "0" standing for 10; every other Die's face i shows i + 1.
        return kind == DieKind.D10 ? value % 10 : value - 1;
    }

    /**
     * The Face Value a face's Numeral shows: the inverse of {@link #face(DieKind, int)}.
     *
     * @param kind any Die Kind but the percentile d10
     * @param face a face index
     * @return the Face Value, from 1 to {@link #highest(DieKind)}
     * @throws IllegalArgumentException  if {@code kind} is the percentile d10
     * @throws IndexOutOfBoundsException if {@code face} is out of range
     */
    public static int faceValue(DieKind kind, int face) {
        Objects.checkIndex(face, highest(kind));
        if (kind == DieKind.D10) {
            return face == 0 ? 10 : face;
        }
        return face + 1;
    }

    /**
     * The percentile d10's face for a Percentile Roll: the tens.
     *
     * @param value the Percentile Roll, from 1 to 100
     * @return the face index of {@link DieKind#PERCENTILE_D10} whose Numeral is the tens, {@code 0} ("00") for 100
     * @throws IllegalArgumentException if {@code value} is outside 1 to 100
     */
    public static int percentileTensFace(int value) {
        requirePercentile(value);
        // 100 wraps to 0: the percentile d10 shows "00" and the d10 "0".
        return value % PERCENTILE_HIGHEST / 10;
    }

    /**
     * The d10's face for a Percentile Roll: the units.
     *
     * @param value the Percentile Roll, from 1 to 100
     * @return the face index of {@link DieKind#D10} whose Numeral is the units digit
     * @throws IllegalArgumentException if {@code value} is outside 1 to 100
     */
    public static int percentileUnitsFace(int value) {
        requirePercentile(value);
        return value % 10;
    }

    /**
     * The Percentile Roll two faces read as.
     *
     * @param tensFace  a face index of the percentile d10
     * @param unitsFace a face index of the d10
     * @return the value from 1 to 100, with 00 + 0 read as 100
     * @throws IndexOutOfBoundsException if either face is out of range
     */
    public static int percentileValue(int tensFace, int unitsFace) {
        Objects.checkIndex(tensFace, DieKind.PERCENTILE_D10.faceCount());
        Objects.checkIndex(unitsFace, DieKind.D10.faceCount());
        int sum = tensFace * 10 + unitsFace;
        return sum == 0 ? PERCENTILE_HIGHEST : sum;
    }

    /** Rejects the percentile d10, which is only ever read together with a d10. */
    private static void requireOnItsOwn(DieKind kind) {
        if (Objects.requireNonNull(kind, "kind") == DieKind.PERCENTILE_D10) {
            throw new IllegalArgumentException("the percentile d10 is only rolled as half of a Percentile Roll");
        }
    }

    /** Rejects a Percentile Roll value outside 1 to 100. */
    private static void requirePercentile(int value) {
        if (value < 1 || value > PERCENTILE_HIGHEST) {
            throw new IllegalArgumentException("a Percentile Roll is 1 to 100: " + value);
        }
    }
}
