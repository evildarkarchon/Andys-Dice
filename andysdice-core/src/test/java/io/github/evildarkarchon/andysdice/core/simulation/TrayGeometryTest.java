package io.github.evildarkarchon.andysdice.core.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A Tray as the simulator sees it: a convex outline of walls on a floor at y = 0. */
class TrayGeometryTest {

    /** The Footprint (ADR-0010): 300 × 200 mm, with 30 mm walls. */
    private static final TrayGeometry FOOTPRINT = TrayGeometry.rectangle(15, 10, 3);

    @Test
    void measuresHowFarAPointLiesInsideTheNearestWall() {
        assertThat(FOOTPRINT.depthInside(0, 0)).isCloseTo(10, within(1e-5f));
        assertThat(FOOTPRINT.depthInside(14, 0)).isCloseTo(1, within(1e-5f));
        assertThat(FOOTPRINT.depthInside(0, -9.5f)).isCloseTo(0.5f, within(1e-5f));
    }

    @Test
    void givesANegativeDepthOutsideTheOutline() {
        assertThat(FOOTPRINT.depthInside(16, 0)).isCloseTo(-1, within(1e-5f));
        assertThat(FOOTPRINT.depthInside(0, 12)).isCloseTo(-2, within(1e-5f));
    }

    @Test
    void acceptsEitherWindingOfTheOutline() {
        List<OutlinePoint> clockwise = FOOTPRINT.outline().reversed();
        TrayGeometry reversed = new TrayGeometry(clockwise, 3);
        assertThat(reversed.depthInside(14, 0)).isCloseTo(1, within(1e-5f));
        assertThat(reversed.depthInside(16, 0)).isCloseTo(-1, within(1e-5f));
    }

    @Test
    void rejectsAnOutlineThatIsNotConvex() {
        List<OutlinePoint> notch = List.of(new OutlinePoint(-10, -10), new OutlinePoint(10, -10),
                new OutlinePoint(0, 0), new OutlinePoint(10, 10), new OutlinePoint(-10, 10));
        assertThatThrownBy(() -> new TrayGeometry(notch, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooFewPointsOrNoWalls() {
        List<OutlinePoint> line = List.of(new OutlinePoint(0, 0), new OutlinePoint(1, 0));
        assertThatThrownBy(() -> new TrayGeometry(line, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrayGeometry.rectangle(15, 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
