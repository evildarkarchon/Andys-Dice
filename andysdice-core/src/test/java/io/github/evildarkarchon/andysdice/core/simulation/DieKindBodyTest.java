package io.github.evildarkarchon.andysdice.core.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.util.List;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A Die Kind's solid as the simulator sees it (ADR-0003: one solid for physics and rendering): the same
 * vertices in centimetres, the 0.75 mm edge radius, and the 4° cocked test.
 */
class DieKindBodyTest {

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void itsVerticesAreTheSolidsInCentimetres(DieKind kind) {
        List<Vector3dc> mm = kind.solid().vertices();
        List<Vector3> cm = DieKindBody.of(kind).vertices();

        assertThat(cm).hasSameSizeAs(mm);
        for (int i = 0; i < mm.size(); i++) {
            assertThat(cm.get(i).x()).isCloseTo((float) mm.get(i).x() / 10, within(1e-6f));
            assertThat(cm.get(i).y()).isCloseTo((float) mm.get(i).y() / 10, within(1e-6f));
            assertThat(cm.get(i).z()).isCloseTo((float) mm.get(i).z() / 10, within(1e-6f));
        }
    }

    @Test
    void aD20sVerticesLieOnItsCircumscribedSphere() {
        // An independent check of the unit: a d20 of inradius 10 mm has circumradius 10 mm × 1.2584 = 1.258 cm.
        for (Vector3 v : DieKindBody.of(DieKind.D20).vertices()) {
            assertThat(v.length()).isCloseTo(1.2584f, within(2e-3f));
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void itsEdgesAreRoundedToThreeQuartersOfAMillimetre(DieKind kind) {
        assertThat(DieKindBody.of(kind).edgeRadius()).isEqualTo(0.075f);
    }

    @Test
    void eachKindHasOneSharedBodyNamingItsKind() {
        assertThat(DieKindBody.of(DieKind.D12)).isSameAs(DieKindBody.of(DieKind.D12));
        assertThat(DieKindBody.of(DieKind.PERCENTILE_D10).kind()).isEqualTo(DieKind.PERCENTILE_D10);
    }

    @Test
    void aD6LyingOnAFaceIsFlatAndItsReadingNormalIsStraightUp() {
        // The model frame is z-up and the world y-up: a quarter turn about x stands +z (the 6) up.
        Orientation sixUp = Orientation.about(new Vector3(1, 0, 0), (float) (-Math.PI / 2));
        DieKindBody d6 = DieKindBody.of(DieKind.D6);

        assertThat(d6.isCocked(sixUp)).isFalse();
        Vector3 n = d6.readingNormal(sixUp);
        assertThat(n.y()).isCloseTo(1, within(1e-5f));
    }

    @Test
    void aD6TippedFiveDegreesIsCockedAndItsReadingNormalLeansTheSameWay() {
        Orientation tipped = Orientation.about(new Vector3(0, 0, 1), (float) Math.toRadians(5))
                .times(Orientation.about(new Vector3(1, 0, 0), (float) (-Math.PI / 2)));
        DieKindBody d6 = DieKindBody.of(DieKind.D6);

        assertThat(d6.isCocked(tipped)).isTrue();
        Vector3 n = d6.readingNormal(tipped);
        // Turning +y by 5° about +z leans it towards -x.
        assertThat(n.x()).isCloseTo((float) -Math.sin(Math.toRadians(5)), within(1e-5f));
        assertThat(n.y()).isCloseTo((float) Math.cos(Math.toRadians(5)), within(1e-5f));
    }

    @Test
    void aD6TippedThreeDegreesIsStillFlat() {
        Orientation tipped = Orientation.about(new Vector3(0, 0, 1), (float) Math.toRadians(3))
                .times(Orientation.about(new Vector3(1, 0, 0), (float) (-Math.PI / 2)));

        assertThat(DieKindBody.of(DieKind.D6).isCocked(tipped)).isFalse();
    }

    @Test
    void aD4IsReadByTheFaceItRestsOn() {
        // Face 0's outward normal is -corner 1 = -(1,1,1)/√3. Turning it to straight down leaves the d4 flat on
        // that face, with corner 1 up; its reading normal is then straight up.
        Vector3 down = new Vector3(0, -1, 0);
        Vector3 face = new Vector3(-1, -1, -1);
        Orientation onFace0 = rotationBetween(face, down);
        DieKindBody d4 = DieKindBody.of(DieKind.D4);

        assertThat(d4.isCocked(onFace0)).isFalse();
        assertThat(d4.readingNormal(onFace0).y()).isCloseTo(1, within(1e-5f));
    }

    /** The shortest rotation taking direction {@code from} to direction {@code to}. */
    private static Orientation rotationBetween(Vector3 from, Vector3 to) {
        float fl = from.length();
        float tl = to.length();
        Vector3 a = new Vector3(from.x() / fl, from.y() / fl, from.z() / fl);
        Vector3 b = new Vector3(to.x() / tl, to.y() / tl, to.z() / tl);
        Vector3 axis = new Vector3(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(),
                a.x() * b.y() - a.y() * b.x());
        float angle = (float) Math.acos(Math.clamp(a.x() * b.x() + a.y() * b.y() + a.z() * b.z(), -1, 1));
        return Orientation.about(axis, angle);
    }
}
