package andysdice.prototype.tray;

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
 * PROTOTYPE. Draws the tray, the table and the dice with hand-written OpenGL 3.3 core shaders inside one
 * long-lived openglfx {@link GLCanvas}.
 *
 * <p>Per frame: the opaque shadow map (and, for tinted translucent shadows, a second map of the
 * translucent dice with their colour), then into our own multisampled framebuffer at the render scale:
 * the table and tray, the opaque dice, a copy of the frame so far for the translucent dice to refract
 * (ADR-0009), the translucent dice, and the escape-volume overlay. Last, a resolve (and an upscale, below
 * full render scale) into openglfx's framebuffer.
 *
 * <p>All GL calls happen on openglfx's render thread. {@link #stats()} and {@link #capture} are the only
 * methods the FX thread may call.
 */
final class GlRenderer {
    private static final int SHADOW_PX = 2048;
    private static final int MSAA = 4;
    /** Side of a baked swirl volume; it spans one {@link #PERIOD_MM} period of the field. */
    static final int SWIRL_PX = 128;
    /** Must match PERIOD_MM in die.frag. */
    static final float PERIOD_MM = 32f;

    private final SceneSpec scene;
    private final Params params;
    private final Builds builds;
    private final TrayTextures tex;
    final GLCanvas canvas;

    private volatile String glInfo = "(initialising)";
    private volatile double gpuMs;
    private volatile String error;
    volatile String bakeInfo = "";
    volatile String passInfo = "";

    private int litProgram, depthProgram, dieProgram, bakeProgram, tintProgram, lineProgram;
    private final Map<Integer, Map<String, Integer>> uniforms = new HashMap<>();
    private int greenFeltTex, navyFeltTex, leatherTex, woodTex, tableTex;
    private int shadowFbo, shadowTex, shadowTFbo, shadowTTex, shadowTintTex, rawSampler, emptyVao;
    private final int[] queries = new int[3];
    private long frameIndex;

    private TrayGeom uploadedTray;
    private GpuMesh floorMesh, wallMesh, tableMesh;
    private int lineVao, lineVbo, lineCount;

    private Builds.Built built;
    private final List<GpuMesh> coarse = new ArrayList<>();
    private final List<int[]> atlasTex = new ArrayList<>();
    private final List<float[]> faceTables = new ArrayList<>();
    private final Map<DiceSet, Integer> swirlTex = new IdentityHashMap<>();
    private List<DiceSet> bakedFor;

    private int sceneFbo, sceneColor, sceneDepth, sceneW, sceneH;
    private int midFbo, midColor;
    private int bgFbo, bgTex, bgW, bgH;

    private record GpuMesh(int vao, int[] buffers, int indexCount) {
    }

    /** A pending screenshot: where to write it and who to tell. */
    record CaptureRequest(Path file, String caption, CompletableFuture<Void> done) {
    }

    private final AtomicReference<CaptureRequest> capture = new AtomicReference<>();
    private int captureFrames;
    private double captureGpuSum;
    private int captureGpuCount;
    private int resolveFbo, resolveRbo, resolveW, resolveH;

    GlRenderer(SceneSpec scene, Params params, Builds builds, TrayTextures tex) {
        this.scene = scene;
        this.params = params;
        this.builds = builds;
        this.tex = tex;
        // No MSAA on the canvas: we multisample our own framebuffer, at the render scale.
        canvas = new GLCanvas(new GLCanvas.Builder().setExecutor(LWJGLExecutor.LWJGL_MODULE).setMSAA(0).setFps(-1.0));
        canvas.addOnInitEvent(e -> guarded(this::init));
        canvas.addOnRenderEvent(e -> guarded(() -> render(e)));
        canvas.addOnDisposeEvent(e -> guarded(this::release));
    }

    String stats() {
        if (error != null) return "GL ERROR: " + error;
        return String.format("%s   GPU %.2f ms/frame   %s", glInfo, gpuMs, passInfo);
    }

