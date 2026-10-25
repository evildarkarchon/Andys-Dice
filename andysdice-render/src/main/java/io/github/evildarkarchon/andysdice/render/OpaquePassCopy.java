package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBlitFramebuffer;
import static org.lwjgl.opengl.GL30C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30C.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;

import java.nio.ByteBuffer;

/**
 * The copy of the frame so far that translucent Dice refract (ADR-0009): after everything opaque is drawn (the Tray,
 * any opaque Dice), {@link #copy} blits the bound framebuffer's colour into a texture the uber-shader reads as
 * {@code uBackground}. Drawing into a framebuffer while sampling it would be a feedback loop, hence the copy.
 *
 * <p>The texture is made on the first copy and reallocated only when the frame's size changes, so a frame with no
 * translucent Dice costs nothing.
 *
 * <p>Render thread only.
 */
final class OpaquePassCopy {

    private int framebuffer;
    private int texture;
    private int width;
    private int height;

    /** Makes nothing yet: the first {@link #copy} allocates. */
    OpaquePassCopy() {}

    /**
     * Copies the colour drawn so far into the bound draw framebuffer into the copy's texture. Leaves the read and
     * draw framebuffer bindings as it found them, so it may be called in the middle of a frame.
     *
     * @param frameWidth the bound framebuffer's width in pixels
     * @param frameHeight its height
     */
    void copy(int frameWidth, int frameHeight) {
        RenderThread.checkRenderThread();
        int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        if (framebuffer == 0 || frameWidth != width || frameHeight != height) {
            allocate(frameWidth, frameHeight);
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, drawFramebuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        // Same size, so no filtering happens; NEAREST is also all a multisampled source would allow.
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
    }

    /**
     * Binds the copy to a texture unit, leaving unit 0 active. Before the first {@link #copy} it binds no texture,
     * which the shader never samples (an opaque Dice Set skips the translucent path).
     *
     * @param unit the texture unit {@code uBackground} reads
     */
    void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glActiveTexture(GL_TEXTURE0);
    }

    /** Deletes the texture and its framebuffer, if they were ever made. */
    void delete() {
        RenderThread.checkRenderThread();
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(texture);
            framebuffer = 0;
            texture = 0;
        }
    }

    /** (Re)makes the texture at the frame's size, with a framebuffer to blit into it. */
    private void allocate(int frameWidth, int frameHeight) {
        delete();
        width = frameWidth;
        height = frameHeight;
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        // One level, no mips: the shader reads level 0 explicitly. Linear, because a refracted ray lands between
        // pixels; clamped, because it may land at the frame's edge.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        int previous = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        // copy() rebinds both read and draw framebuffers right after, so only the draw binding matters here.
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previous);
    }
}
