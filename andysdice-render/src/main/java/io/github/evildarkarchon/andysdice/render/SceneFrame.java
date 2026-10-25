package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30C.GL_MAX_SAMPLES;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindRenderbuffer;
import static org.lwjgl.opengl.GL30C.glBlitFramebuffer;
import static org.lwjgl.opengl.GL30C.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30C.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL30C.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL30C.glGenRenderbuffers;
import static org.lwjgl.opengl.GL30C.glRenderbufferStorageMultisample;

/**
 * Where {@link DiceScene} draws a frame under a Quality Preset's render scale and MSAA (ADR-0002, ADR-0014): a
 * framebuffer of its own at the scaled size, multisampled if the preset says so, which {@link #end()} resolves and
 * scales up, with linear filtering, into the framebuffer that was bound when {@link #begin} was called (the render
 * target). The prototype's resolve: a multisampled blit can't scale, so a scaled multisampled frame is first
 * resolved into a single-sampled twin of its own size and then scaled.
 *
 * <p>At full scale with no MSAA the scene draws straight into the render target and this does nothing.
 *
 * <p>Its colour is RGBA8 like the render target's and {@link OpaquePassCopy}'s texture: a blit out of a multisampled
 * framebuffer needs the same format on both sides.
 *
 * <p>Render thread only. Storage is reallocated only when the drawn size or sample count changes.
 */
final class SceneFrame {

    private final int maxSamples;

    // The framebuffer drawn into, and (only for a scaled, multisampled frame) its single-sampled twin.
    private int framebuffer;
    private int colour;
    private int depth;
    private int resolveFramebuffer;
    private int resolveColour;
    private int allocatedWidth;
    private int allocatedHeight;
    private int allocatedSamples = -1;

    // This frame's sizes and the state begin() replaced, for end().
    private boolean drawingOwn;
    private int targetWidth;
    private int targetHeight;
    private int width;
    private int height;
    private int samples;
    private int savedDrawFramebuffer;
    private int savedReadFramebuffer;
    private final int[] savedViewport = new int[4];

    /** Asks the driver how many samples it allows; makes no framebuffer until a frame needs one. */
    SceneFrame() {
        RenderThread.checkRenderThread();
        maxSamples = glGetInteger(GL_MAX_SAMPLES);
    }

    /**
     * Starts a frame: binds the framebuffer the scene is drawn into and sets the viewport to its size.
     *
     * @param frameWidth the render target's width in pixels, the size the frame ends up at
     * @param frameHeight its height
     * @param preset the Quality Preset whose render scale and MSAA samples apply
     */
    void begin(int frameWidth, int frameHeight, QualityPreset preset) {
        targetWidth = frameWidth;
        targetHeight = frameHeight;
        width = Math.max(1, (int) Math.round(frameWidth * preset.renderScale()));
        height = Math.max(1, (int) Math.round(frameHeight * preset.renderScale()));
        // A driver allowing fewer samples than the preset asks for gets its most; one allowing none gets none.
        samples = Math.min(preset.msaaSamples(), maxSamples);
        drawingOwn = samples > 0 || width != frameWidth || height != frameHeight;
        if (!drawingOwn) {
            return;
        }
        savedDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        savedReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, savedViewport);
        if (width != allocatedWidth || height != allocatedHeight || samples != allocatedSamples) {
            allocate();
        }
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, width, height);
    }

    /** @return the width the scene is drawn at this frame, in pixels */
    int width() {
        return width;
    }

    /** @return the height the scene is drawn at this frame, in pixels */
    int height() {
        return height;
    }

    /**
     * Ends the frame: resolves and scales what was drawn into the framebuffer {@link #begin} found bound, and puts
     * back that binding and the viewport.
     */
    void end() {
        if (!drawingOwn) {
            return;
        }
        int source = framebuffer;
        boolean scaled = width != targetWidth || height != targetHeight;
        if (samples > 0 && scaled) {
            // A multisampled blit must keep the size: resolve into the twin first, then scale from that.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFramebuffer);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            source = resolveFramebuffer;
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, savedDrawFramebuffer);
        glBlitFramebuffer(0, 0, width, height, 0, 0, targetWidth, targetHeight, GL_COLOR_BUFFER_BIT,
                scaled ? GL_LINEAR : GL_NEAREST);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, savedReadFramebuffer);
        glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
    }

    /** Deletes every GL object made so far. */
    void delete() {
        RenderThread.checkRenderThread();
        release();
    }

    /** (Re)makes the framebuffers at this frame's size and sample count. */
    private void allocate() {
        release();
        framebuffer = glGenFramebuffers();
        colour = glGenRenderbuffers();
        depth = glGenRenderbuffers();
        // Sample count 0 makes ordinary single-sampled storage.
        storage(colour, GL_RGBA8);
        storage(depth, GL_DEPTH24_STENCIL8);
        attach(framebuffer, colour, depth);
        if (samples > 0) {
            resolveFramebuffer = glGenFramebuffers();
            resolveColour = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, resolveColour);
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, 0, GL_RGBA8, width, height);
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
            attach(resolveFramebuffer, resolveColour, 0);
        }
        allocatedWidth = width;
        allocatedHeight = height;
        allocatedSamples = samples;
    }

    private void storage(int renderbuffer, int format) {
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, format, width, height);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }

    /** Attaches a colour and (unless 0) a depth-stencil renderbuffer, and checks the framebuffer is complete. */
    private void attach(int target, int colourBuffer, int depthBuffer) {
        glBindFramebuffer(GL_FRAMEBUFFER, target);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colourBuffer);
        if (depthBuffer != 0) {
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);
        }
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, savedDrawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, savedReadFramebuffer);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("the scene's framebuffer is incomplete at " + width + "x" + height
                    + " with " + samples + " samples (status 0x" + Integer.toHexString(status) + ")");
        }
    }

    private void release() {
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer);
            glDeleteRenderbuffers(colour);
            glDeleteRenderbuffers(depth);
            framebuffer = 0;
        }
        if (resolveFramebuffer != 0) {
            glDeleteFramebuffers(resolveFramebuffer);
            glDeleteRenderbuffers(resolveColour);
            resolveFramebuffer = 0;
        }
        allocatedSamples = -1;
    }
}
