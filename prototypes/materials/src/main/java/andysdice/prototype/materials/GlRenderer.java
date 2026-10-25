package andysdice.prototype.materials;

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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.opengl.GL33C.*;

/**
 * PROTOTYPE. Draws the tray and the dice with hand-written OpenGL 3.3 core shaders inside one long-lived
 * openglfx {@link GLCanvas}. The tray keeps the bake-off's {@code lit} shader; the dice use
 * {@code die.frag}, one uber-shader for every Dice Set.
 *
 * <p>Per frame: shadow map, tray, opaque dice, then (for C, if any translucent Dice Set is on screen)
 * a copy of the frame so far into {@link #bgTex}, then the translucent dice, which read the copy to
 * see the tray through themselves. No blending is used: each translucent die composites what is behind
 * it in its own shader, so draw order among them does not matter (and they cannot see each other).
 *
 * <p>All GL calls happen on openglfx's render thread. {@link #stats()} and {@link #capture} are the
 * only methods the FX thread may call.
 */
final class GlRenderer {
    private static final int SHADOW_PX = 2048;
    /** Side of a baked swirl volume; it spans one {@link #PERIOD_MM} period of the field. */
    static final int SWIRL_PX = 128;
    /** Must match PERIOD_MM in die.frag. */
    static final float PERIOD_MM = 32f;

    private final SceneSpec scene;
    private final Params params;
    private final Builds builds;
    private final TrayTextures tray;
    final GLCanvas canvas;

    private volatile String glInfo = "(initialising)";
    private volatile double gpuMs;
    private volatile String error;
    volatile int uploadedVersion = -1;
    /** Last bake, for the HUD. */
    volatile String bakeInfo = "";

    private int litProgram, depthProgram, dieProgram, bakeProgram;
    private final Map<String, Integer> litUniforms = new HashMap<>(), depthUniforms = new HashMap<>(),
            dieUniforms = new HashMap<>(), bakeUniforms = new HashMap<>();
    private GpuMesh floorMesh;
    private final List<GpuMesh> wallMeshes = new ArrayList<>();
    private int feltTex, woodTex, shadowFbo, shadowTex, emptyVao;
    private final int[] queries = new int[3];
    private long frameIndex;

    private Builds.Built built;
    private final List<GpuMesh> coarse = new ArrayList<>();
    private final List<int[]> atlasTex = new ArrayList<>();
    private final List<float[]> faceTables = new ArrayList<>();

    /** Baked swirl volumes, one per Dice Set with a swirl, for the catalogue they were baked from. */
    private final Map<DiceSet, Integer> swirlTex = new IdentityHashMap<>();
    private List<DiceSet> bakedFor;
    private int bgFbo, bgTex, bgW, bgH;

    private record GpuMesh(int vao, int[] buffers, int indexCount) {
    }

    /** A pending screenshot: where to write it and who to tell. */
    record CaptureRequest(Path file, String caption, int version, CompletableFuture<Void> done) {
    }

    private final AtomicReference<CaptureRequest> capture = new AtomicReference<>();
    /** Frames drawn since the pending capture was armed, and the GPU time summed over the measured ones. */
    private int captureFrames;
    private double captureGpuSum;
    private int captureGpuCount;
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
     * Asks the render thread to save a settled frame of the given structure version as a PNG. It waits
     * 40 frames, averages the GPU time over the last 30 and appends it to the caption. Completes when the
     * file is written.
     */
    CompletableFuture<Void> capture(Path file, String caption, int version) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        capture.set(new CaptureRequest(file, caption, version, done));
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
        dieProgram = program("die.vert", "die.frag");
        bakeProgram = program("bake.vert", "bake.frag");
        emptyVao = glGenVertexArrays();
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

