package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieStart;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The escape volume is the visible walls (ADR-0010), in every built-in Tray: a Die that hits a wall below its top
 * comes back, and one that flies over the top leaves, with no invisible wall above to bounce off.
 */
class TrayEscapeTest {

    private static final DieBody D6 = DieKindBody.of(DieKind.D6);
    private static final float D6_INRADIUS = 0.8f;

    static Stream<Arguments> trays() {
        return BuiltInTrays.all().stream().map(tray -> Arguments.of(Named.of(tray.name(), tray)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trays")
    void aDieThrownAtTheFarWallBelowItsTopStaysInTheTray(Tray tray) {
        TrayGeometry geometry = tray.geometry();
        // Fast, flat at the far wall (+z, flat in every built-in outline), its top edge a centimetre under the wall's.
        Attempt attempt = throwAtFarWall(tray, geometry.wallHeight() - D6_INRADIUS - 1);

        assertThat(attempt.ending()).isNotEqualTo(Ending.ESCAPED);
        Pose last = attempt.recording().pose(0, attempt.recording().samples() - 1);
        assertThat(geometry.depthInside(last.position().x(), last.position().z())).isPositive();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trays")
    void aDieThatFliesOverTheWallTopHasEscaped(Tray tray) {
        // The same throw with the Die's underside a little over the wall top all the way across the wall.
        Attempt attempt = throwAtFarWall(tray, tray.geometry().wallHeight() + D6_INRADIUS + 1.2f);

        assertThat(attempt.ending()).isEqualTo(Ending.ESCAPED);
    }

    /** Throws a flat d6 from 3 cm short of the far wall straight at it at 1.5 m/s, its centre at a height (cm). */
    private static Attempt throwAtFarWall(Tray tray, float height) {
        TrayGeometry geometry = tray.geometry();
        float farWall = geometry.outline().stream().map(p -> p.z()).max(Float::compare).orElseThrow();
        DieStart start = new DieStart(new Pose(new Vector3(0, height, farWall - 3), Scenarios.flat(DieKind.D6, 0)),
                new Vector3(0, 0, 150), Vector3.ZERO);
        return Scenarios.simulate(new AttemptRequest(List.of(D6), new Release.Given(List.of(start)), List.of(),
                geometry, tray.materials()));
    }
}
