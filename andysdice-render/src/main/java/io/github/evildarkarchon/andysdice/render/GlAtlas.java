package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glGetFloat;
import static org.lwjgl.opengl.GL11C.glPixelStorei;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL11C.glTexParameterf;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL30C.GL_RG;
import static org.lwjgl.opengl.GL30C.GL_RG16F;
import static org.lwjgl.opengl.GL30C.GL_RG8;
import static org.lwjgl.opengl.GL30C.glGenerateMipmap;

import io.github.evildarkarchon.andysdice.render.numerals.NumeralAtlas;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * A {@link NumeralAtlas} uploaded as the engraving's two textures (ADR-0008): the mask as {@code GL_RG8} (R ink,
 * G profile) and the slopes as {@code GL_RG16F}, both mipmapped, clamped to the edge and, where the driver offers
 * it, anisotropically filtered (the faces are often seen at a grazing angle). The engraving shader
 * ({@code engraving.glsl}) samples them as {@code uMask} and {@code uSlope}.
 *
 * <p>Render thread only.
 */
final class GlAtlas {

    /** {@code GL_TEXTURE_MAX_ANISOTROPY} and its limit, core in GL 4.6 and the same in the ARB/EXT extensions. */
    private static final int TEXTURE_MAX_ANISOTROPY = 0x84FE;
    private static final int MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;
    private static final float ANISOTROPY = 8;

    private final int mask;
    private final int slope;
    private final float mmPerUv;

    /**
     * Uploads an atlas. The atlas itself can be dropped afterwards.
     *
     * @param atlas the rasterised atlas
     */
    GlAtlas(NumeralAtlas atlas) {
        RenderThread.checkRenderThread();
        int size = atlas.sizePx();
        mmPerUv = (float) atlas.layout().mmPerUv();
        // Rows of two bytes per pixel need not be 4-byte aligned; tell GL, and put the default back after.
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        // Native buffers freed straight away: an atlas is tens of MB, too much to leave to the garbage collector.
        ByteBuffer bytes = MemoryUtil.memAlloc(2 * size * size);
        try {
            atlas.putMask(bytes).flip();
            mask = texture(() -> glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, size, size, 0, GL_RG,
                    GL_UNSIGNED_BYTE, bytes));
        } finally {
            MemoryUtil.memFree(bytes);
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        FloatBuffer floats = MemoryUtil.memAllocFloat(2 * size * size);
        try {
            atlas.putSlopes(floats).flip();
            slope = texture(() -> glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16F, size, size, 0, GL_RG,
                    GL_FLOAT, floats));
        } finally {
            MemoryUtil.memFree(floats);
        }
    }

    /** Makes one mipmapped, clamped texture, filling level 0 with {@code upload}. */
    private static int texture(Runnable upload) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        upload.run();
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL46 || caps.GL_ARB_texture_filter_anisotropic || caps.GL_EXT_texture_filter_anisotropic) {
            float limit = glGetFloat(MAX_TEXTURE_MAX_ANISOTROPY);
            glTexParameterf(GL_TEXTURE_2D, TEXTURE_MAX_ANISOTROPY, Math.min(ANISOTROPY, limit));
        }
        glBindTexture(GL_TEXTURE_2D, 0);
        return texture;
    }

    /** @return mm on a flat face per unit of atlas UV, for the shader's {@code uMmPerUv} */
    float mmPerUv() {
        return mmPerUv;
    }

    /**
     * Binds the mask and slope textures to two texture units.
     *
     * @param maskUnit the unit for {@code uMask}
     * @param slopeUnit the unit for {@code uSlope}
     */
    void bind(int maskUnit, int slopeUnit) {
        glActiveTexture(GL_TEXTURE0 + maskUnit);
        glBindTexture(GL_TEXTURE_2D, mask);
        glActiveTexture(GL_TEXTURE0 + slopeUnit);
        glBindTexture(GL_TEXTURE_2D, slope);
        glActiveTexture(GL_TEXTURE0);
    }

    /** Deletes the textures. */
    void delete() {
        RenderThread.checkRenderThread();
        glDeleteTextures(mask);
        glDeleteTextures(slope);
    }
}