    /**
     * Asks the render thread to save a frame as a PNG once 40 frames have passed, captioned with the GPU
     * time averaged over the last 30. Completes when the file is written.
     */
    CompletableFuture<Void> capture(Path file, String caption) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        capture.set(new CaptureRequest(file, caption, done));
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
        tintProgram = program("depth.vert", "tint.frag");
        lineProgram = program("line.vert", "line.frag");
        emptyVao = glGenVertexArrays();
        greenFeltTex = surfaceTexture(tex.greenFelt);
        navyFeltTex = surfaceTexture(tex.navyFelt);
        leatherTex = surfaceTexture(tex.leather);
        woodTex = surfaceTexture(tex.wood);
        tableTex = surfaceTexture(tex.table);

        shadowTex = depthTexture();
        shadowFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTex, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("shadow FBO incomplete");

        shadowTTex = depthTexture();
        shadowTintTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, shadowTintTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SHADOW_PX, SHADOW_PX, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        clampLinear(GL_TEXTURE_2D);
        shadowTFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, shadowTFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTTex, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, shadowTintTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("tint shadow FBO incomplete");

        // PCSS's blocker search needs raw depths from the same texture the filter compares against: a
        // sampler object overrides the texture's compare mode on its own unit (core since GL 3.3).
        rawSampler = glGenSamplers();
        glSamplerParameteri(rawSampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(rawSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(rawSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(rawSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(rawSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenQueries(queries);
    }

    private static int depthTexture() {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, SHADOW_PX, SHADOW_PX, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (FloatBuffer) null);
        clampLinear(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        return t;
    }

    private static void clampLinear(int target) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    /** Swaps in a newer build of the dice: meshes, atlases and face tables. */
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
        built = b;
    }

    /** Re-uploads the tray meshes and the escape outline when a tray knob has changed. */
    private void syncTray() {
        TrayGeom g = scene.tray;
        if (g == uploadedTray) return;
        for (GpuMesh m : new GpuMesh[]{floorMesh, wallMesh, tableMesh}) if (m != null) delete(m);
        floorMesh = upload(g.floor);
        wallMesh = upload(g.walls);
        tableMesh = upload(g.table);
        if (lineVao == 0) {
            lineVao = glGenVertexArrays();
            lineVbo = glGenBuffers();
        }
        glBindVertexArray(lineVao);
        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glBufferData(GL_ARRAY_BUFFER, g.escapeLines, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glBindVertexArray(0);
        lineCount = g.escapeLines.length / 3;
        uploadedTray = g;
    }

    /** Bakes every swirl volume when the catalogue has been (re)loaded (ADR-0009: swirls are baked). */
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
            int t = glGenTextures();
            glBindTexture(GL_TEXTURE_3D, t);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, SWIRL_PX, SWIRL_PX, SWIRL_PX, 0, GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            for (int w : new int[]{GL_TEXTURE_WRAP_S, GL_TEXTURE_WRAP_T, GL_TEXTURE_WRAP_R}) glTexParameteri(GL_TEXTURE_3D, w, GL_REPEAT);
            glUniform1f(u(bakeProgram, "uSize"), SWIRL_PX);
            glUniform1f(u(bakeProgram, "uPeriod"), swirlPeriod(s));
            glUniform1f(u(bakeProgram, "uWarp"), s.swirlWarp());
            glUniform1f(u(bakeProgram, "uBands"), s.swirlBands());
            for (int z = 0; z < SWIRL_PX; z++) {
                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, t, 0, z);
                glUniform1f(u(bakeProgram, "uSlice"), z);
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }
            swirlTex.put(s, t);
        }
        glFinish();
        glDeleteFramebuffers(fbo);
        glBindVertexArray(0);
        bakedFor = sets;
        bakeInfo = String.format("baked %d swirl volumes in %d ms", swirlTex.size(), (System.nanoTime() - t0) / 1_000_000);
    }

    /** Noise cells per period: the nearest whole number to the Dice Set's scale, so the field still tiles. */
    private static float swirlPeriod(DiceSet s) {
        return Math.max(1, Math.round(PERIOD_MM / s.swirlScale()));
    }

    private void render(GLRenderEvent e) {
        syncBuild();
        syncSwirls();
        syncTray();
        if (frameIndex >= queries.length) {
            int old = queries[(int) (frameIndex % queries.length)];
            if (glGetQueryObjecti(old, GL_QUERY_RESULT_AVAILABLE) != 0) gpuMs = glGetQueryObjecti64(old, GL_QUERY_RESULT) / 1e6;
        }
        glBeginQuery(GL_TIME_ELAPSED, queries[(int) (frameIndex % queries.length)]);
        float scale = params.renderScale();
        int w = Math.max(1, Math.round(e.width * scale)), h = Math.max(1, Math.round(e.height * scale));
        ensureSceneTargets(w, h);

        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glViewport(0, 0, w, h);
        glClearColor(0.02f, 0.02f, 0.022f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        if (built != null && uploadedTray != null) drawScene(w, h, e.width / (float) Math.max(1, e.height));

        // Resolve the multisampled frame; below full scale, through a single-sampled copy, then upscale.
        glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneFbo);
        if (w == e.width && h == e.height) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, e.fbo);
            glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        } else {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, midFbo);
            glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, midFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, e.fbo);
            glBlitFramebuffer(0, 0, w, h, 0, 0, e.width, e.height, GL_COLOR_BUFFER_BIT, GL_LINEAR);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
        glEndQuery(GL_TIME_ELAPSED);
        frameIndex++;
        maybeCapture(e);
    }

    /** (Re)allocates the multisampled scene framebuffer and its single-sampled twin at w x h. */
    private void ensureSceneTargets(int w, int h) {
        if (sceneFbo != 0 && w == sceneW && h == sceneH) return;
        if (sceneFbo != 0) {
            glDeleteFramebuffers(new int[]{sceneFbo, midFbo});
            glDeleteRenderbuffers(new int[]{sceneColor, sceneDepth, midColor});
        }
        sceneFbo = glGenFramebuffers();
        sceneColor = glGenRenderbuffers();
        sceneDepth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, sceneColor);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, MSAA, GL_RGBA8, w, h);
        glBindRenderbuffer(GL_RENDERBUFFER, sceneDepth);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, MSAA, GL_DEPTH_COMPONENT24, w, h);
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, sceneColor);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, sceneDepth);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("scene FBO incomplete");
        midFbo = glGenFramebuffers();
        midColor = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, midColor);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
        glBindFramebuffer(GL_FRAMEBUFFER, midFbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, midColor);
        sceneW = w;
        sceneH = h;
    }

    /** The key light's view-projection, fitted to what the camera is looking at. */
    private record LightView(Matrix4f vp, boolean persp, float near, float far, float uvPerMm) {
    }

    private LightView lightView(SceneSpec.Light light, SceneSpec.Frame f, TrayGeom g) {
        if (light.spot()) {
            float fov = (float) Math.toRadians(light.spotOuterDeg() * 2 + 6);
            Vector3f pos = light.keyVec();
            Matrix4f vp = new Matrix4f().perspective(fov, 1, 150, 900)
                    .lookAt(pos, new Vector3f(pos).add(light.spotDir()), new Vector3f(0, 0, 1));
            return new LightView(vp, true, 150, 900, (float) (1 / (2 * Math.tan(fov / 2))));
        }
        // Fit the frustum to what the camera sees, so close-ups get fine shadow texels, but never larger
        // than the tray plus its walls' shadows.
        float extent = Math.clamp(f.dist() * 0.5f, 50f, g.radius() + TrayGeom.WALL_T + 60f);
        Vector3f target = new Vector3f(f.target()).mul(1, 0, 1);
        Matrix4f vp = new Matrix4f().ortho(-extent, extent, -extent, extent, 100, 1100)
                .lookAt(light.keyVec().mul(600).add(target), target, new Vector3f(0, 1, 0));
        return new LightView(vp, false, 100, 1100, 1 / (2 * extent));
    }

    private void drawScene(int w, int h, float aspect) {
        List<DieModel> models = built.models();
        SceneSpec.Frame f = scene.frame(params, aspect);
        TrayGeom g = uploadedTray;
        SceneSpec.Light light = SceneSpec.light(params.rig);
        LightView lv = lightView(light, f, g);
        boolean tinted = params.transShadow == Params.TransShadow.TINTED;

        // Pass 1: shadow maps from the key light. Walls cast; the floor and table only receive.
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.1f, 2f);
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
        glViewport(0, 0, SHADOW_PX, SHADOW_PX);
        glClear(GL_DEPTH_BUFFER_BIT);
        glUseProgram(depthProgram);
        setMat(depthProgram, "uLightVP", lv.vp());
        glUniform1f(u(depthProgram, "uDisplace"), 0f);
        for (SceneSpec.DieInstance d : f.dice()) {
            if (tinted && d.set().translucent()) continue;
            setMat(depthProgram, "uModel", d.model());
            draw(coarse.get(d.kind().ordinal()));
        }
        setMat(depthProgram, "uModel", new Matrix4f());
        draw(wallMesh);

        // The tinted map is cleared to "no translucent caster" (depth 1, white) even when unused.
        glBindFramebuffer(GL_FRAMEBUFFER, shadowTFbo);
        glClearColor(1, 1, 1, 1);
        glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
        if (tinted) {
            glUseProgram(tintProgram);
            setMat(tintProgram, "uLightVP", lv.vp());
            glUniform1f(u(tintProgram, "uDisplace"), 0f);
            for (SceneSpec.DieInstance d : f.dice()) {
                if (!d.set().translucent()) continue;
                float[] t = shadowTint(d.set());
                glUniform3f(u(tintProgram, "uTint"), t[0], t[1], t[2]);
                setMat(tintProgram, "uModel", d.model());
                draw(coarse.get(d.kind().ordinal()));
            }
        }
        glDisable(GL_POLYGON_OFFSET_FILL);

        // Pass 2: table, tray floor and walls.
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glViewport(0, 0, w, h);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(SceneSpec.FOV_Y_DEG), aspect, 3, 4000);
        Matrix4f view = new Matrix4f().lookAt(f.eye(), f.target(), new Vector3f(0, 1, 0));

        glUseProgram(litProgram);
        setMat(litProgram, "uProj", proj);
        setMat(litProgram, "uView", view);
        setMat(litProgram, "uModel", new Matrix4f());
        commonLighting(litProgram, f, light, lv, tinted);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer dice = stack.mallocFloat(SceneSpec.MAX_DICE * 4);
            int n = 0;
            for (SceneSpec.DieInstance d : f.dice()) {
                if (n++ == SceneSpec.MAX_DICE) break;
                Vector3f c = d.centre();
                dice.put(c.x).put(c.y).put(c.z).put(d.radius() * 1.05f);
            }
            while (dice.hasRemaining()) dice.put(0);
            dice.flip();
            glUniform4fv(u(litProgram, "uDice"), dice);
        }
        glUniform1i(u(litProgram, "uDiceCount"), Math.min(f.dice().size(), SceneSpec.MAX_DICE));
        glUniform1i(u(litProgram, "uContactAo"), params.contactAo ? 1 : 0);

        surface(tableTex, 0.5f, 0f, 0.02f);
        draw(tableMesh);
        switch (params.surface) {
            case GREEN_FELT -> surface(greenFeltTex, params.surface.roughness, 1.6f, 0.12f);
            case NAVY_FELT -> surface(navyFeltTex, params.surface.roughness, 1.6f, 0.12f);
            case LEATHER -> surface(leatherTex, params.surface.roughness, 0.3f, 0.07f);
            case WOOD -> surface(woodTex, params.surface.roughness, 0f, 0.03f);
        }
        draw(floorMesh);
        if (g.wallMaterial == Params.WallMaterial.WOOD) surface(woodTex, 0.42f, 0f, 0.02f);
        else surface(leatherTex, 0.55f, 0.3f, 0.07f);
        draw(wallMesh);

        // Pass 3: dice. Opaque first, then a copy of the frame for the translucent ones to look through.
        glUseProgram(dieProgram);
        setMat(dieProgram, "uProj", proj);
        setMat(dieProgram, "uView", view);
        setMat(dieProgram, "uViewProj", new Matrix4f(proj).mul(view));
        commonLighting(dieProgram, f, light, lv, tinted);
        glUniform1f(u(dieProgram, "uDepth"), Params.VISIBLE_DEPTH);
        glUniform1i(u(dieProgram, "uTechnique"), 2);
        glUniform1i(u(dieProgram, "uSwirlBaked"), 1);
        // Bind something valid to the background unit even when unused, so samplers never alias.
        bindTex(dieProgram, 4, bgTex != 0 ? bgTex : tableTex, "uBackground");
        boolean anyTranslucent = false;
        for (SceneSpec.DieInstance d : f.dice()) {
            if (d.set().translucent()) anyTranslucent = true;
            else drawDie(d, models);
        }
        if (anyTranslucent) {
            copyBackground(w, h);
            glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
            bindTex(dieProgram, 4, bgTex, "uBackground");
            for (SceneSpec.DieInstance d : f.dice()) if (d.set().translucent()) drawDie(d, models);
        }

        if (params.showEscape) {
            glDisable(GL_DEPTH_TEST);
            glUseProgram(lineProgram);
            setMat(lineProgram, "uViewProj", new Matrix4f(proj).mul(view));
            boolean glass = g.escape == Params.Escape.GLASS;
            glUniform3f(u(lineProgram, "uColor"), glass ? 0.3f : 1f, glass ? 0.9f : 0.55f, glass ? 1f : 0.15f);
            glBindVertexArray(lineVao);
            glDrawArrays(GL_LINES, 0, lineCount);
        }
        passInfo = String.format("scene %dx%d (scale %.2f, MSAA %dx)", w, h, params.renderScale(), MSAA);

        // Leave the context the way openglfx's own passes expect to find it.
        glBindSampler(6, 0);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glBindVertexArray(0);
        glUseProgram(0);
        glActiveTexture(GL_TEXTURE0);
    }

    /** The colour light keeps through a translucent Dice Set's body: denser, cloudier sets let less by. */
    private static float[] shadowTint(DiceSet s) {
        float t = s.transmission() * (1 - 0.6f * s.cloudiness());
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = Math.clamp(t * (float) Math.pow(s.absorb()[i], 0.8), 0.04f, 1f);
        return out;
    }

    private void surface(int texture, float rough, float sheen, float bumpMm) {
        bindTex(litProgram, 2, texture, "uAlbedoTex");
        glUniform3f(u(litProgram, "uTint"), 1, 1, 1);
        glUniform1f(u(litProgram, "uRough"), rough);
        glUniform1f(u(litProgram, "uSheen"), sheen);
        glUniform1f(u(litProgram, "uBumpMm"), bumpMm);
    }

    /** Light rig, environment and shadow uniforms, the same for the tray and the dice. */
    private void commonLighting(int program, SceneSpec.Frame f, SceneSpec.Light light, LightView lv, boolean tinted) {
        vec3(u(program, "uCamPos"), f.eye());
        float[] kp = light.keyPos();
        glUniform4f(u(program, "uKeyPos"), kp[0], kp[1], kp[2], kp[3]);
        vec3(u(program, "uKeyColor"), light.keyColor());
        vec3(u(program, "uSpotDir"), light.spotDir());
        glUniform2f(u(program, "uSpotCos"), (float) Math.cos(Math.toRadians(light.spotOuterDeg())), (float) Math.cos(Math.toRadians(light.spotInnerDeg())));
        glUniform1f(u(program, "uKeyRef"), light.keyVec().length());
        vec3(u(program, "uFillDir"), light.fillDir());
        vec3(u(program, "uFillColor"), light.fillColor());
        glUniform1i(u(program, "uEnv"), light.env());
        setMat(program, "uLightVP", lv.vp());
        bindTex(program, 3, shadowTex, "uShadow");
        bindTex(program, 6, shadowTex, "uShadowRaw");
        glBindSampler(6, rawSampler);
        bindTex(program, 7, shadowTTex, "uShadowT");
        bindTex(program, 8, shadowTintTex, "uShadowTint");
        glUniform1i(u(program, "uTintedShadows"), tinted ? 1 : 0);
        glUniform1i(u(program, "uShadowMode"), params.shadows.ordinal());
        glUniform1f(u(program, "uShadowBiasMm"), lv.persp() ? 0.25f : 0.05f);
        glUniform1i(u(program, "uLightPersp"), lv.persp() ? 1 : 0);
        glUniform2f(u(program, "uLightNearFar"), lv.near(), lv.far());
        glUniform1f(u(program, "uLightSize"), light.size());
        glUniform1f(u(program, "uUvPerMm"), lv.uvPerMm());
    }

    /** Sets one die's Dice Set, face table and atlases, and draws it. */
    private void drawDie(SceneSpec.DieInstance d, List<DieModel> models) {
        int k = d.kind().ordinal();
        DieModel m = models.get(k);
        DiceSet s = d.set();
        bindTex(dieProgram, 0, atlasTex.get(k)[0], "uMask");
        bindTex(dieProgram, 1, atlasTex.get(k)[1], "uSlope");
        Integer swirl = swirlTex.get(s);
        glActiveTexture(GL_TEXTURE5);
        glBindTexture(GL_TEXTURE_3D, swirl == null ? 0 : swirl);
        glUniform1i(u(dieProgram, "uSwirlTex"), 5);
        glUniform1f(u(dieProgram, "uMmPerUv"), m.mmPerUv());
        float[] faces = faceTables.get(k);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniform4fv(u(dieProgram, "uFaces"), stack.floats(faces));
        }
        glUniform1i(u(dieProgram, "uFaceCount"), faces.length / 12);
        vec3(u(dieProgram, "uBase"), s.base());
        glUniform1f(u(dieProgram, "uRough"), s.roughness());
        glUniform1f(u(dieProgram, "uMetal"), s.metallic());
        vec3(u(dieProgram, "uSwirlColor"), s.swirlColor());
        glUniform1f(u(dieProgram, "uSwirlPeriod"), swirlPeriod(s));
        glUniform1f(u(dieProgram, "uSwirlWarp"), s.swirlWarp());
        glUniform1f(u(dieProgram, "uSwirlBands"), s.swirlBands());
        glUniform1f(u(dieProgram, "uSwirlAmount"), s.swirlAmount());
        glUniform1f(u(dieProgram, "uSwirlSoft"), s.swirlSoftness());
        vec3(u(dieProgram, "uFlakeColor"), s.flakeColor());
        glUniform1f(u(dieProgram, "uFlakeDensity"), s.flakeDensity());
        glUniform1f(u(dieProgram, "uFlakeSize"), s.flakeSize());
        glUniform1f(u(dieProgram, "uFlakeMetal"), s.flakeMetallic());
        glUniform1f(u(dieProgram, "uPearl"), s.pearl());
        glUniform1f(u(dieProgram, "uTransmission"), s.transmission());
        vec3(u(dieProgram, "uAbsorb"), s.absorb());
        glUniform1f(u(dieProgram, "uIor"), s.ior());
        glUniform1f(u(dieProgram, "uCloud"), s.cloudiness());
        vec3(u(dieProgram, "uInk"), s.ink());
        glUniform1f(u(dieProgram, "uInkMetal"), s.inkMetallic());
        glUniform1f(u(dieProgram, "uInkRough"), s.inkRoughness());
        glUniform1f(u(dieProgram, "uGlow"), d.glow());
        // A per-die offset anywhere in the field's period; the golden-ratio steps keep neighbours apart.
        float[] seed = new float[3];
        for (int i = 0; i < 3; i++) seed[i] = (float) (((d.slot() + 1) * (0.618034 + 0.17 * i)) % 1.0) * PERIOD_MM;
        glUniform3f(u(dieProgram, "uSeed"), seed[0], seed[1], seed[2]);
        setMat(dieProgram, "uModel", d.model());
        draw(coarse.get(k));
    }

    /** Resolves what has been drawn so far into {@link #bgTex}, (re)allocating it to the frame size. */
    private void copyBackground(int w, int h) {
        if (bgFbo == 0 || w != bgW || h != bgH) {
            if (bgFbo != 0) {
                glDeleteFramebuffers(bgFbo);
                glDeleteTextures(bgTex);
            }
            bgTex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, bgTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            clampLinear(GL_TEXTURE_2D);
            bgFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, bgFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bgTex, 0);
            bgW = w;
            bgH = h;
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bgFbo);
        // NEAREST because a multisampled read buffer allows nothing else; the resolve averages anyway.
        glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    /**
     * If a capture is pending and has settled, copies openglfx's framebuffer into a plain one, reads it
     * back and writes the PNG off-thread.
     */
    private void maybeCapture(GLRenderEvent e) {
        CaptureRequest req = capture.get();
        if (req == null) {
            captureFrames = 0;
            return;
        }
        if (bakedFor != scene.sets || built == null) return;
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
        String caption = req.caption() + String.format("%nGPU %.2f ms/frame at %dx%d, render scale %.2f (avg of %d frames)",
                avg, e.width, e.height, params.renderScale(), captureGpuCount);
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
        for (int p : new int[]{litProgram, depthProgram, dieProgram, bakeProgram, tintProgram, lineProgram}) glDeleteProgram(p);
        List<GpuMesh> all = new ArrayList<>(coarse);
        for (GpuMesh m : new GpuMesh[]{floorMesh, wallMesh, tableMesh}) if (m != null) all.add(m);
        for (GpuMesh m : all) delete(m);
        for (int[] t : atlasTex) glDeleteTextures(t);
        for (int t : swirlTex.values()) glDeleteTextures(t);
        glDeleteTextures(new int[]{greenFeltTex, navyFeltTex, leatherTex, woodTex, tableTex, shadowTex, shadowTTex, shadowTintTex, bgTex});
        glDeleteFramebuffers(new int[]{shadowFbo, shadowTFbo, bgFbo, sceneFbo, midFbo});
        glDeleteRenderbuffers(new int[]{sceneColor, sceneDepth, midColor});
        glDeleteSamplers(rawSampler);
        glDeleteVertexArrays(new int[]{emptyVao, lineVao});
        glDeleteBuffers(lineVbo);
        glDeleteQueries(queries);
    }

    private int u(int program, String name) {
        return uniforms.computeIfAbsent(program, p -> new HashMap<>()).computeIfAbsent(name, n -> glGetUniformLocation(program, n));
    }

    private static void vec3(int location, Vector3f v) {
        glUniform3f(location, v.x, v.y, v.z);
    }

    private static void vec3(int location, float[] v) {
        glUniform3f(location, v[0], v[1], v[2]);
    }

    private void bindTex(int program, int unit, int texture, String uniform) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(u(program, uniform), unit);
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

    private static void mipmapped(int wrap) {
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        var caps = GL.getCapabilities();
        if (caps.OpenGL46 || caps.GL_EXT_texture_filter_anisotropic || caps.GL_ARB_texture_filter_anisotropic) {
            glTexParameterf(GL_TEXTURE_2D, 0x84FE /* TEXTURE_MAX_ANISOTROPY */, 8f);
        }
    }

    /** Uploads a tiling {@link TrayTextures#PX}^2 surface (packed ARGB, top row first), repeating. */
    private static int surfaceTexture(int[] argb) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        // An int ARGB is exactly what GL_BGRA + UNSIGNED_INT_8_8_8_8_REV describes: no swizzle needed.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, TrayTextures.PX, TrayTextures.PX, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, argb);
        mipmapped(GL_REPEAT);
        return t;
    }

    /** Uploads interleaved 8-bit RG (top row first; UV v = 0 is the top of the image, as in the bake-off). */
    private static int rgTexture(int size, byte[] rg) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        ByteBuffer buf = BufferUtils.createByteBuffer(rg.length).put(rg).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, size, size, 0, GL_RG, GL_UNSIGNED_BYTE, buf);
        mipmapped(GL_CLAMP_TO_EDGE);
        return t;
    }

    /** Uploads interleaved float RG as half floats (signed slopes). */
    private static int rgFloatTexture(int size, float[] rg) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        FloatBuffer buf = BufferUtils.createFloatBuffer(rg.length).put(rg).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16F, size, size, 0, GL_RG, GL_FLOAT, buf);
        mipmapped(GL_CLAMP_TO_EDGE);
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

    private void setMat(int program, String name, Matrix4f m) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(u(program, name), false, m.get(stack.mallocFloat(16)));
        }
    }
}
