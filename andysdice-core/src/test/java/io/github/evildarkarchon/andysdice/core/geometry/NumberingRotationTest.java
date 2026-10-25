package io.github.evildarkarchon.andysdice.core.geometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The Numbering Rotation, checked exhaustively (ADR-0005): for every Die Kind, every face that can land (up, or
 * down on a d4) and every target Numeral, the chosen symmetry maps the solid onto itself, puts the target
 * Numeral where the Die is read, and keeps the numbering's opposite sums and neighbours.
 */
class NumberingRotationTest {

    /**
     * What opposite faces sum to on each centrally symmetric Die Kind (research §3; GLOSSARY): known from the
     * real dice, not from the tables. The d4 has no opposite faces.
     */
    private static final Map<DieKind, Integer> OPPOSITE_SUM = Map.of(
            DieKind.D6, 7,
            DieKind.D8, 9,
            DieKind.D10, 9,
            DieKind.PERCENTILE_D10, 90,
            DieKind.D12, 13,
            DieKind.D20, 21);

    /** The chosen symmetry is one of the solid's own, and it carries the solid onto itself. */
    @ParameterizedTest
    @EnumSource(DieKind.class)
    void isOneOfTheSolidsOwnSymmetries(DieKind kind) {
        for (int landed = 0; landed < kind.faceCount(); landed++) {
            for (int target = 0; target < kind.faceCount(); target++) {
                NumberingRotation r = NumberingRotation.of(kind, landed, target);

                assertThat(kind.symmetries()).contains(r.symmetry());
                SymmetryTest.assertMapsSolidOntoItself(kind.solid(), r.symmetry());
            }
        }
    }

    /**
     * After the rotation the landed face carries the target Numeral, and a Die drawn at its landing pose turned by
     * the rotation reads, from its fixed numbering, the target face.
     */
    @ParameterizedTest
    @EnumSource(DieKind.class)
    void putsTheTargetNumeralWhereTheDieIsRead(DieKind kind) {
        for (int landed = 0; landed < kind.faceCount(); landed++) {
            Quaterniond pose = landingPose(kind, landed);
            assertThat(UpFace.read(kind, pose).face()).isEqualTo(landed);
            for (int target = 0; target < kind.faceCount(); target++) {
                NumberingRotation r = NumberingRotation.of(kind, landed, target);

                assertThat(r.numeralOn(landed)).isEqualTo(kind.numeral(target));
                UpFace drawn = UpFace.read(kind, r.applyTo(pose, new Quaterniond()));
                assertThat(drawn.face()).as("landed %d, target %d", landed, target).isEqualTo(target);
                assertThat(drawn.cocked()).isFalse();
            }
        }
    }

    /** Opposite faces still sum to the Die Kind's constant after every rotation; the d4 has none. */
    @ParameterizedTest
    @EnumSource(DieKind.class)
    void keepsOppositeSums(DieKind kind) {
        Solid solid = kind.solid();
        for (int landed = 0; landed < kind.faceCount(); landed++) {
            for (int target = 0; target < kind.faceCount(); target++) {
                NumberingRotation r = NumberingRotation.of(kind, landed, target);
                for (int f = 0; f < kind.faceCount(); f++) {
                    int opposite = opposite(solid, f);
                    if (kind == DieKind.D4) {
                        assertThat(opposite).isNegative();
                        continue;
                    }
                    assertThat(Integer.parseInt(r.numeralOn(f)) + Integer.parseInt(r.numeralOn(opposite)))
                            .as("faces %d and %d, landed %d, target %d", f, opposite, landed, target)
                            .isEqualTo(OPPOSITE_SUM.get(kind));
                }
            }
        }
    }

    /**
     * Every Numeral keeps its neighbours in the same counter-clockwise order (so the numbering is turned, never
     * mirrored): the Numerals around a face after the rotation are those around that Numeral's original face.
     */
    @ParameterizedTest
    @EnumSource(DieKind.class)
    void keepsNeighboursInTheirOrder(DieKind kind) {
        Solid solid = kind.solid();
        for (int landed = 0; landed < kind.faceCount(); landed++) {
            for (int target = 0; target < kind.faceCount(); target++) {
                NumberingRotation r = NumberingRotation.of(kind, landed, target);
                for (int f = 0; f < kind.faceCount(); f++) {
                    List<String> after = solid.neighbours(f).stream().map(r::numeralOn).toList();
                    int original = originalFace(kind, r.numeralOn(f));
                    List<String> before = solid.neighbours(original).stream().map(kind::numeral).toList();

                    assertThat(isCyclicShift(after, before))
                            .as("around face %d: %s vs %s (landed %d, target %d)", f, after, before, landed, target)
                            .isTrue();
                }
            }
        }
    }

    /** With the target already up, the rotation is the identity. */
    @ParameterizedTest
    @EnumSource(DieKind.class)
    void isTheIdentityWhenTheTargetLandedUp(DieKind kind) {
        for (int face = 0; face < kind.faceCount(); face++) {
            assertThat(NumberingRotation.of(kind, face, face).symmetry()).isEqualTo(kind.symmetries().getFirst());
        }
    }

    /** The rotation can be chosen straight from a reading of the landed pose. */
    @Test
    void takesTheLandedFaceFromAnUpFace() {
        UpFace landed = UpFace.read(DieKind.D20, landingPose(DieKind.D20, 7));

        NumberingRotation r = NumberingRotation.of(landed, 19);

        assertThat(r.landedFace()).isEqualTo(7);
        assertThat(r.numeralOn(7)).isEqualTo("20");
    }

    @Test
    void rejectsFacesTheDieDoesNotHave() {
        assertThatThrownBy(() -> NumberingRotation.of(DieKind.D6, 6, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> NumberingRotation.of(DieKind.D6, 0, -1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    /**
     * A pose that lands {@code face} flat where the Die is read: its outward normal straight up in the y-up world,
     * or on the d4 straight down. A quarter turn about the vertical is added so that the pose is not a bare
     * shortest-arc rotation.
     */
    private static Quaterniond landingPose(DieKind kind, int face) {
        Vector3dc n = kind.solid().faces().get(face).normal();
        Vector3d wanted = new Vector3d(0, kind == DieKind.D4 ? -1 : 1, 0);
        return new Quaterniond().rotationTo(n, wanted).premul(new Quaterniond().rotationY(Math.PI / 2));
    }

    /** The face whose outward normal is {@code face}'s negated, or -1 if there is none. */
    private static int opposite(Solid solid, int face) {
        Vector3d minus = solid.faces().get(face).normal().negate(new Vector3d());
        for (Solid.Face other : solid.faces()) {
            if (other.normal().distance(minus) < 1e-3) {
                return other.index();
            }
        }
        return -1;
    }

    /** The face the fixed numbering puts {@code numeral} on. */
    private static int originalFace(DieKind kind, String numeral) {
        for (int f = 0; f < kind.faceCount(); f++) {
            if (kind.numeral(f).equals(numeral)) {
                return f;
            }
        }
        throw new AssertionError("no face shows " + numeral);
    }

    /** Whether {@code a} is {@code b} turned round, keeping its direction. */
    private static boolean isCyclicShift(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<String> turned = new ArrayList<>(b);
        for (int k = 0; k < b.size(); k++) {
            if (turned.equals(a)) {
                return true;
            }
            Collections.rotate(turned, 1);
        }
        return false;
    }
}
