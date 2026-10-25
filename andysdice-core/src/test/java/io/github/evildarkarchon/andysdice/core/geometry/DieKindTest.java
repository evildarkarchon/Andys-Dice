package io.github.evildarkarchon.andysdice.core.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The Die Kinds' sizes and edge radius, against the spec's Appendix A and §4 table.
 */
class DieKindTest {

    /** Appendix A: the inradius (half the face-to-face distance) of each Die Kind, in mm. */
    @ParameterizedTest
    @CsvSource({
        "D4, 4.5",
        "D6, 8",
        "D8, 7.75",
        "D10, 8",
        "PERCENTILE_D10, 8",
        "D12, 9.5",
        "D20, 10",
    })
    void inradiusMatchesAppendixA(DieKind kind, double inradiusMm) {
        assertThat(kind.inradius()).isEqualTo(inradiusMm);
    }

    /** §4: edges are rounded at 0.75 mm on every Die Kind (ADR-0008). */
    @ParameterizedTest
    @CsvSource({"D4", "D6", "D8", "D10", "PERCENTILE_D10", "D12", "D20"})
    void edgeRadiusIsThreeQuartersOfAMillimetre(DieKind kind) {
        assertThat(kind.edgeRadius()).isEqualTo(0.75);
    }
}
