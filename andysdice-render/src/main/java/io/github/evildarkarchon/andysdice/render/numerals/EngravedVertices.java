package io.github.evildarkarchon.andysdice.render.numerals;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.render.mesh.DieMesh;
import java.util.Objects;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * The vertex data a Die is drawn with: its rounded {@link DieMesh} with, per vertex, the atlas UV of its
 * {@link NumeralLayout} and the tangent the engraving is traced along. Pure Java, no GL.
 *
 * <p>Per vertex, {@value #FLOATS_PER_VERTEX} floats: position (3, mm), unit normal (3), atlas UV (2) and unit
 * tangent (3). On a flat face the UV is the face's projection into its cell and the tangent is {@code dP/du}, the
 * face frame's right. The bitangent {@code dP/dv} is always {@code tangent × normal} (the face frame's up negated,
 * since {@code v} grows image-down), so the shader derives it rather than carrying it. Fillets and caps map to
 * the centre of the blank cell, with any unit tangent perpendicular to the normal; the profile there is flat, so
 * it never matters.
 *
 * <p>The percentile d10 shares the d10's mesh but not its atlas, so its vertex data is its own.
 */
public final class EngravedVertices {

    /** Floats per vertex: position 3, normal 3, UV 2, tangent 3. */
    public static final int FLOATS_PER_VERTEX = 11;

    private EngravedVertices() {}

    /**
     * Builds a Die Kind's vertex data from its shared mesh and layout.
     *
     * @param kind the Die Kind
     * @return a new array of {@code FLOATS_PER_VERTEX × DieMesh.of(kind).vertexCount()} floats, in the mesh's
     *     vertex order, so {@link DieMesh#indices()} index it
     */
    public static float[] of(DieKind kind) {
        Objects.requireNonNull(kind, "kind");
        DieMesh mesh = DieMesh.of(kind);
        NumeralLayout layout = NumeralLayout.of(kind);
        int count = mesh.vertexCount();
        float[] out = new float[FLOATS_PER_VERTEX * count];
        Vector2d blank = layout.uv(layout.blankCell(), 0, 0, new Vector2d());
        Vector3f p = new Vector3f();
        Vector3f n = new Vector3f();
        Vector2d uv = new Vector2d();
        for (int v = 0; v < count; v++) {
            mesh.position(v, p);
            mesh.normal(v, n);
            int face = mesh.face(v);
            Vector3dc tangent;
            if (face == DieMesh.NO_FACE) {
                uv.set(blank);
                Vector3d nd = new Vector3d(n);
                // Any direction not along the normal will do as a seed: the axis the normal leans on least.
                Vector3d seed = Math.abs(nd.x) <= Math.abs(nd.y) && Math.abs(nd.x) <= Math.abs(nd.z)
                        ? new Vector3d(1, 0, 0)
                        : Math.abs(nd.y) <= Math.abs(nd.z) ? new Vector3d(0, 1, 0) : new Vector3d(0, 0, 1);
                tangent = nd.cross(seed, new Vector3d()).normalize();
            } else {
                layout.uv(face, new Vector3d(p), uv);
                tangent = layout.faces().get(face).right();
            }
            int i = FLOATS_PER_VERTEX * v;
            out[i] = p.x;
            out[i + 1] = p.y;
            out[i + 2] = p.z;
            out[i + 3] = n.x;
            out[i + 4] = n.y;
            out[i + 5] = n.z;
            out[i + 6] = (float) uv.x;
            out[i + 7] = (float) uv.y;
            out[i + 8] = (float) tangent.x();
            out[i + 9] = (float) tangent.y();
            out[i + 10] = (float) tangent.z();
        }
        return out;
    }
}
