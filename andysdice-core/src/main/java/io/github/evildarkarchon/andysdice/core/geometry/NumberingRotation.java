package io.github.evildarkarchon.andysdice.core.geometry;

import java.util.Objects;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;

/**
 * A Die's Numbering Rotation (ADR-0001, GLOSSARY): the symmetry of its solid that turns its numbering so that
 * the Numeral chosen for it is the one on the face it landed on.
 *
 * <p>Physics lands the Die on whatever face it lands on; the Face Value was chosen beforehand. The rotation
 * carries the target face's normal onto the landed face's, in the z-up model frame, so a Die drawn at
 * {@code pose · rotation} (see {@link #applyTo}) shows the target Numeral where the pose would have shown the
 * landed one. The rotation maps the solid onto itself, so the shape drawn, and with it the physics, is unchanged,
 * and the real numeral layout is only turned, never mirrored or shuffled.
 *
 * <p>On a d4 the landed face is the one lying on the table, as {@link UpFace#read} reports it, and the target
 * face is the one named by the corner that should point up; the rotation is found the same way.
 *
 * @param kind       the Die Kind
 * @param landedFace the face the Die landed on: up, or down on a d4
 * @param targetFace the face that carries the target Numeral in the Die Kind's fixed numbering
 * @param symmetry   the chosen symmetry, one of {@code kind.symmetries()}, taking {@code targetFace} to
 *                   {@code landedFace}
 */
public record NumberingRotation(DieKind kind, int landedFace, int targetFace, Symmetry symmetry) {

    /**
     * Validates that the symmetry belongs to the Die Kind and does the job.
     *
     * @throws IndexOutOfBoundsException if either face is out of range
     * @throws IllegalArgumentException  if {@code symmetry} isn't one of the solid's or doesn't take
     *                                   {@code targetFace} to {@code landedFace}
     */
    public NumberingRotation {
        Objects.requireNonNull(kind, "kind");
        Objects.checkIndex(landedFace, kind.faceCount());
        Objects.checkIndex(targetFace, kind.faceCount());
        if (!kind.symmetries().contains(symmetry)) {
            throw new IllegalArgumentException("not a symmetry of the " + kind + " solid: " + symmetry);
        }
        if (symmetry.face(targetFace) != landedFace) {
            throw new IllegalArgumentException("the symmetry takes face " + targetFace + " to "
                    + symmetry.face(targetFace) + ", not to " + landedFace);
        }
    }

    /**
     * Chooses the Numbering Rotation that puts the target Numeral on the landed face. Several symmetries may do
     * it (four on a d6, one on a d10); the one with the smallest turn is chosen, so the choice is deterministic
     * and is the identity when the target already landed.
     *
     * @param kind       the Die Kind
     * @param landedFace the face the Die landed on: up, or down on a d4
     * @param targetFace the face that carries the target Numeral in the fixed numbering
     * @return the rotation
     * @throws IndexOutOfBoundsException if either face is out of range
     * @throws IllegalStateException     if no symmetry takes the target face to the landed one, which the
     *                                   exhaustive tests prove never happens for the seven Die Kinds
     */
    public static NumberingRotation of(DieKind kind, int landedFace, int targetFace) {
        Objects.checkIndex(landedFace, kind.faceCount());
        Objects.checkIndex(targetFace, kind.faceCount());
        // The group is sorted by increasing angle, so the first match is the smallest turn.
        for (Symmetry s : kind.symmetries()) {
            if (s.face(targetFace) == landedFace) {
                return new NumberingRotation(kind, landedFace, targetFace, s);
            }
        }
        throw new IllegalStateException("no symmetry of the " + kind + " takes face " + targetFace
                + " to face " + landedFace);
    }

    /**
     * Chooses the Numbering Rotation for a Die read at rest.
     *
     * @param landed     the reading of the Die's landed pose
     * @param targetFace the face that carries the target Numeral in the fixed numbering
     * @return {@code of(landed.kind(), landed.face(), targetFace)}
     * @throws IndexOutOfBoundsException if {@code targetFace} is out of range
     */
    public static NumberingRotation of(UpFace landed, int targetFace) {
        return of(landed.kind(), landed.face(), targetFace);
    }

    /**
     * The rotation itself, in the z-up model frame.
     *
     * @return the symmetry's unit quaternion
     */
    public Quaterniondc rotation() {
        return symmetry.rotation();
    }

    /**
     * The Numeral a face shows once the numbering is turned: the one the fixed numbering puts on the face the
     * rotation carries onto it.
     *
     * @param face a face index
     * @return the Numeral shown on {@code face}; on a d4, the corner Numeral that points up when it lies on the
     *         table
     * @throws IndexOutOfBoundsException if {@code face} is out of range
     */
    public String numeralOn(int face) {
        return kind.numeral(symmetry.preimage(face));
    }

    /**
     * The orientation to draw the Die at: its physics pose turned by this rotation, {@code pose · rotation}. The
     * rotation acts first, in the model frame, so the solid lands on itself and only the numbering moves.
     *
     * @param pose the Die's orientation from physics, z-up model frame into the y-up world
     * @param dest receives the result
     * @return {@code dest}
     */
    public Quaterniond applyTo(Quaterniondc pose, Quaterniond dest) {
        return pose.mul(symmetry.rotation(), dest);
    }
}
