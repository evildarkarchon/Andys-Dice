package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glDrawElements;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform3f;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

/**
 * The bridge's test scene (ticket #33, spec slice S3): one flat-shaded cube under a directional light on a
 * plain background. It proves the context, the shaders and the frame bridge end to end before any real
 * renderer exists.
 *
 * <p>{@link #setYaw} may be called from any thread; everything else runs on the render thread, as a
 * {@link FrameRenderer}.
 */
public final class TestScene implements FrameRenderer {

    /** The background, as the opaque ARGB JavaFX reads back: a dark felt green. */
    public static final int BACKGROUND_ARGB = 0xff12301c;

    private static final float FOV_Y_DEGREES = 32;
    private static final float CAMERA_DISTANCE = 7;
    private static final float CAMERA_PITCH_DEGREES = 30;

    // Six faces, four corners each, with the face's normal at every corner so shading stays flat.
    private static final float[][] FACE_NORMALS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
    };

    private volatile double yawDegrees = 35;

    private int program;
    private int vertexArray;
    private int vertices;
    private int indices;
    private int modelUniform;
    private int viewProjectionUniform;
    private int lightUniform;
    private int colourUniform;

    /** Creates the scene; its GL objects are made in {@link #init()}. */
    public TestScene() {}

    /**
     * Turns the cube about the vertical axis. Takes effect at the next frame; any thread.
     *
     * @param degrees the yaw in degrees
     */
    public void setYaw(double degrees) {
        yawDegrees = degrees;
    }

    @Override
    public void init() {
        program = Shaders.program("flat.vert", "flat.frag");
        modelUniform = glGetUniformLocation(program, "uModel");
        viewProjectionUniform = glGetUniformLocation(program, "uViewProjection");
        lightUniform = glGetUniformLocation(program, "uLightDirection");
        colourUniform = glGetUniformLocation(program, "uColour");

        vertexArray = glGenVertexArrays();
        glBindVertexArray(vertexArray);
        vertices = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vertices);
        glBufferData(GL_ARRAY_BUFFER, cubeVertices(), GL_STATIC_DRAW);
        indices = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, cubeIndices(), GL_STATIC_DRAW);
        int stride = 6 * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    @Override
    public void render(int width, int height) {
        glClearColor(((BACKGROUND_ARGB >> 16) & 0xff) / 255f, ((BACKGROUND_ARGB >> 8) & 0xff) / 255f,
                (BACKGROUND_ARGB & 0xff) / 255f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);

        float[] projection = perspective(FOV_Y_DEGREES, (float) width / height, 0.5f, 50);
        float[] view = multiply(translation(0, 0, -CAMERA_DISTANCE), rotationX(CAMERA_PITCH_DEGREES));
        glUseProgram(program);
        glUniformMatrix4fv(viewProjectionUniform, false, multiply(projection, view));
        glUniformMatrix4fv(modelUniform, false, rotationY((float) yawDegrees));
        float[] light = normalised(-0.45f, 0.85f, 0.35f);
        glUniform3f(lightUniform, light[0], light[1], light[2]);
        // An ivory die colour, linear RGB.
        glUniform3f(colourUniform, 0.80f, 0.76f, 0.66f);
        glBindVertexArray(vertexArray);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_SHORT, 0);
        glBindVertexArray(0);
        glUseProgram(0);
        glDisable(GL_DEPTH_TEST);
    }

    @Override
    public void dispose() {
        glDeleteBuffers(indices);
        glDeleteBuffers(vertices);
        glDeleteVertexArrays(vertexArray);
        glDeleteProgram(program);
    }

    /** A cube of edge 2 about the origin: per face, four corners of (position, normal). */
    private static float[] cubeVertices() {
        float[] out = new float[6 * 4 * 6];
        int i = 0;
        for (float[] n : FACE_NORMALS) {
            // Two axes spanning the face: any vector not parallel to n, crossed with it, and then again.
            float[] u = Math.abs(n[1]) > 0.5f ? new float[] {1, 0, 0} : new float[] {0, 1, 0};
            float[] a = cross(n, u);
            float[] b = cross(n, a);
            float[][] corners = {
                {n[0] - a[0] - b[0], n[1] - a[1] - b[1], n[2] - a[2] - b[2]},
                {n[0] + a[0] - b[0], n[1] + a[1] - b[1], n[2] + a[2] - b[2]},
                {n[0] + a[0] + b[0], n[1] + a[1] + b[1], n[2] + a[2] + b[2]},
                {n[0] - a[0] + b[0], n[1] - a[1] + b[1], n[2] - a[2] + b[2]},
            };
            for (float[] c : corners) {
                out[i++] = c[0];
                out[i++] = c[1];
                out[i++] = c[2];
                out[i++] = n[0];
                out[i++] = n[1];
                out[i++] = n[2];
            }
        }
        return out;
    }

    private static short[] cubeIndices() {
        short[] out = new short[36];
        for (int face = 0; face < 6; face++) {
            int base = face * 4;
            int[] quad = {base, base + 1, base + 2, base, base + 2, base + 3};
            for (int k = 0; k < 6; k++) {
                out[face * 6 + k] = (short) quad[k];
            }
        }
        return out;
    }

    private static float[] cross(float[] p, float[] q) {
        return new float[] {p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0]};
    }

    private static float[] normalised(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] {x / length, y / length, z / length};
    }

    // Column-major 4x4 matrices, as glUniformMatrix4fv takes them untransposed.

    private static float[] perspective(float fovYDegrees, float aspect, float near, float far) {
        float f = (float) (1 / Math.tan(Math.toRadians(fovYDegrees) / 2));
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1;
        m[14] = 2 * far * near / (near - far);
        return m;
    }

    private static float[] translation(float x, float y, float z) {
        float[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    private static float[] rotationX(float degrees) {
        float c = (float) Math.cos(Math.toRadians(degrees));
        float s = (float) Math.sin(Math.toRadians(degrees));
        float[] m = identity();
        m[5] = c;
        m[6] = s;
        m[9] = -s;
        m[10] = c;
        return m;
    }

    private static float[] rotationY(float degrees) {
        float c = (float) Math.cos(Math.toRadians(degrees));
        float s = (float) Math.sin(Math.toRadians(degrees));
        float[] m = identity();
        m[0] = c;
        m[2] = -s;
        m[8] = s;
        m[10] = c;
        return m;
    }

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = 1;
        m[5] = 1;
        m[10] = 1;
        m[15] = 1;
        return m;
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                r[col * 4 + row] = sum;
            }
        }
        return r;
    }
}