    /** Swaps in a newer build: new meshes, atlases and face tables. */
    private void syncBuild() {
        Builds.Built b = builds.latest();
        if (b == null || b == built) return;
        for (GpuMesh m : coarse) delete(m);
        for (int[] t : atlasTex) glDeleteTextures(t);
        coarse.clear();
        atlasTex.clear();
        faceTables.clear();
        for (int i = 0; i < b.models().size(); i++) {
            coarse.add(upload(b.models().get(i).mesh));
            Atlas a = b.atlases().get(i);
            atlasTex.add(new int[]{rgTexture(a.size, a.maskRg), rgFloatTexture(a.size, a.slopeRg)});
            faceTables.add(b.models().get(i).faceTable());
        }
        uploadedVersion = b.st().version();
        built = b;
    }

    /**
     * Re-bakes every swirl volume when the catalogue has been (re)loaded. Each is one draw per z-slice of
     * the same field die.frag evaluates live, so the two swirl sources show the same pattern.
     */
    private void syncSwirls() {
        List<DiceSet> sets = scene.sets;
        if (sets == bakedFor) return;
        for (int t : swirlTex.values()) glDeleteTextures(t);
        swirlTex.clear();
        long t0 = System.nanoTime();
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, SWIRL_PX, SWIRL_PX);
        glDisable(GL_DEPTH_TEST);
        glUseProgram(bakeProgram);
        glBindVertexArray(emptyVao);
        for (DiceSet s : sets) {
            if (!s.hasSwirl()) continue;
            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_3D, tex);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, SWIRL_PX, SWIRL_PX, SWIRL_PX, 0, GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            for (int w : new int[]{GL_TEXTURE_WRAP_S, GL_TEXTURE_WRAP_T, GL_TEXTURE_WRAP_R}) glTexParameteri(GL_TEXTURE_3D, w, GL_REPEAT);
            glUniform1f(loc(bakeUniforms, bakeProgram, "uSize"), SWIRL_PX);
            glUniform1f(loc(bakeUniforms, bakeProgram, "uPeriod"), swirlPeriod(s));
            glUniform1f(loc(bakeUniforms, bakeProgram, "uWarp"), s.swirlWarp());
            glUniform1f(loc(bakeUniforms, bakeProgram, "uBands"), s.swirlBands());
            for (int z = 0; z < SWIRL_PX; z++) {
                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, tex, 0, z);
                glUniform1f(loc(bakeUniforms, bakeProgram, "uSlice"), z);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }
            swirlTex.put(s, tex);
        }
        glFinish();
        glDeleteFramebuffers(fbo);
        glBindVertexArray(0);
        bakedFor = sets;
        bakeInfo = String.format("baked %d swirl volumes (%d^3 R8, %.1f MB) in %d ms", swirlTex.size(), SWIRL_PX,
                swirlTex.size() * Math.pow(SWIRL_PX, 3) / 1048576.0, (System.nanoTime() - t0) / 1_000_000);
        System.out.println(bakeInfo);
    }

    /** Noise cells per period: the nearest whole number to the Dice Set's scale, so the field still tiles. */
    private static float swirlPeriod(DiceSet s) {
        return Math.max(1, Math.round(PERIOD_MM / s.swirlScale()));
    }

    private void render(GLRenderEvent e) {
        syncBuild();
        syncSwirls();
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
        maybeCapture(e);
    }

    private void drawScene(GLRenderEvent e) {
        List<DieModel> models = built.models();
        SceneSpec.Frame f = scene.frame(models);
        Params.Technique tech = params.technique;
        float depth = params.visibleDepth();

        // Fit the key light's frustum to what the camera sees, so close-ups get fine shadow texels.
        float extent = Math.clamp(f.dist() * 0.55f, 16f, 170f);
        Vector3f lightTarget = new Vector3f(f.target()).mul(1, 0, 1);
        Matrix4f lightVP = new Matrix4f().ortho(-extent, extent, -extent, extent, 150, 450)
                .lookAt(new Vector3f(SceneSpec.KEY_DIR).mul(300).add(lightTarget), lightTarget, new Vector3f(0, 1, 0));

        // Pass 1: shadow map from the key light. Translucent dice cast full shadows (a prototype shortcut).
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
        glViewport(0, 0, SHADOW_PX, SHADOW_PX);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.1f, 2f);
        glUseProgram(depthProgram);
        setMat(depthUniforms, depthProgram, "uLightVP", lightVP);
        glUniform1f(loc(depthUniforms, depthProgram, "uDisplace"), 0f);
        for (SceneSpec.DieInstance d : f.dice()) {
            setMat(depthUniforms, depthProgram, "uModel", d.model());
            draw(coarse.get(d.kind().ordinal()));
        }
        setMat(depthUniforms, depthProgram, "uModel", new Matrix4f());
        for (GpuMesh w : wallMeshes) draw(w);
        glDisable(GL_POLYGON_OFFSET_FILL);

        // Pass 2: the tray into openglfx's framebuffer.
        glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
        glViewport(0, 0, e.width, e.height);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        float aspect = Math.max(1, e.width) / (float) Math.max(1, e.height);
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(SceneSpec.FOV_Y_DEG), aspect, 2, 3000);
        Matrix4f view = new Matrix4f().lookAt(f.eye(), f.target(), new Vector3f(0, 1, 0));

        glUseProgram(litProgram);
        setMat(litUniforms, litProgram, "uProj", proj);
        setMat(litUniforms, litProgram, "uView", view);
        setMat(litUniforms, litProgram, "uLightVP", lightVP);
        lights(litUniforms, litProgram, f);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer dice = stack.mallocFloat(SceneSpec.MAX_DICE * 4);
            int n = 0;
            for (SceneSpec.DieInstance d : f.dice()) {
                if (n++ == SceneSpec.MAX_DICE) break;
                Vector3f c = d.centre();
                dice.put(c.x).put(c.y).put(c.z).put(d.kind().inradius * 1.05f);
            }
            while (dice.hasRemaining()) dice.put(0);
            dice.flip();
            glUniform4fv(loc(litUniforms, litProgram, "uDice"), dice);
        }
        glUniform1i(loc(litUniforms, litProgram, "uDiceCount"), Math.min(f.dice().size(), SceneSpec.MAX_DICE));
        bindTex(litUniforms, litProgram, 3, shadowTex, "uShadow");
        glUniform1f(loc(litUniforms, litProgram, "uDisplace"), 0f);
        bindTex(litUniforms, litProgram, 2, feltTex, "uAlbedoTex");
        glUniform1i(loc(litUniforms, litProgram, "uKind"), 1);
        setMat(litUniforms, litProgram, "uModel", new Matrix4f());
        draw(floorMesh);
        bindTex(litUniforms, litProgram, 2, woodTex, "uAlbedoTex");
        glUniform1i(loc(litUniforms, litProgram, "uKind"), 2);
        for (GpuMesh w : wallMeshes) draw(w);

        // Pass 3: dice. Opaque first, then (C) copy the frame for the translucent ones to look through.
        glUseProgram(dieProgram);
        setMat(dieUniforms, dieProgram, "uProj", proj);
        setMat(dieUniforms, dieProgram, "uView", view);
        setMat(dieUniforms, dieProgram, "uViewProj", new Matrix4f(proj).mul(view));
        setMat(dieUniforms, dieProgram, "uLightVP", lightVP);
        lights(dieUniforms, dieProgram, f);
        glUniform1f(die("uDepth"), depth);
        glUniform1i(die("uTechnique"), tech.ordinal());
        glUniform1i(die("uSwirlBaked"), params.swirlSource == Params.SwirlSource.BAKED ? 1 : 0);
        bindTex(dieUniforms, dieProgram, 3, shadowTex, "uShadow");
        // Bind something valid to the background unit even when it is unused, so samplers never alias.
        bindTex(dieUniforms, dieProgram, 4, bgTex != 0 ? bgTex : feltTex, "uBackground");
        boolean anyTranslucent = false;
        for (SceneSpec.DieInstance d : f.dice()) {
            if (tech != Params.Technique.A && d.set().translucent()) anyTranslucent = true;
            else drawDie(d, models);
        }
        if (anyTranslucent) {
            if (tech == Params.Technique.C) {
                copyBackground(e);
                glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
                bindTex(dieUniforms, dieProgram, 4, bgTex, "uBackground");
            }
            for (SceneSpec.DieInstance d : f.dice()) if (d.set().translucent()) drawDie(d, models);
        }
        // Leave the context the way openglfx's own blit/interop passes expect to find it.
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glBindVertexArray(0);
        glUseProgram(0);
        glActiveTexture(GL_TEXTURE0);
    }

    /** Sets one die's Dice Set, face table and atlases, and draws it. */
    private void drawDie(SceneSpec.DieInstance d, List<DieModel> models) {
        int k = d.kind().ordinal();
        DieModel m = models.get(k);
        DiceSet s = d.set();
        bindTex(dieUniforms, dieProgram, 0, atlasTex.get(k)[0], "uMask");
        bindTex(dieUniforms, dieProgram, 1, atlasTex.get(k)[1], "uSlope");
        Integer swirl = swirlTex.get(s);
        glActiveTexture(GL_TEXTURE5);
        glBindTexture(GL_TEXTURE_3D, swirl == null ? 0 : swirl);
        glUniform1i(die("uSwirlTex"), 5);
        glUniform1f(die("uMmPerUv"), m.mmPerUv());
        float[] faces = faceTables.get(k);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniform4fv(die("uFaces"), stack.floats(faces));
        }
        glUniform1i(die("uFaceCount"), faces.length / 12);

        vec3(die("uBase"), s.base());
        glUniform1f(die("uRough"), s.roughness());
        glUniform1f(die("uMetal"), s.metallic());
        vec3(die("uSwirlColor"), s.swirlColor());
        glUniform1f(die("uSwirlPeriod"), swirlPeriod(s));
        glUniform1f(die("uSwirlWarp"), s.swirlWarp());
        glUniform1f(die("uSwirlBands"), s.swirlBands());
        glUniform1f(die("uSwirlAmount"), s.swirlAmount());
        glUniform1f(die("uSwirlSoft"), s.swirlSoftness());
        vec3(die("uFlakeColor"), s.flakeColor());
        glUniform1f(die("uFlakeDensity"), s.flakeDensity());
        glUniform1f(die("uFlakeSize"), s.flakeSize());
        glUniform1f(die("uFlakeMetal"), s.flakeMetallic());
        glUniform1f(die("uPearl"), s.pearl());
        glUniform1f(die("uTransmission"), s.transmission());
        vec3(die("uAbsorb"), s.absorb());
        glUniform1f(die("uIor"), s.ior());
        glUniform1f(die("uCloud"), s.cloudiness());
        vec3(die("uInk"), s.ink());
        glUniform1f(die("uInkMetal"), s.inkMetallic());
        glUniform1f(die("uInkRough"), s.inkRoughness());
        // A per-die offset anywhere in the field's period; the golden-ratio steps keep neighbours apart.
        float[] seed = new float[3];
        if (params.variation) {
            for (int i = 0; i < 3; i++) seed[i] = (float) (((d.slot() + 1) * (0.618034 + 0.17 * i)) % 1.0) * PERIOD_MM;
        }
        glUniform3f(die("uSeed"), seed[0], seed[1], seed[2]);
        setMat(dieUniforms, dieProgram, "uModel", d.model());
        draw(coarse.get(k));
    }

    /** Resolves what has been drawn so far into {@link #bgTex}, (re)allocating it to the frame size. */
    private void copyBackground(GLRenderEvent e) {
        int w = e.width, h = e.height;
        if (bgFbo == 0 || w != bgW || h != bgH) {
            if (bgFbo != 0) {
                glDeleteFramebuffers(bgFbo);
                glDeleteTextures(bgTex);
            }
            bgTex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, bgTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            bgFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, bgFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bgTex, 0);
            bgW = w;
            bgH = h;
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, e.fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bgFbo);
        // NEAREST because a multisampled read buffer allows nothing else; the resolve averages anyway.
        glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    private static void lights(Map<String, Integer> cache, int program, SceneSpec.Frame f) {
        vec3(loc(cache, program, "uCamPos"), f.eye());
        vec3(loc(cache, program, "uKeyDir"), SceneSpec.KEY_DIR);
        vec3(loc(cache, program, "uKeyColor"), SceneSpec.KEY_COLOR);
        vec3(loc(cache, program, "uFillDir"), SceneSpec.FILL_DIR);
        vec3(loc(cache, program, "uFillColor"), SceneSpec.FILL_COLOR);
        // Bias in light-NDC depth units over a 300 mm range: 0.00004 is about 0.01 mm.
        glUniform1f(loc(cache, program, "uShadowBias"), 0.00004f);
    }

    /**
     * If a capture is pending and has settled, resolves openglfx's (possibly multisampled) framebuffer
     * into a plain one, reads it back and writes the PNG off-thread.
     */
    private void maybeCapture(GLRenderEvent e) {
        CaptureRequest req = capture.get();
        if (req == null) {
            captureFrames = 0;
            return;
        }
        if (uploadedVersion != req.version() || bakedFor != scene.sets) return;
        captureFrames++;
        if (captureFrames == 10) {
            captureGpuSum = 0;
            captureGpuCount = 0;
        } else if (captureFrames > 10) {
            captureGpuSum += gpuMs;
            captureGpuCount++;
        }
        if (captureFrames < 40) return;
        if (!capture.compareAndSet(req, null)) return;
        captureFrames = 0;
        double avg = captureGpuSum / Math.max(1, captureGpuCount);
        String caption = req.caption() + String.format("%nGPU %.2f ms/frame at %dx%d (avg of %d frames)", avg, e.width, e.height, captureGpuCount);
        System.out.printf("gpu %s %.3f ms%n", req.file().getFileName(), avg);
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
                for (String line : caption.split("\n")) {
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
        glDeleteProgram(dieProgram);
        glDeleteProgram(bakeProgram);
        List<GpuMesh> all = new ArrayList<>(wallMeshes);
        all.add(floorMesh);
        all.addAll(coarse);
        for (GpuMesh m : all) if (m != null) delete(m);
        for (int[] t : atlasTex) glDeleteTextures(t);
        for (int t : swirlTex.values()) glDeleteTextures(t);
        glDeleteTextures(new int[]{feltTex, woodTex, shadowTex, bgTex});
        glDeleteFramebuffers(new int[]{shadowFbo, bgFbo});
        glDeleteVertexArrays(emptyVao);
        glDeleteQueries(queries);
    }

    private int die(String name) {
        return loc(dieUniforms, dieProgram, name);
    }

    private static void vec3(int location, Vector3f v) {
        glUniform3f(location, v.x, v.y, v.z);
    }

    private static void vec3(int location, float[] v) {
        glUniform3f(location, v[0], v[1], v[2]);
    }

    private static void bindTex(Map<String, Integer> cache, int program, int unit, int texture, String uniform) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(loc(cache, program, uniform), unit);
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

    /** Loads a shader resource, splicing in any {@code #include "file"} line (GLSL 3.3 has no includes). */
    private static String source(String name) {
        String src;
        try (InputStream in = GlRenderer.class.getResourceAsStream("shaders/" + name)) {
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        StringBuilder out = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("#include \"")) out.append(source(t.substring(10, t.length() - 1))).append('\n');
            else out.append(line).append('\n');
        }
        return out.toString();
    }

    private static int shader(int type, String name) {
        int s = glCreateShader(type);
        glShaderSource(s, source(name));
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
