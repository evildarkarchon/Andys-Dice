package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11C.glDrawElements;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.render.mesh.DieMesh;
import io.github.evildarkarchon.andysdice.render.numerals.EngravedVertices;

/**
 * A Die Kind's rounded {@link DieMesh} uploaded to the GPU with its engraving attributes
 * ({@link EngravedVertices}): a vertex array with interleaved position (attribute 0), normal (1), atlas UV (2)
 * and tangent (3), and an element buffer of its triangles.
 *
 * <p>Render thread only.
 */
final class GlMesh {

    private final int vertexArray;
    private final int vertices;
    private final int elements;
    private final int indexCount;

    /**
     * Uploads a Die Kind's mesh. The percentile d10 gets buffers of its own: it shares the d10's triangles but
     * not its atlas UVs.
     *
     * @param kind the Die Kind
     */
    GlMesh(DieKind kind) {
        RenderThread.checkRenderThread();
        int[] indices = DieMesh.of(kind).indices();
        indexCount = indices.length;
        vertexArray = glGenVertexArrays();
        glBindVertexArray(vertexArray);
        vertices = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vertices);
        glBufferData(GL_ARRAY_BUFFER, EngravedVertices.of(kind), GL_STATIC_DRAW);
        elements = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elements);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        int stride = EngravedVertices.FLOATS_PER_VERTEX * Float.BYTES;
        attribute(0, 3, stride, 0);
        attribute(1, 3, stride, 3);
        attribute(2, 2, stride, 6);
        attribute(3, 3, stride, 8);
        // Unbind the vertex array first: it records the element buffer binding, which must stay with it.
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Points one float attribute at its slice of each interleaved vertex. */
    private static void attribute(int location, int size, int stride, int offsetFloats) {
        glVertexAttribPointer(location, size, GL_FLOAT, false, stride, (long) offsetFloats * Float.BYTES);
        glEnableVertexAttribArray(location);
    }

    /** @return how many triangles {@link #draw()} draws */
    int triangleCount() {
        return indexCount / 3;
    }

    /** Draws every triangle with whatever program is in use. */
    void draw() {
        glBindVertexArray(vertexArray);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    /** Deletes the GPU objects. */
    void delete() {
        RenderThread.checkRenderThread();
        glDeleteBuffers(elements);
        glDeleteBuffers(vertices);
        glDeleteVertexArrays(vertexArray);
    }
}
