package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.stephengold.joltjni.ConvexHullShape;
import com.github.stephengold.joltjni.ConvexHullShapeSettings;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.ShapeResult;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Each Die Kind's physics body is a rounded convex hull of its {@code core} solid with the 0.75 mm edge radius
 * (ADR-0003, ADR-0008): Jolt keeps every vertex and face of the solid and rounds by the full radius, even at the
 * d4's sharp tips.
 */
class DieHullTest {

    /** What Jolt made of a Die Kind's hull settings. */
    private record Hull(float convexRadius, int points, int faces, float innerRadius) {
    }

    private static Hull build(DieKind kind) {
        return TestWorker.get().submit(simulator -> {
            try (ConvexHullShapeSettings settings = JoltSimulator.hullSettings(DieKindBody.of(kind))) {
                ShapeResult result = settings.create();
                try (result; ShapeRefC ref = result.get()) {
                    // getError() may be called only on a failed result: Jolt asserts otherwise.
                    if (result.hasError()) {
                        throw new AssertionError("Jolt can't build the " + kind + " hull: " + result.getError());
                    }
                    ConvexHullShape hull = (ConvexHullShape) ref.getPtr();
                    return new Hull(hull.getConvexRadius(), hull.getNumPoints(), hull.getNumFaces(),
                            ref.getInnerRadius());
                }
            }
        }).join();
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void itIsRoundedByThreeQuartersOfAMillimetre(DieKind kind) {
        assertThat(build(kind).convexRadius()).isCloseTo(0.075f, within(1e-6f));
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void itKeepsEveryVertexAndFaceOfTheSolid(DieKind kind) {
        Hull hull = build(kind);

        assertThat(hull.points()).isEqualTo(kind.solid().vertices().size());
        assertThat(hull.faces()).isEqualTo(kind.faceCount());
        // Spec Appendix A's inradius, in cm: the rounding doesn't move the face planes.
        assertThat(hull.innerRadius()).isCloseTo((float) kind.inradius() / 10, within(1e-4f));
    }
}
