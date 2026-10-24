package andysdice.prototype.bakeoff;

import com.huskerdev.openglfx.canvas.GLCanvas;
import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import com.huskerdev.openglfx.internal.GLInteropType;
import com.huskerdev.openglfx.lwjgl.LWJGLExecutor;
import javafx.scene.Node;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * PROTOTYPE. Variants B and C: the same scene drawn with hand-written OpenGL 3.3 core shaders
 * (LWJGL) inside an openglfx {@link GLCanvas} embedded in the JavaFX window.
 *
 * <p>B lets openglfx pick its interop (zero-copy GPU sharing where the driver allows it); C forces
 * {@link GLInteropType#Blit}, the CPU read-back path that is also what a public-API-only
 * {@code PixelBuffer} embed would cost.
 *
 * <p>All GL calls happen on the thread openglfx renders on (Prism's render thread), which is also
 * where LWJGL's thread-local capabilities were created, so nothing here may be called from the FX
 * thread except {@link #stats()} and {@link #dispose()}.
 */
final class GlVariant implements Variant {
    private static final int SHADOW_PX = 2048;

    private final SceneSpec spec;
    private final Textures tex;
    private final String key;
    private final String title;
    private final GLCanvas canvas;

    // Written on the render thread, read by the HUD on the FX thread.
    private volatile String glInfo = "(initialising)";
    private volatile int renderFps;
    private volatile double cpuMs;
    private volatile double gpuMs;
    private volatile String error;

    private int litProgram, depthProgram;
    private final Map<String, Integer> litUniforms = new HashMap<>(), depthUniforms = new HashMap<>();
    private GpuMesh dieMesh, floorMesh;
    private final List<GpuMesh> wallMeshes = new ArrayList<>();
    private int maskTex, normalTex, feltTex, woodTex;
    private int shadowFbo, shadowTex;
    private final int[] queries = new int[3];
    private long frameIndex;

    private record GpuMesh(int vao, int[] buffers, int indexCount) {
    }

    GlVariant(SceneSpec spec, Textures tex, String key, GLInteropType forcedInterop, boolean uncapped) {
        this.spec = spec;
        this.tex = tex;
        this.key = key;
        GLCanvas.Builder b = new GLCanvas.Builder()
                .setExecutor(LWJGLExecutor.LWJGL_MODULE)
                .setMSAA(4)
                .setFps(uncapped ? 1000.0 : -1.0);
        if (forcedInterop != null) b.setInteropType(forcedInterop);
        canvas = new GLCanvas(b);
        this.title = "openglfx + LWJGL GL (" + canvas.getInteropType() + (forcedInterop == null ? ", auto" : ", forced") + ")";
        canvas.addOnInitEvent(e -> guarded(this::init));
        canvas.addOnRenderEvent(e -> guarded(() -> render(e)));
        canvas.addOnDisposeEvent(e -> guarded(this::release));
    }

    @Override public String key() { return key; }

    @Override public String title() { return title; }

    @Override public Node node() { return canvas; }

    @Override public void update() {
        // openglfx repaints on its own schedule (fps property); nothing to push from the FX thread.
    }

    @Override
    public String stats() {
        if (error != null) return "GL ERROR: " + error;
        return String.format("%s%nGL render calls/s: %d   CPU %.2f ms   GPU %.2f ms (timer query)", glInfo, renderFps, cpuMs, gpuMs);
    }

    @Override public void dispose() {
        canvas.dispose();
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
        System.out.println("[" + key + "] interop=" + canvas.getInteropType() + " renderer=" + glInfo);
        litProgram = program("lit.vert", "lit.frag");
        depthProgram = program("depth.vert", "depth.frag");

        dieMesh = upload(spec.d20.mesh);
        floorMesh = upload(spec.floor);
        for (MeshData w : spec.walls) wallMeshes.add(upload(w));

        maskTex = texture(tex.atlasMask, Textures.ATLAS_PX, Textures.ATLAS_PX);
        normalTex = texture(tex.atlasNormal, Textures.ATLAS_PX, Textures.ATLAS_PX);
        feltTex = texture(tex.felt, Textures.FELT_W, Textures.FELT_H);
        woodTex = texture(tex.wood, Textures.WOOD_PX, Textures.WOOD_PX);

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

    private void render(GLRenderEvent e) {
        long t0 = System.nanoTime();
        renderFps = e.fps;
        // Read the timer query from two frames ago, so we never stall waiting on the GPU.
        if (frameIndex >= queries.length) {
            int old = queries[(int) (frameIndex % queries.length)];
            if (glGetQueryObjecti(old, GL_QUERY_RESULT_AVAILABLE) != 0) gpuMs = glGetQueryObjecti64(old, GL_QUERY_RESULT) / 1e6;
        }
        glBeginQuery(GL_TIME_ELAPSED, queries[(int) (frameIndex % queries.length)]);

        SceneSpec.Frame f = spec.frame();
        Look look = spec.look();
        float extent = spec.twenty ? 190 : 70;
        Matrix4f lightVP = new Matrix4f().ortho(-extent, extent, -extent, extent, 50, 900)
                .lookAt(new Vector3f(SceneSpec.KEY_DIR).mul(450), new Vector3f(0, 0, 0), new Vector3f(0, 1, 0));

        // Pass 1: shadow map from the key light. Polygon offset instead of a large shader bias.
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
        glViewport(0, 0, SHADOW_PX, SHADOW_PX);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.5f, 3f);
        glUseProgram(depthProgram);
        setMat(depthUniforms, depthProgram, "uLightVP", lightVP);
        for (Matrix4f m : f.dice()) {
            setMat(depthUniforms, depthProgram, "uModel", m);
            draw(dieMesh);
        }
        setMat(depthUniforms, depthProgram, "uModel", new Matrix4f());
        for (GpuMesh w : wallMeshes) draw(w);
        glDisable(GL_POLYGON_OFFSET_FILL);

        // Pass 2: the lit scene into openglfx's framebuffer.
        glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
        glViewport(0, 0, e.width, e.height);
        glClearColor(18 / 255f, 18 / 255f, 20 / 255f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glUseProgram(litProgram);
        float aspect = Math.max(1, e.width) / (float) Math.max(1, e.height);
        setMat(litUniforms, litProgram, "uProj", new Matrix4f().perspective((float) Math.toRadians(SceneSpec.FOV_Y_DEG), aspect, 1, 3000));
        setMat(litUniforms, litProgram, "uView", new Matrix4f().lookAt(f.eye(), f.target(), new Vector3f(0, 1, 0)));
        setMat(litUniforms, litProgram, "uLightVP", lightVP);
        setVec(litUniforms, litProgram, "uCamPos", f.eye());
        setVec(litUniforms, litProgram, "uKeyDir", SceneSpec.KEY_DIR);
        setVec(litUniforms, litProgram, "uKeyColor", SceneSpec.KEY_COLOR);
        setVec(litUniforms, litProgram, "uFillDir", SceneSpec.FILL_DIR);
        setVec(litUniforms, litProgram, "uFillColor", SceneSpec.FILL_COLOR);
        glUniform1f(loc(litUniforms, litProgram, "uEnvStrength"), 1.0f);
        glUniform1f(loc(litUniforms, litProgram, "uParallax"), Textures.ENGRAVE_DEPTH_MM / Textures.CELL_MM / D20.ATLAS_GRID);
        glUniform3f(loc(litUniforms, litProgram, "uBase"), look.base()[0], look.base()[1], look.base()[2]);
        glUniform3f(loc(litUniforms, litProgram, "uInk"), look.ink()[0], look.ink()[1], look.ink()[2]);
        glUniform1f(loc(litUniforms, litProgram, "uRough"), look.roughness());
        glUniform1f(loc(litUniforms, litProgram, "uMetal"), look.metallic());
        glUniform1f(loc(litUniforms, litProgram, "uInkMetal"), look.inkMetallic());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer dice = stack.mallocFloat(80);
            for (Vector3f c : f.diceCentres()) dice.put(c.x).put(c.y).put(c.z).put(D20.INRADIUS * 1.05f);
            while (dice.hasRemaining()) dice.put(0);
            dice.flip();
            glUniform4fv(loc(litUniforms, litProgram, "uDice"), dice);
        }
        glUniform1i(loc(litUniforms, litProgram, "uDiceCount"), f.diceCentres().size());
        bindTex(0, maskTex, "uMask");
        bindTex(1, normalTex, "uNormalMap");
        bindTex(3, shadowTex, "uShadow");

        bindTex(2, feltTex, "uAlbedoTex");
        glUniform1i(loc(litUniforms, litProgram, "uKind"), 1);
        setMat(litUniforms, litProgram, "uModel", new Matrix4f());
        draw(floorMesh);

        bindTex(2, woodTex, "uAlbedoTex");
        glUniform1i(loc(litUniforms, litProgram, "uKind"), 2);
        for (GpuMesh w : wallMeshes) draw(w);

        glUniform1i(loc(litUniforms, litProgram, "uKind"), 0);
        for (Matrix4f m : f.dice()) {
            setMat(litUniforms, litProgram, "uModel", m);
            draw(dieMesh);
        }
        // Leave the context the way openglfx's own blit/interop passes expect to find it.
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glBindVertexArray(0);
        glUseProgram(0);

        glEndQuery(GL_TIME_ELAPSED);
        frameIndex++;
        cpuMs = (System.nanoTime() - t0) / 1e6;
    }

    private void release() {
        glDeleteProgram(litProgram);
        glDeleteProgram(depthProgram);
        List<GpuMesh> all = new ArrayList<>(wallMeshes);
        all.add(dieMesh);
        all.add(floorMesh);
        for (GpuMesh m : all) {
            if (m == null) continue;
            glDeleteVertexArrays(m.vao());
            glDeleteBuffers(m.buffers());
        }
        glDeleteTextures(new int[]{maskTex, normalTex, feltTex, woodTex, shadowTex});
        glDeleteFramebuffers(shadowFbo);
        glDeleteQueries(queries);
    }

    private void bindTex(int unit, int texture, String uniform) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(loc(litUniforms, litProgram, uniform), unit);
    }

    private static void draw(GpuMesh m) {
        glBindVertexArray(m.vao());
        glDrawElements(GL_TRIANGLES, m.indexCount(), GL_UNSIGNED_INT, 0);
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

    /** Uploads packed ARGB ints (top row first) with mipmaps and anisotropic filtering where available. */
    private static int texture(int[] argb, int w, int h) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        // An int ARGB is exactly what GL_BGRA + UNSIGNED_INT_8_8_8_8_REV describes: no swizzle needed.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, argb);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        var caps = GL.getCapabilities();
        if (caps.OpenGL46 || caps.GL_EXT_texture_filter_anisotropic || caps.GL_ARB_texture_filter_anisotropic) {
            glTexParameterf(GL_TEXTURE_2D, 0x84FE /* TEXTURE_MAX_ANISOTROPY */, 8f);
        }
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
        try (InputStream in = GlVariant.class.getResourceAsStream("shaders/" + name)) {
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
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

    private static void setVec(Map<String, Integer> cache, int program, String name, Vector3f v) {
        glUniform3f(loc(cache, program, name), v.x, v.y, v.z);
    }
}
