package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30C.GL_MAX_RENDERBUFFER_SIZE;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindRenderbuffer;
import static org.lwjgl.opengl.GL30C.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30C.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL30C.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL30C.glGenRenderbuffers;
import static org.lwjgl.opengl.GL30C.glRenderbufferStorage;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.glReadBuffer;

/**
 * The one render surface (ADR-0002): a framebuffer object with an RGBA8 colour and a depth-stencil
 * renderbuffer. Made once with the context, resized by reallocating its renderbuffers' storage, never deleted
 * and recreated; deleted only at exit. Its colour attachment is what the transport reads back.
 *
 * <p>Render thread only.
 */
final class RenderTarget {

    private final int framebuffer;
    private final int colour;
    private final int depth;
    private final int maxSize;
    private int width;
    private int height;

    /** Creates the framebuffer with no storage yet; {@link #resize} gives it some. */
    RenderTarget() {
        RenderThread.checkRenderThread();
        framebuffer = glGenFramebuffers();
        colour = glGenRenderbuffers();
        depth = glGenRenderbuffers();
        maxSize = glGetInteger(GL_MAX_RENDERBUFFER_SIZE);
    }

    /**
     * Reallocates the renderbuffers at a new size. The framebuffer object itself is kept.
     *
     * @param requestedWidth the wanted width in pixels; clamped to the driver's renderbuffer limit
     * @param requestedHeight the wanted height in pixels; clamped likewise
     * @throws IllegalStateException if the driver reports the resized framebuffer incomplete
     */
    void resize(int requestedWidth, int requestedHeight) {
        RenderThread.checkRenderThread();
        width = Math.clamp(requestedWidth, 1, maxSize);
        height = Math.clamp(requestedHeight, 1, maxSize);
        glBindRenderbuffer(GL_RENDERBUFFER, colour);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colour);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("the render target is incomplete at " + width + "x" + height
                    + " (status 0x" + Integer.toHexString(status) + ")");
        }
    }

    /** @return the current width in pixels */
    int width() {
        return width;
    }

    /** @return the current height in pixels */
    int height() {
        return height;
    }

    /** Binds the framebuffer for drawing and reading. */
    void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    }

    /** Binds the framebuffer as the read framebuffer, reading colour attachment 0, for the transport. */
    void bindForReading() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
    }

    /** Deletes the framebuffer and its renderbuffers. At exit only. */
    void delete() {
        RenderThread.checkRenderThread();
        glDeleteFramebuffers(framebuffer);
        glDeleteRenderbuffers(colour);
        glDeleteRenderbuffers(depth);
    }
}
