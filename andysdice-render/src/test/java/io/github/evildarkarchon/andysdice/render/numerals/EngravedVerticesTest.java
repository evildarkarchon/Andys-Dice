package io.github.evildarkarchon.andysdice.render.numerals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.render.mesh.DieMesh;
import java.util.HashMap;
import java.util.Map;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The vertex data a Die is drawn with: its rounded mesh plus, per vertex, the atlas UV and the tangent the
 * engraving is traced along. Checked without GL.
 */
class EngravedVerticesTest {

    private static final int F = EngravedVertices.FLOATS_PER_VERTEX;

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void carriesTheMeshPositionsAndNormals(DieKind kind) {
        DieMesh mesh = DieMesh.of(kind);
        float[] data = EngravedVertices.of(kind);

        assertThat(data).hasSize(F * mesh.vertexCount());
        Vector3f p = new Vector3f();
        Vector3f n = new Vector3f();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            mesh.position(v, p);
            mesh.normal(v, n);
            assertThat(new float[] {data[F * v], data[F * v + 1], data[F * v + 2]}).containsExactly(p.x, p.y, p.z);
            assertThat(new float[] {data[F * v + 3], data[F * v + 4], data[F * v + 5]}).containsExactly(n.x, n.y, n.z);
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void flatFacesMapIntoTheirOwnCellAndFilletsIntoTheBlankOne(DieKind kind) {
        DieMesh mesh = DieMesh.of(kind);
        NumeralLayout layout = NumeralLayout.of(kind);
        float[] data = EngravedVertices.of(kind);
        int grid = layout.grid();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            double u = data[F * v + 6];
            double w = data[F * v + 7];
            int face = mesh.face(v);
            int expectedCell = face == DieMesh.NO_FACE ? layout.blankCell() : layout.cell(face);
            // The cell's square in UV, shrunk a little: no flat face reaches its cell's border.
            double u0 = (double) (expectedCell % grid) / grid;
            double v0 = (double) (expectedCell / grid) / grid;
            assertThat(u).as("%s vertex %d (face %d) u", kind, v, face).isStrictlyBetween(u0, u0 + 1.0 / grid);
            assertThat(w).as("%s vertex %d (face %d) v", kind, v, face).isStrictlyBetween(v0, v0 + 1.0 / grid);
            if (face != DieMesh.NO_FACE) {
                Vector3f p = mesh.position(v, new Vector3f());
                Vector2d expected = layout.uv(face, new Vector3d(p), new Vector2d());
                assertThat(u).isCloseTo(expected.x, within(1e-6));
                assertThat(w).isCloseTo(expected.y, within(1e-6));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void onFlatFacesTheTangentsAreTheUvDirectionsInMillimetres(DieKind kind) {
        DieMesh mesh = DieMesh.of(kind);
        double mmPerUv = NumeralLayout.of(kind).mmPerUv();
        float[] data = EngravedVertices.of(kind);
        Map<Integer, Integer> firstOfFace = new HashMap<>();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            Vector3d n = vector(data, v, 3);
            Vector3d t = vector(data, v, 8);
            assertThat(t.length()).isCloseTo(1, within(1e-5));
            assertThat(t.dot(n)).isCloseTo(0, within(1e-5));
            // The shader's bitangent: v grows image-down, which makes it tangent × normal.
            Vector3d b = t.cross(n, new Vector3d());
            int face = mesh.face(v);
            if (face == DieMesh.NO_FACE) {
                continue;
            }
            Integer first = firstOfFace.putIfAbsent(face, v);
            if (first == null) {
                continue;
            }
            // dP/du and dP/dv: a step across the face moves the UV by its length along each, over mm per UV.
            Vector3d step = vector(data, v, 0).sub(vector(data, first, 0));
            double du = data[F * v + 6] - data[F * first + 6];
            double dv = data[F * v + 7] - data[F * first + 7];
            assertThat(du * mmPerUv).isCloseTo(step.dot(t), within(1e-3));
            assertThat(dv * mmPerUv).isCloseTo(step.dot(b), within(1e-3));
        }
    }

    private static Vector3d vector(float[] data, int vertex, int offset) {
        int i = F * vertex + offset;
        return new Vector3d(data[i], data[i + 1], data[i + 2]);
    }
}
