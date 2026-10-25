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

import io.github.evildarkarchon.andysdice.render.mesh.DieMesh;

/**
 * A {@link DieMesh} uploaded to the GPU: a vertex array with interleaved positions (attribute 0) and normals
 * (attribute 1), and an element buffer of its triangles.
 *
 * <p>Render thread only.
 */
final class GlMesh {

    private final int vertexArray;
    private final int vertices;
    private final int elements;
    private final int indexCount;

    /**
     * Uploads a mesh.
     *
     * @param mesh the mesh to upload
     */
    GlMesh(DieMesh mesh) {
        RenderThread.checkRenderThread();
        int[] indices = mesh.indices();
        indexCount = indices.length;
        vertexArray = glGenVertexArrays();
        glBindVertexArray(vertexArray);
        vertices = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vertices);
        glBufferData(GL_ARRAY_BUFFER, mesh.interleavedPositionsAndNormals(), GL_STATIC_DRAW);
        elements = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elements);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        int stride = 6 * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        // Unbind the vertex array first: it records the element buffer binding, which must stay with it.
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
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
