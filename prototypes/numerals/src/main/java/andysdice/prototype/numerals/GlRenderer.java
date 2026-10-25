package andysdice.prototype.numerals;

import com.huskerdev.openglfx.canvas.GLCanvas;
import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import com.huskerdev.openglfx.lwjgl.LWJGLExecutor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.opengl.GL33C.*;

/**
 * PROTOTYPE. Draws the tray and the seven dice with hand-written OpenGL 3.3 core shaders inside one
 * long-lived openglfx {@link GLCanvas}. The three switcher variants are a shader uniform plus, for C,
 * the dense mesh; one canvas for the whole run sidesteps the canvas-recreation hang the bake-off hit.
 *
 * <p>All GL calls happen on openglfx's render thread. {@link #stats()} and {@link #capture} are the
 * only methods the FX thread may call.
 */
final class GlRenderer {
    private static final int SHADOW_PX = 2048;

    private final SceneSpec scene;
    private final Params params;
    private final Builds builds;
    private final TrayTextures tray;
    final GLCanvas canvas;

    private volatile String glInfo = "(initialising)";
    private volatile double gpuMs;
    private volatile String error;
    /** Structure version and dense flag of what is on the GPU, for the HUD and capture gating. */
    volatile int uploadedVersion = -1;
    volatile boolean denseUploaded;
    /** Frames drawn since the last upload; capture waits for a few so state has settled. */
    private volatile int framesSinceUpload;

    private int litProgram, depthProgram;
    private final Map<String, Integer> litUniforms = new HashMap<>(), depthUniforms = new HashMap<>();
    private GpuMesh floorMesh;
    private final List<GpuMesh> wallMeshes = new ArrayList<>();
    private int feltTex, woodTex, shadowFbo, shadowTex;
    private final int[] queries = new int[3];
    private long frameIndex;

    private Builds.Built built;
    private final List<GpuMesh> coarse = new ArrayList<>(), dense = new ArrayList<>();
    private final List<int[]> atlasTex = new ArrayList<>();

    private record GpuMesh(int vao, int[] buffers, int indexCount) {
    }

    /** A pending screenshot: where to write it and who to tell. */
    record CaptureRequest(Path file, String caption, int version, boolean needDense, CompletableFuture<Void> done) {
    }

    private final AtomicReference<CaptureRequest> capture = new AtomicReference<>();
    private int resolveFbo, resolveRbo, resolveW, resolveH;

    GlRenderer(SceneSpec scene, Params params, Builds builds, TrayTextures tray) {
        this.scene = scene;
        this.params = params;
        this.builds = builds;
        this.tray = tray;
        canvas = new GLCanvas(new GLCanvas.Builder().setExecutor(LWJGLExecutor.LWJGL_MODULE).setMSAA(4).setFps(-1.0));
        canvas.addOnInitEvent(e -> guarded(this::init));
        canvas.addOnRenderEvent(e -> guarded(() -> render(e)));
        canvas.addOnDisposeEvent(e -> guarded(this::release));
    }

    String stats() {
        if (error != null) return "GL ERROR: " + error;
        return String.format("%s   GPU %.2f ms/frame", glInfo, gpuMs);
    }

