package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_RED;
import static org.lwjgl.opengl.GL11C.GL_REPEAT;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL12C.glTexImage3D;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_R8;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glFramebufferTextureLayer;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The Dice Sets' swirl fields baked on the GPU into 3D textures (ADR-0009): {@value #SIZE_PX}³ texels of
 * {@code GL_R8} (2 MB) holding exactly one {@value #PERIOD_MM} mm period of the periodic, domain-warped noise, so
 * the texture wraps with {@code GL_REPEAT} and a per-Die offset is all a Die needs to differ. The uber-shader only
 * reads them; {@code bake.frag} is the one place the noise is evaluated.
 *
 * <p>Baked on first use and kept until {@link #delete()}: a bake takes a few milliseconds, so a Dice Set chosen
 * mid-run costs one slightly late frame rather than anything at start-up (the swirl-less majority never bake).
 * Dice Sets whose fields are the same share one texture.
 *
 * <p>Render thread only.
 */
final class SwirlVolumes {

    /** Texels per side of a volume. */
    static final int SIZE_PX = 128;

    /** The mm of model space one volume spans, along each axis. */
    static final float PERIOD_MM = 32f;

    /**
     * What decides a baked field: the noise cells per period, the warp and the bands. The swirl's colour, amount
     * and softness are applied by the uber-shader, so they don't need a bake of their own.
     */
    private record Field(int cellsPerPeriod, double warp, double bands) {

        static Field of(DiceSet.Swirl swirl) {
            // The nearest whole number of cells to the Dice Set's scale, so that the field still tiles.
            return new Field((int) Math.max(1, Math.round(PERIOD_MM / swirl.scale())), swirl.warp(), swirl.bands());
        }
    }

    private final Map<Field, Integer> textures = new HashMap<>();
    private final int program;
    private final int sliceUniform;
    private final int sizeUniform;
    private final int periodUniform;
    private final int warpUniform;
    private final int bandsUniform;
    // Core profile draws need a vertex array bound, even for a triangle made from gl_VertexID alone.
    private final int emptyVertexArray;

    /** Builds the bake program. */
    SwirlVolumes() {
        RenderThread.checkRenderThread();
        program = Shaders.program("bake.vert", "bake.frag");
        sliceUniform = glGetUniformLocation(program, "uSlice");
        sizeUniform = glGetUniformLocation(program, "uSize");
        periodUniform = glGetUniformLocation(program, "uPeriod");
        warpUniform = glGetUniformLocation(program, "uWarp");
        bandsUniform = glGetUniformLocation(program, "uBands");
        emptyVertexArray = glGenVertexArrays();
    }

    /**
     * The volume of a Dice Set's swirl, baking it if this is its first use. Leaves the framebuffer bindings, the
     * viewport and the program as it found them, so it may be called in the middle of a frame.
     *
     * @param swirl the swirl, which must have a non-zero amount
     * @return the {@code GL_TEXTURE_3D} texture
     */
    int volume(DiceSet.Swirl swirl) {
        RenderThread.checkRenderThread();
        return textures.computeIfAbsent(Field.of(swirl), this::bake);
    }

    /** @return how many volumes have been baked; for tests */
    int baked() {
        return textures.size();
    }

    private int bake(Field field) {
        int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);

        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, texture);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, SIZE_PX, SIZE_PX, SIZE_PX, 0, GL_RED, GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        // No mipmaps: the field is soft at this resolution, and the shader reads level 0 explicitly.
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);
        glBindTexture(GL_TEXTURE_3D, 0);

        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, SIZE_PX, SIZE_PX);
        glUseProgram(program);
        glUniform1f(sizeUniform, SIZE_PX);
        glUniform1f(periodUniform, field.cellsPerPeriod());
        glUniform1f(warpUniform, (float) field.warp());
        glUniform1f(bandsUniform, (float) field.bands());
        glBindVertexArray(emptyVertexArray);
        // One draw per z-slice: GL 3.3 can render into one layer of a 3D texture at a time.
        for (int z = 0; z < SIZE_PX; z++) {
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texture, 0, z);
            glUniform1f(sliceUniform, z);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        glBindVertexArray(0);
        glDeleteFramebuffers(framebuffer);

        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glUseProgram(previousProgram);
        return texture;
    }

    /** Deletes every volume and the bake program. */
    void delete() {
        RenderThread.checkRenderThread();
        textures.values().forEach(t -> glDeleteTextures(t));
        textures.clear();
        glDeleteVertexArrays(emptyVertexArray);
        glDeleteProgram(program);
    }
}
