package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_REPEAT;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glDrawElements;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glGetFloat;
import static org.lwjgl.opengl.GL11C.glPixelStorei;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL11C.glTexParameterf;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
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
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform3f;
import static org.lwjgl.opengl.GL20C.glUniform4fv;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.GL_RG;
import static org.lwjgl.opengl.GL30C.GL_RG8;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenerateMipmap;

import io.github.evildarkarchon.andysdice.core.diceset.SrgbColour;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.render.tray.SurfaceLook;
import io.github.evildarkarchon.andysdice.render.tray.SurfaceTexture;
import io.github.evildarkarchon.andysdice.render.tray.TrayMesh;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3dc;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws the chosen Tray (ADR-0010) in the Dice scene: its floor, walls and the table around it ({@link TrayMesh}),
 * each with its procedural, tiling surface ({@link SurfaceTexture}) tinted by the Tray file's colour, through
 * {@code tray.vert} / {@code tray.frag}. The chosen Light Rig lights it (ticket #45): its walls cast into the key
 * light's shadow map ({@link #drawCasters}), every surface receives that shadow, and each Die darkens the floor
 * beneath it with a contact occlusion.
 *
 * <p>A surface kind's texture is generated and uploaded the first time a Tray needs it, then kept for the life of
 * the renderer, so changing Tray later costs only its meshes. Render thread only.
 */
final class TrayRenderer {

    /** The table under every Tray: the tray prototype's dark table wood, linear RGB. */
    private static final float[] TABLE_COLOUR = {0.11f, 0.065f, 0.04f};

    /** The texture unit the surfaces are bound to. */
    private static final int SURFACE_UNIT = 0;

    /** {@code GL_TEXTURE_MAX_ANISOTROPY} and its limit, as {@link GlAtlas} uses them: floors are seen at a slant. */
    private static final int TEXTURE_MAX_ANISOTROPY = 0x84FE;
    private static final int MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;
    private static final float ANISOTROPY = 8;

    /** How many Dice darken the floor beneath them: {@code tray.frag}'s array, the 20-Dice cap plus slack. */
    static final int MAX_DICE = 24;

    /** A Die's contact-occlusion sphere is a little larger than its inscribed one (the prototype's). */
    private static final double CONTACT_RADIUS_SCALE = 1.05;

    private final Map<SurfaceTexture.Kind, Integer> textures = new EnumMap<>(SurfaceTexture.Kind.class);
    private int program;
    private int viewProjectionUniform;
    private int eyeUniform;
    private LightUniforms lightUniforms;
    private int diceUniform;
    private int diceCountUniform;
    private int tintUniform;
    private int roughnessUniform;
    private int sheenUniform;
    private int bumpUniform;

    private Tray tray;
    private GlPart floor;
    private GlPart walls;
    private GlPart table;

    /**
     * Compiles the shader. GL objects for a Tray are made when it is first drawn.
     *
     * @param shadowUnit the texture unit the scene binds the comparing shadow map to
     * @param rawShadowUnit the unit it binds the raw shadow map to
     */
    void init(int shadowUnit, int rawShadowUnit) {
        RenderThread.checkRenderThread();
        program = Shaders.program("tray.vert", "tray.frag");
        viewProjectionUniform = glGetUniformLocation(program, "uViewProjection");
        eyeUniform = glGetUniformLocation(program, "uEye");
        diceUniform = glGetUniformLocation(program, "uDice");
        diceCountUniform = glGetUniformLocation(program, "uDiceCount");
        tintUniform = glGetUniformLocation(program, "uTint");
        roughnessUniform = glGetUniformLocation(program, "uRoughness");
        sheenUniform = glGetUniformLocation(program, "uSheen");
        bumpUniform = glGetUniformLocation(program, "uBumpMm");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "uSurface"), SURFACE_UNIT);
        glUniform1f(glGetUniformLocation(program, "uShadeRange"), SurfaceTexture.SHADE_RANGE);
        lightUniforms = new LightUniforms(program, shadowUnit, rawShadowUnit);
        glUseProgram(0);
    }

    /**
     * Draws a Tray's shadow casters, its walls, with whatever depth-only program is in use (the floor and table only
     * receive). Builds its meshes first if it isn't the one drawn last.
     *
     * @param next the Tray whose walls to draw
     */
    void drawCasters(Tray next) {
        RenderThread.checkRenderThread();
        if (!next.equals(tray)) {
            rebuild(next);
        }
        walls.draw();
    }

    /**
     * Draws a Tray, building its meshes first if it isn't the one drawn last, lit by a Light Rig and shadowed by the
     * shadow map the caller has bound to the units given to {@link #init}. Depth testing is the caller's.
     *
     * @param next the Tray to draw
     * @param viewProjection the view-projection matrix, column-major
     * @param eye the camera's position, in mm
     * @param rig the Light Rig
     * @param lightView the key light's view the shadow map was drawn from
     * @param dice the Dice on it, which darken the floor beneath them; past {@value #MAX_DICE}, the rest don't
     */
    void draw(Tray next, float[] viewProjection, Vector3dc eye, LightRig rig, LightView lightView,
            List<DiceScene.Die> dice) {
        RenderThread.checkRenderThread();
        if (!next.equals(tray)) {
            rebuild(next);
        }
        glUseProgram(program);
        glUniformMatrix4fv(viewProjectionUniform, false, viewProjection);
        vector(eyeUniform, eye);
        lightUniforms.apply(rig, lightView);
        int count = Math.min(dice.size(), MAX_DICE);
        float[] spheres = new float[4 * MAX_DICE];
        for (int i = 0; i < count; i++) {
            DiceScene.Die die = dice.get(i);
            spheres[4 * i] = (float) die.position().x();
            spheres[4 * i + 1] = (float) die.position().y();
            spheres[4 * i + 2] = (float) die.position().z();
            spheres[4 * i + 3] = (float) (die.kind().inradius() * CONTACT_RADIUS_SCALE);
        }
        glUniform4fv(diceUniform, spheres);
        glUniform1i(diceCountUniform, count);

        SurfaceTexture.Kind floorKind = SurfaceTexture.Kind.of(tray.floor().surface());
        SurfaceTexture.Kind wallKind = SurfaceTexture.Kind.of(tray.walls().material());
        // The table first: the floor lies half a millimetre above it, so the floor wins wherever both are.
        drawPart(table, texture(SurfaceTexture.Kind.WOOD), TABLE_COLOUR, SurfaceLook.TABLE);
        drawPart(floor, texture(floorKind), linear(tray.floor().colour()), SurfaceLook.of(tray.floor().surface()));
        drawPart(walls, texture(wallKind), linear(tray.walls().colour()), SurfaceLook.of(tray.walls().material()));
        glUseProgram(0);
    }

    /** Draws one part with a surface, its tint and its look. */
    private void drawPart(GlPart part, int texture, float[] tint, SurfaceLook look) {
        glActiveTexture(GL_TEXTURE0 + SURFACE_UNIT);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform3f(tintUniform, tint[0], tint[1], tint[2]);
        glUniform1f(roughnessUniform, look.roughness());
        glUniform1f(sheenUniform, look.sheen());
        glUniform1f(bumpUniform, look.bumpMm());
        part.draw();
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Replaces the drawn meshes with the next Tray's. */
    private void rebuild(Tray next) {
        deleteParts();
        TrayMesh mesh = TrayMesh.of(next);
        floor = new GlPart(mesh.floor());
        walls = new GlPart(mesh.walls());
        table = new GlPart(mesh.table());
        tray = next;
    }

    /** A surface kind's texture, generated and uploaded on first use. */
    private int texture(SurfaceTexture.Kind kind) {
        return textures.computeIfAbsent(kind, TrayRenderer::upload);
    }

    /** Generates a surface and uploads it as a mipmapped, repeating {@code GL_RG8} texture. */
    private static int upload(SurfaceTexture.Kind kind) {
        SurfaceTexture surface = SurfaceTexture.generate(kind);
        int size = surface.sizePx();
        // Rows of two bytes per texel need not be 4-byte aligned; tell GL, and put the default back after.
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        ByteBuffer bytes = MemoryUtil.memAlloc(2 * size * size);
        int texture = glGenTextures();
        try {
            surface.put(bytes).flip();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, size, size, 0, GL_RG, GL_UNSIGNED_BYTE, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        }
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        // The surfaces tile: every pattern in them wraps at the edge.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL46 || caps.GL_ARB_texture_filter_anisotropic || caps.GL_EXT_texture_filter_anisotropic) {
            float limit = glGetFloat(MAX_TEXTURE_MAX_ANISOTROPY);
            glTexParameterf(GL_TEXTURE_2D, TEXTURE_MAX_ANISOTROPY, Math.min(ANISOTROPY, limit));
        }
        glBindTexture(GL_TEXTURE_2D, 0);
        return texture;
    }

    /** Sets a {@code vec3} uniform from a vector. */
    private static void vector(int location, Vector3dc v) {
        glUniform3f(location, (float) v.x(), (float) v.y(), (float) v.z());
    }

    /** A Tray file's sRGB colour as the shader's linear RGB. */
    private static float[] linear(SrgbColour colour) {
        return new float[] {(float) colour.linearRed(), (float) colour.linearGreen(), (float) colour.linearBlue()};
    }

    /** Deletes every GL object. */
    void dispose() {
        RenderThread.checkRenderThread();
        deleteParts();
        textures.values().forEach(t -> glDeleteTextures(t));
        textures.clear();
        glDeleteProgram(program);
    }

    private void deleteParts() {
        for (GlPart part : new GlPart[] {floor, walls, table}) {
            if (part != null) {
                part.delete();
            }
        }
        floor = null;
        walls = null;
        table = null;
        tray = null;
    }

    /** One {@link TrayMesh.Part} on the GPU: position (attribute 0), normal (1) and texture coordinate (2). */
    private static final class GlPart {
        private final int vertexArray;
        private final int vertices;
        private final int elements;
        private final int indexCount;

        GlPart(TrayMesh.Part part) {
            int[] indices = part.indices();
            indexCount = indices.length;
            vertexArray = glGenVertexArrays();
            glBindVertexArray(vertexArray);
            vertices = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vertices);
            glBufferData(GL_ARRAY_BUFFER, part.vertices(), GL_STATIC_DRAW);
            elements = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elements);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
            int stride = TrayMesh.FLOATS_PER_VERTEX * Float.BYTES;
            attribute(0, 3, stride, 0);
            attribute(1, 3, stride, 3);
            attribute(2, 2, stride, 6);
            // Unbind the vertex array first: it records the element buffer binding, which must stay with it.
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }

        private static void attribute(int location, int size, int stride, int offsetFloats) {
            glVertexAttribPointer(location, size, GL_FLOAT, false, stride, (long) offsetFloats * Float.BYTES);
            glEnableVertexAttribArray(location);
        }

        void draw() {
            glBindVertexArray(vertexArray);
            glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
            glBindVertexArray(0);
        }

        void delete() {
            glDeleteBuffers(elements);
            glDeleteBuffers(vertices);
            glDeleteVertexArrays(vertexArray);
        }
    }
}