    /**
     * Asks the render thread to save the next settled frame of the given structure version as a PNG.
     * Completes when the file is written.
     */
    CompletableFuture<Void> capture(Path file, String caption, int version, boolean needDense) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        capture.set(new CaptureRequest(file, caption, version, needDense, done));
        return done;
    }

    /** Surfaces exceptions in the HUD; openglfx would otherwise swallow them on the render thread. */
    private void guarded(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            error = ex.toString();
            ex.printStackTrace();
        }
    }

    private void init() {
        glInfo = glGetString(GL_RENDERER) + " | GL " + glGetString(GL_VERSION);
        System.out.println("renderer=" + glInfo + " interop=" + canvas.getInteropType());
        litProgram = program("lit.vert", "lit.frag");
        depthProgram = program("depth.vert", "depth.frag");
        floorMesh = upload(scene.floor);
        for (MeshData w : scene.walls) wallMeshes.add(upload(w));
        feltTex = argbTexture(tray.felt, TrayTextures.FELT_W, TrayTextures.FELT_H);
        woodTex = argbTexture(tray.wood, TrayTextures.WOOD_PX, TrayTextures.WOOD_PX);

        shadowTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, shadowTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, SHADOW_PX, SHADOW_PX, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (FloatBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        shadowFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTex, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("shadow FBO incomplete");
        glGenQueries(queries);
    }

    /** Swaps in a newer build: new meshes and atlases, then (later) the dense meshes. */
    private void syncBuild() {
        Builds.Built b = builds.latest();
        if (b == null || b == built) return;
        if (built == null || b.st().version() != built.st().version()) {
            for (GpuMesh m : coarse) delete(m);
            for (GpuMesh m : dense) delete(m);
            for (int[] t : atlasTex) glDeleteTextures(t);
            coarse.clear();
            dense.clear();
            atlasTex.clear();
            denseUploaded = false;
            for (int i = 0; i < b.models().size(); i++) {
                coarse.add(upload(b.models().get(i).mesh));
                Atlas a = b.atlases().get(i);
                atlasTex.add(new int[]{rgTexture(a.size, a.maskRg), rgFloatTexture(a.size, a.slopeRg)});
            }
            uploadedVersion = b.st().version();
        }
        if (b.dense() != null && dense.isEmpty()) {
            for (MeshData d : b.dense()) dense.add(upload(d));
            denseUploaded = true;
        }
        built = b;
        framesSinceUpload = 0;
    }

    private void render(GLRenderEvent e) {
        syncBuild();
        // Read the timer query from two frames ago, so we never stall waiting on the GPU.
        if (frameIndex >= queries.length) {
            int old = queries[(int) (frameIndex % queries.length)];
            if (glGetQueryObjecti(old, GL_QUERY_RESULT_AVAILABLE) != 0) gpuMs = glGetQueryObjecti64(old, GL_QUERY_RESULT) / 1e6;
        }
        glBeginQuery(GL_TIME_ELAPSED, queries[(int) (frameIndex % queries.length)]);

        glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
        glViewport(0, 0, e.width, e.height);
        glClearColor(18 / 255f, 18 / 255f, 20 / 255f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        if (built != null) drawScene(e);
        glEndQuery(GL_TIME_ELAPSED);
        frameIndex++;
        framesSinceUpload++;
        maybeCapture(e);
    }

    private void drawScene(GLRenderEvent e) {
        List<DieModel> models = built.models();
        SceneSpec.Frame f = scene.frame(models);
        Look look = scene.look();
        Params.Technique tech = params.technique;
        boolean useDense = tech == Params.Technique.C && !dense.isEmpty();
        float depth = params.visibleDepth();

        // Fit the key light's frustum to what the camera sees, so close-ups get fine shadow texels
        // (about 0.01 mm at macro, enough for the recesses in C to shadow themselves).
        float extent = Math.clamp(f.dist() * 0.55f, 16f, 170f);
        Vector3f lightTarget = new Vector3f(f.target()).mul(1, 0, 1);
        Matrix4f lightVP = new Matrix4f().ortho(-extent, extent, -extent, extent, 150, 450)
                .lookAt(new Vector3f(SceneSpec.KEY_DIR).mul(300).add(lightTarget), lightTarget, new Vector3f(0, 1, 0));

        // Pass 1: shadow map from the key light. Polygon offset handles slope; the shader bias is tiny.
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
        glViewport(0, 0, SHADOW_PX, SHADOW_PX);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.1f, 2f);
        glUseProgram(depthProgram);
        setMat(depthUniforms, depthProgram, "uLightVP", lightVP);
        glUniform1i(loc(depthUniforms, depthProgram, "uMask"), 0);
        for (SceneSpec.DieInstance d : f.dice()) {
            int k = d.kind().ordinal();
            setMat(depthUniforms, depthProgram, "uModel", d.model());
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, atlasTex.get(k)[0]);
            glUniform1f(loc(depthUniforms, depthProgram, "uDisplace"), useDense ? depth : 0f);
            glUniform1f(loc(depthUniforms, depthProgram, "uDisplaceLod"), displaceLod(models.get(k)));
            draw(useDense ? dense.get(k) : coarse.get(k));
        }
        glUniform1f(loc(depthUniforms, depthProgram, "uDisplace"), 0f);
        setMat(depthUniforms, depthProgram, "uModel", new Matrix4f());
        for (GpuMesh w : wallMeshes) draw(w);
        glDisable(GL_POLYGON_OFFSET_FILL);

        // Pass 2: the lit scene into openglfx's framebuffer.
        glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
        glViewport(0, 0, e.width, e.height);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glUseProgram(litProgram);
        float aspect = Math.max(1, e.width) / (float) Math.max(1, e.height);
        setMat(litUniforms, litProgram, "uProj", new Matrix4f().perspective((float) Math.toRadians(SceneSpec.FOV_Y_DEG), aspect, 2, 3000));
        setMat(litUniforms, litProgram, "uView", new Matrix4f().lookAt(f.eye(), f.target(), new Vector3f(0, 1, 0)));
        setMat(litUniforms, litProgram, "uLightVP", lightVP);
        setVec("uCamPos", f.eye());
        setVec("uKeyDir", SceneSpec.KEY_DIR);
        setVec("uKeyColor", SceneSpec.KEY_COLOR);
        setVec("uFillDir", SceneSpec.FILL_DIR);
        setVec("uFillColor", SceneSpec.FILL_COLOR);
        // Bias in light-NDC depth units over a 300 mm range: 0.00004 is about 0.01 mm.
        glUniform1f(lit("uShadowBias"), 0.00004f);
        glUniform3f(lit("uBase"), look.base()[0], look.base()[1], look.base()[2]);
        glUniform3f(lit("uInk"), look.ink()[0], look.ink()[1], look.ink()[2]);
        glUniform1f(lit("uRough"), look.roughness());
        glUniform1f(lit("uMetal"), look.metallic());
        glUniform1f(lit("uInkMetal"), look.inkMetallic());
        glUniform1f(lit("uDepth"), depth);
        glUniform1i(lit("uTechnique"), tech.ordinal());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer dice = stack.mallocFloat(32);
            for (SceneSpec.DieInstance d : f.dice()) {
                Vector3f c = d.centre();
                dice.put(c.x).put(c.y).put(c.z).put(d.kind().inradius * 1.05f);
            }
            while (dice.hasRemaining()) dice.put(0);
            dice.flip();
            glUniform4fv(lit("uDice"), dice);
        }
        glUniform1i(lit("uDiceCount"), f.dice().size());
        bindTex(3, shadowTex, "uShadow");
        glUniform1f(lit("uDisplace"), 0f);

        bindTex(2, feltTex, "uAlbedoTex");
        glUniform1i(lit("uKind"), 1);
        setMat(litUniforms, litProgram, "uModel", new Matrix4f());
        draw(floorMesh);
        bindTex(2, woodTex, "uAlbedoTex");
        glUniform1i(lit("uKind"), 2);
        for (GpuMesh w : wallMeshes) draw(w);

        glUniform1i(lit("uKind"), 0);
        for (SceneSpec.DieInstance d : f.dice()) {
            int k = d.kind().ordinal();
            DieModel m = models.get(k);
            bindTex(0, atlasTex.get(k)[0], "uMask");
            bindTex(1, atlasTex.get(k)[1], "uSlope");
            glUniform1f(lit("uMmPerUv"), m.mmPerUv());
            glUniform1f(lit("uDisplace"), useDense ? depth : 0f);
            glUniform1f(lit("uDisplaceLod"), displaceLod(m));
            setMat(litUniforms, litProgram, "uModel", d.model());
            draw(useDense ? dense.get(k) : coarse.get(k));
        }
        // Leave the context the way openglfx's own blit/interop passes expect to find it.
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    /** The atlas mip whose texel size matches the dense mesh's vertex spacing, so displacement doesn't alias. */
    private static float displaceLod(DieModel m) {
        float texelMm = 2 * m.cellHalfMm / Atlas.CELL_PX;
        return Math.max(0f, (float) (Math.log(DieModel.DENSE_SPACING_MM / texelMm) / Math.log(2)) - 0.5f);
    }

    /**
     * If a capture is pending and the frame shows the requested build, resolves openglfx's (possibly
     * multisampled) framebuffer into a plain one, reads it back and writes the PNG off-thread.
     */
    private void maybeCapture(GLRenderEvent e) {
        CaptureRequest req = capture.get();
        if (req == null || framesSinceUpload < 4 || uploadedVersion != req.version() || (req.needDense() && !denseUploaded)) return;
        if (!capture.compareAndSet(req, null)) return;
        int w = e.width, h = e.height;
        if (resolveFbo == 0 || w != resolveW || h != resolveH) {
            if (resolveFbo != 0) {
                glDeleteFramebuffers(resolveFbo);
                glDeleteRenderbuffers(resolveRbo);
            }
            resolveFbo = glGenFramebuffers();
            resolveRbo = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, resolveRbo);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
            glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, resolveRbo);
            resolveW = w;
            resolveH = h;
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, e.fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFbo);
        glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, resolveFbo);
        ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
        glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
        Thread.ofPlatform().daemon().start(() -> {
            try {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = ((h - 1 - y) * w + x) * 4; // GL rows run bottom-up
                        img.setRGB(x, y, (px.get(i) & 0xff) << 16 | (px.get(i + 1) & 0xff) << 8 | (px.get(i + 2) & 0xff));
                    }
                }
                var g = img.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 15));
                int y = 22;
                for (String line : req.caption().split("\n")) {
                    g.setColor(new java.awt.Color(0, 0, 0, 160));
                    g.fillRect(8, y - 15, g.getFontMetrics().stringWidth(line) + 10, 20);
                    g.setColor(new java.awt.Color(235, 235, 235));
                    g.drawString(line, 13, y);
                    y += 20;
                }
                g.dispose();
                ImageIO.write(img, "png", req.file().toFile());
                req.done().complete(null);
            } catch (IOException ex) {
                req.done().completeExceptionally(new UncheckedIOException(ex));
            }
        });
    }

    private void release() {
        glDeleteProgram(litProgram);
        glDeleteProgram(depthProgram);
        List<GpuMesh> all = new ArrayList<>(wallMeshes);
        all.add(floorMesh);
        all.addAll(coarse);
        all.addAll(dense);
        for (GpuMesh m : all) if (m != null) delete(m);
        for (int[] t : atlasTex) glDeleteTextures(t);
        glDeleteTextures(new int[]{feltTex, woodTex, shadowTex});
        glDeleteFramebuffers(shadowFbo);
        glDeleteQueries(queries);
    }

    private int lit(String name) {
        return loc(litUniforms, litProgram, name);
    }

    private void setVec(String name, Vector3f v) {
        glUniform3f(lit(name), v.x, v.y, v.z);
    }

    private void bindTex(int unit, int texture, String uniform) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(lit(uniform), unit);
    }

    private static void draw(GpuMesh m) {
        glBindVertexArray(m.vao());
        glDrawElements(GL_TRIANGLES, m.indexCount(), GL_UNSIGNED_INT, 0);
    }

    private static void delete(GpuMesh m) {
        glDeleteVertexArrays(m.vao());
        glDeleteBuffers(m.buffers());
    }

    private static GpuMesh upload(MeshData m) {
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int[] buffers = new int[6];
        float[][] attribs = {m.positions, m.normals, m.uvs, m.tangents, m.bitangents};
        int[] sizes = {3, 3, 2, 3, 3};
        for (int i = 0; i < attribs.length; i++) {
            buffers[i] = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, buffers[i]);
            glBufferData(GL_ARRAY_BUFFER, attribs[i], GL_STATIC_DRAW);
            glEnableVertexAttribArray(i);
            glVertexAttribPointer(i, sizes[i], GL_FLOAT, false, 0, 0);
        }
        buffers[5] = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[5]);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, m.indices, GL_STATIC_DRAW);
        glBindVertexArray(0);
        return new GpuMesh(vao, buffers, m.indices.length);
    }

    private static void mipmapped() {
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        var caps = GL.getCapabilities();
        if (caps.OpenGL46 || caps.GL_EXT_texture_filter_anisotropic || caps.GL_ARB_texture_filter_anisotropic) {
            glTexParameterf(GL_TEXTURE_2D, 0x84FE /* TEXTURE_MAX_ANISOTROPY */, 8f);
        }
    }

    /** Uploads packed ARGB ints (top row first). */
    private static int argbTexture(int[] argb, int w, int h) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        // An int ARGB is exactly what GL_BGRA + UNSIGNED_INT_8_8_8_8_REV describes: no swizzle needed.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, argb);
        mipmapped();
        return t;
    }

    /** Uploads interleaved 8-bit RG (top row first; UV v = 0 is the top of the image, as in the bake-off). */
    private static int rgTexture(int size, byte[] rg) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        ByteBuffer buf = BufferUtils.createByteBuffer(rg.length).put(rg).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, size, size, 0, GL_RG, GL_UNSIGNED_BYTE, buf);
        mipmapped();
        return t;
    }

    /** Uploads interleaved float RG as half floats (signed slopes). */
    private static int rgFloatTexture(int size, float[] rg) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        FloatBuffer buf = BufferUtils.createFloatBuffer(rg.length).put(rg).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16F, size, size, 0, GL_RG, GL_FLOAT, buf);
        mipmapped();
        return t;
    }

    private static int program(String vert, String frag) {
        int p = glCreateProgram();
        int vs = shader(GL_VERTEX_SHADER, vert), fs = shader(GL_FRAGMENT_SHADER, frag);
        glAttachShader(p, vs);
        glAttachShader(p, fs);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == 0) throw new IllegalStateException("link: " + glGetProgramInfoLog(p));
        glDeleteShader(vs);
        glDeleteShader(fs);
        return p;
    }

    private static int shader(int type, String name) {
        String src;
        try (InputStream in = GlRenderer.class.getResourceAsStream("shaders/" + name)) {
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(name + ": " + glGetShaderInfoLog(s));
        return s;
    }

    private static int loc(Map<String, Integer> cache, int program, String name) {
        return cache.computeIfAbsent(name, n -> glGetUniformLocation(program, n));
    }

    private static void setMat(Map<String, Integer> cache, int program, String name, Matrix4f m) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(loc(cache, program, name), false, m.get(stack.mallocFloat(16)));
        }
    }
}
