package io.github.evildarkarchon.andysdice.core.facevalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * How a Face Value maps onto the Numeral that shows it (ADR-0005: the draw maps one-to-one onto each Die Kind's
 * Numerals), including the split of a Percentile Roll across its two Dice (GLOSSARY).
 */
class FaceValuesTest {

    /** The Die Kinds rolled on their own; the percentile d10 is only ever half of a Percentile Roll. */
    @ParameterizedTest
    @EnumSource(value = DieKind.class, names = "PERCENTILE_D10", mode = EnumSource.Mode.EXCLUDE)
    void mapsEveryFaceValueOneToOneOntoTheNumerals(DieKind kind) {
        Set<Integer> faces = new HashSet<>();
        for (int value = 1; value <= FaceValues.highest(kind); value++) {
            int face = FaceValues.face(kind, value);
            assertThat(faces.add(face)).as("value %d", value).isTrue();
            assertThat(FaceValues.faceValue(kind, face)).isEqualTo(value);
        }
        assertThat(faces).hasSize(kind.faceCount());
    }

    /** The values each Die Kind rolls, and the Numeral each shows: a d10 shows its 10 as "0". */
    @ParameterizedTest
    @CsvSource({
        "D4, 4, 1, 1", "D4, 4, 4, 4",
        "D6, 6, 1, 1", "D6, 6, 6, 6",
        "D8, 8, 1, 1", "D8, 8, 8, 8",
        "D10, 10, 1, 1", "D10, 10, 9, 9", "D10, 10, 10, 0",
        "D12, 12, 1, 1", "D12, 12, 12, 12",
        "D20, 20, 1, 1", "D20, 20, 20, 20",
    })
    void showsEachFaceValueAsItsNumeral(DieKind kind, int highest, int value, String numeral) {
        assertThat(FaceValues.highest(kind)).isEqualTo(highest);
        assertThat(kind.numeral(FaceValues.face(kind, value))).isEqualTo(numeral);
    }

    @Test
    void rejectsValuesTheDieCannotShow() {
        assertThatThrownBy(() -> FaceValues.face(DieKind.D6, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaceValues.face(DieKind.D6, 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaceValues.faceValue(DieKind.D6, 6)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    /** The percentile d10 has no Face Value of its own: it is read only together with a d10. */
    @Test
    void rejectsThePercentileD10OnItsOwn() {
        assertThatThrownBy(() -> FaceValues.highest(DieKind.PERCENTILE_D10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaceValues.face(DieKind.PERCENTILE_D10, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaceValues.faceValue(DieKind.PERCENTILE_D10, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Worked examples of a Percentile Roll's two Numerals; 100 is 00 + 0 (GLOSSARY). */
    @ParameterizedTest
    @CsvSource({
        "1, 00, 1",
        "9, 00, 9",
        "10, 10, 0",
        "55, 50, 5",
        "90, 90, 0",
        "99, 90, 9",
        "100, 00, 0",
    })
    void splitsAPercentileRollIntoTensAndUnits(int value, String tens, String units) {
        int tensFace = FaceValues.percentileTensFace(value);
        int unitsFace = FaceValues.percentileUnitsFace(value);

        assertThat(DieKind.PERCENTILE_D10.numeral(tensFace)).isEqualTo(tens);
        assertThat(DieKind.D10.numeral(unitsFace)).isEqualTo(units);
        assertThat(FaceValues.percentileValue(tensFace, unitsFace)).isEqualTo(value);
    }

    /** Each of the 100 values is one pair of faces, and each pair is one value. */
    @Test
    void mapsPercentileRollsOneToOneOntoPairsOfFaces() {
        Set<List<Integer>> pairs = new HashSet<>();
        for (int value = 1; value <= FaceValues.PERCENTILE_HIGHEST; value++) {
            pairs.add(List.of(FaceValues.percentileTensFace(value), FaceValues.percentileUnitsFace(value)));
        }
        assertThat(pairs).hasSize(100);
        assertThat(FaceValues.PERCENTILE_HIGHEST).isEqualTo(100);
    }

    @Test
    void rejectsPercentileValuesOutsideOneToOneHundred() {
        assertThatThrownBy(() -> FaceValues.percentileTensFace(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaceValues.percentileUnitsFace(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaceValues.percentileValue(10, 0)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
