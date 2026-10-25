package io.github.evildarkarchon.andysdice.core.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Percentile Roll's value from its two Dice (GLOSSARY.md): the percentile d10 gives the tens (00-90), the d10
 * the units (0-9), and 00 + 0 reads as 100.
 */
class PercentileRollTest {

    @ParameterizedTest(name = "{0} + {1} reads as {2}")
    @CsvSource({
        "0, 0, 100",
        "0, 1, 1",
        "0, 7, 7",
        "10, 0, 10",
        "30, 7, 37",
        "90, 0, 90",
        "90, 9, 99",
    })
    void readsTensPlusUnitsWithDoubleZeroAs100(int tens, int units, int value) {
        assertThat(PercentileRoll.value(tens, units)).isEqualTo(value);
    }

    @ParameterizedTest(name = "{0} shows {1} + {2}")
    @CsvSource({
        "100, 0, 0",
        "1, 0, 1",
        "10, 10, 0",
        "37, 30, 7",
        "90, 90, 0",
        "99, 90, 9",
    })
    void eachValueIsShownByOnePairOfFaces(int value, int tens, int units) {
        assertThat(PercentileRoll.tensFace(value)).isEqualTo(tens);
        assertThat(PercentileRoll.unitsFace(value)).isEqualTo(units);
    }

    @Test
    void everyValueFrom1To100RoundTripsThroughItsFaces() {
        for (int value = 1; value <= 100; value++) {
            assertThat(PercentileRoll.value(PercentileRoll.tensFace(value), PercentileRoll.unitsFace(value)))
                    .isEqualTo(value);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-10, 5, 100, 95})
    void rejectsATensFaceThePercentileD10DoesNotHave(int tens) {
        assertThatIllegalArgumentException().isThrownBy(() -> PercentileRoll.value(tens, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10})
    void rejectsAUnitsFaceTheD10DoesNotHave(int units) {
        assertThatIllegalArgumentException().isThrownBy(() -> PercentileRoll.value(0, units));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsAValueOutside1To100(int value) {
        assertThatIllegalArgumentException().isThrownBy(() -> PercentileRoll.tensFace(value));
        assertThatIllegalArgumentException().isThrownBy(() -> PercentileRoll.unitsFace(value));
    }
}
