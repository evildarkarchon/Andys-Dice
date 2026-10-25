package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NONE;
import static org.lwjgl.opengl.GL11C.GL_POLYGON_OFFSET_FILL;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glDrawBuffer;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glPolygonOffset;
import static org.lwjgl.opengl.GL11C.glReadBuffer;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_FUNC;
import static org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.GL_COMPARE_REF_TO_TEXTURE;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30C.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;

import java.nio.FloatBuffer;

/**
 * The key light's shadow map (ADR-0010): one square depth texture drawn from the {@link LightView} each frame, before
 * the scene, with every Die and the Tray's walls as casters (the floor and table only receive). The shaders read it
 * twice through {@code shadow.glsl}: with depth comparison for the PCSS filter, and raw, through a sampler object of
 * its own, for the blocker search.
 *
 * <p>Its size is half of ADR-0002's shadow-quality lever (the filter's tap count is the other half): each
 * {@link #begin} is given the Quality Preset's size, and the texture is reallocated when that changes.
 *
 * <p>Render thread only.
 */
final class ShadowMap {

    /** Texels per side until a frame asks for another size: the tray prototype's, High's. */
    static final int SIZE_PX = 2048;

    private int sizePx = SIZE_PX;

    // The prototype's slope-scaled offset in the depth pass, on top of shadow.glsl's bias in mm.
    private static final float OFFSET_FACTOR = 1.1f;
    private static final float OFFSET_UNITS = 2f;

    private final int texture;
    private final int framebuffer;
    private final int rawSampler;
    private final int program;
    private final int modelUniform;
    private final int viewProjectionUniform;

    // The state begin() replaces and end() puts back.
    private int savedDrawFramebuffer;
    private int savedReadFramebuffer;
    private final int[] savedViewport = new int[4];

    /** Makes the depth texture, its framebuffer, the raw sampler and the depth-only program. */
    ShadowMap() {
        RenderThread.checkRenderThread();
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        allocate(SIZE_PX);
        // Linear filtering on a comparing texture gives each PCSS tap a 2x2 compare for free.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        glBindTexture(GL_TEXTURE_2D, 0);

        int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texture, 0);
        // Depth only: no colour buffer to draw to or read from (the draw and read buffer state is the framebuffer's).
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(texture);
            throw new IllegalStateException("shadow map framebuffer incomplete: 0x" + Integer.toHexString(status));
        }

        // PCSS's blocker search needs raw depths from the texture the filter compares against: a sampler object
        // overrides the texture's compare mode on its own unit (core since GL 3.3).
        rawSampler = glGenSamplers();
        glSamplerParameteri(rawSampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(rawSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(rawSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(rawSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(rawSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        program = Shaders.program("shadow.vert", "shadow.frag");
        modelUniform = glGetUniformLocation(program, "uModel");
        viewProjectionUniform = glGetUniformLocation(program, "uLightViewProjection");
    }

    /**
     * Starts drawing the map from a light's view: binds its framebuffer, viewport and depth-only program, and clears
     * it. Every caster is then drawn after a {@link #model} call. Remembers the framebuffers and viewport it replaces,
     * for {@link #end()}.
     *
     * @param view the key light's view this frame
     * @param size the map's texels per side this frame, the Quality Preset's; a change reallocates the texture
     */
    void begin(LightView view, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("a shadow map is at least 1 texel across, not " + size);
        }
        if (size != sizePx) {
            // Re-specifying the attached texture's image keeps the framebuffer's attachment; the image is redrawn
            // in full below anyway.
            glBindTexture(GL_TEXTURE_2D, texture);
            allocate(size);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
        savedDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        savedReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, savedViewport);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, sizePx, sizePx);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        // No culling, as in the prototype: the walls are open shells that cast from either side.
        glDisable(GL_CULL_FACE);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(OFFSET_FACTOR, OFFSET_UNITS);
        glUseProgram(program);
        glUniformMatrix4fv(viewProjectionUniform, false, DiceScene.columns(view.viewProjection()));
    }

    /**
     * Sets the model matrix of the next caster drawn.
     *
     * @param columns the model-to-world matrix, column-major; the identity for the Tray's walls
     */
    void model(float[] columns) {
        glUniformMatrix4fv(modelUniform, false, columns);
    }

    /** Finishes the map and puts back the framebuffers and viewport {@link #begin} found. Depth testing is left off. */
    void end() {
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_DEPTH_TEST);
        glUseProgram(0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, savedDrawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, savedReadFramebuffer);
        glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
    }

    /**
     * Binds the map for the scene's shaders: comparing on one unit, raw (through the sampler object) on another.
     *
     * @param compareUnit the unit {@code uShadow} reads
     * @param rawUnit the unit {@code uShadowRaw} reads
     */
    void bind(int compareUnit, int rawUnit) {
        glActiveTexture(GL_TEXTURE0 + compareUnit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glActiveTexture(GL_TEXTURE0 + rawUnit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindSampler(rawUnit, rawSampler);
        glActiveTexture(GL_TEXTURE0);
    }

    /**
     * Unbinds what {@link #bind} bound, so the sampler object can't override some later texture on its unit.
     *
     * @param compareUnit the unit {@code uShadow} read
     * @param rawUnit the unit {@code uShadowRaw} read
     */
    void unbind(int compareUnit, int rawUnit) {
        glBindSampler(rawUnit, 0);
        glActiveTexture(GL_TEXTURE0 + rawUnit);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0 + compareUnit);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
    }

    /**
     * Gives the bound depth texture storage of a size, and remembers it.
     *
     * @param size texels per side
     */
    private void allocate(int size) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0, GL_DEPTH_COMPONENT, GL_FLOAT,
                (FloatBuffer) null);
        sizePx = size;
    }

    /** Deletes every GL object. */
    void delete() {
        RenderThread.checkRenderThread();
        glDeleteProgram(program);
        glDeleteSamplers(rawSampler);
        glDeleteFramebuffers(framebuffer);
        glDeleteTextures(texture);
    }
}
