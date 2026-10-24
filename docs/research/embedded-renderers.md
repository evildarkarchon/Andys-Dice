# Research: 3D renderers that can be embedded in a JavaFX 27 app

Ticket: [#3 Which 3D renderers can be embedded in a JavaFX 27 app?](https://github.com/evildarkarchon/Andys-Dice/issues/3)
(part of map [#1](https://github.com/evildarkarchon/Andys-Dice/issues/1)). Researched 2026-10-23.

**Legend.** **[V]** means verified against a primary source, which is linked. **[I]** means
inference or arithmetic, which needs confirming in a prototype.

## TL;DR

- **[V]** JavaFX has no supported way to share a GPU surface with another renderer. JDK-8091324,
  "interoperate with 3rd party (native) OpenGL", has been open since 2014. JDK-8375663, "embedding native
  OS-level rendering surfaces", has been open at P5 since January 2026. The JavaFX lead's
  recommended route is `PixelBuffer`, which copies frames through the CPU.
- **[V]** That leaves two embedding styles:
  1. **CPU copy**: render offscreen, read the pixels back into a `PixelBuffer`, and show them in an
     `ImageView`. This uses public API only.
  2. **GPU texture sharing**: use JavaFX's internal Prism classes, which is what openglfx does. This
     needs no CPU copy, but it depends on unsupported internals.
- **Recommended "strongest embedded candidate" for the side-by-side prototype against JavaFX 3D:**
  **openglfx 4.2.x as the bridge, with LWJGL 3.4 OpenGL (3.3 to 4.x core) rendering into it.** It is
  maintained, it uses zero-copy interop on the default Windows `d3d` pipeline when the driver allows,
  and it falls back automatically to a readback ("Blit") path on every platform. It also has built-in
  MSAA and is a JPMS module. To reach PBR, IBL and shadows quickly, the prototype can run
  **libGDX + gdx-gltf** on top of it through `openglfx-libgdx`.
- **Fallback if openglfx breaks on JavaFX 27:** plain LWJGL offscreen rendering, an asynchronous PBO
  readback, and `PixelBuffer`. This uses public JavaFX API only and survives pipeline changes such as
  Metal on macOS or a future D3D12 pipeline. It costs about 7.9 MiB per direction per frame at 1080p.
- **Not recommended:** a jMonkeyEngine embed (no maintained bridge exists, though its engine
  features are the best of the group); JOGL `NewtCanvasJFX` (a heavyweight native child window with
  layout and z-order bugs); Filament (no published desktop Java bindings); DriftFX (archived).

## Context that changed recently (affects every candidate)

| Fact | Source |
|---|---|
| **[V]** JavaFX 27 is GA (27.0.1 on jdk.java.net). It is "designed for JDK 27, and known to work with JDK 25 and later". Builds are published for Linux x64, macOS AArch64 and Windows x64. | [jdk.java.net/javafx27](https://jdk.java.net/javafx27/) |
| **[V]** "JavaFX 27 requires JDK 25 or later." | [release-notes-27.md](https://github.com/openjdk/jfx/blob/master/doc-files/release-notes-27.md) |
| **[V]** "Metal is now the default rendering pipeline on macOS". You can revert with `-Dprism.order=es2`. | same; [Inside Java heads-up, 2026-03-03](https://inside.java/2026/03/03/quality-heads-up/) |
| **[V]** A Direct3D 12 pipeline exists only as EA builds from a sandbox branch (newest listed: `26-direct3d12+2`, 2025-11-10). The first EA announcement said its "3D pipeline" was much slower than D3D. | [jdk.java.net/javafxdirect3d12](https://jdk.java.net/javafxdirect3d12), [openjfx-dev May 2025](https://mail.openjdk.org/pipermail/openjfx-dev/2025-May/054560.html) |
| **[V]** JDK 27 went GA on 2026-09-15. None of its JEPs change the JNI or `sun.misc.Unsafe` defaults. | [openjdk.org/projects/jdk/27](https://openjdk.org/projects/jdk/27/) |
| **[V]** JEP 498 plans for `--sun-misc-unsafe-memory-access` to default to `deny` "in phase 3 (JDK 26 or later)". **[I]** I could not confirm whether JDK 26 or 27 actually made that switch. Treat Unsafe-dependent code as at risk. | [JEP 498](https://openjdk.org/jeps/498) |
| **[V]** Kevin Rushforth, JavaFX lead, 2020: "Any solution that assumes OpenGL is the native rendering library is not something that we would consider including in JavaFX". He recommended "the new NIO Buffer-based PixelBuffer API added in JavaFX 13" instead. | [openjfx-dev 2020-05](https://mail.openjdk.org/pipermail/openjfx-dev/2020-May/026203.html) |
| **[V]** JDK-8091324 (open since 2014, unassigned) and JDK-8375663 (created 2026-01-17, P5, assigned to Kevin Rushforth, no activity) are both open with fix version "tbd". | [JDK-8091324](https://bugs.openjdk.org/browse/JDK-8091324), [JDK-8375663](https://bugs.openjdk.org/browse/JDK-8375663) |

**[I]** Do not plan on an official JavaFX interop API during v1. Anything faster than a CPU copy
depends on Prism internals.

## The baseline being compared against: JavaFX 3D

- **[V]** `PhongMaterial` has `diffuseColor`, `specularColor`, `specularPower`, `diffuseMap`,
  `specularMap`, `bumpMap` and `selfIlluminationMap`. Diffuse alpha controls transparency. The javadoc
  says it "is not suitable for surfaces that act like mirrors and reflect their environment". There
  are no environment maps, shadows, PBR or custom shaders.
  ([PhongMaterial javadoc](https://openjfx.io/javadoc/25/javafx.graphics/javafx/scene/paint/PhongMaterial.html))
- **[V]** Nothing in the JavaFX 27 release notes touches 3D.
  ([release-notes-27.md](https://github.com/openjdk/jfx/blob/master/doc-files/release-notes-27.md))

## How frames reach the scene

### Path A: `PixelBuffer` (public API, CPU copy)

- **[V]** `PixelBuffer` (since 13) wraps an application-supplied `IntBuffer` (`INT_ARGB_PRE`) or
  `ByteBuffer` (`BYTE_BGRA_PRE`). A `WritableImage` "can use this PixelBuffer directly without copying
  the pixel data", and one buffer can back several images. `updateBuffer(callback)` "must be called on
  the JavaFX Application Thread". The callback returns a dirty `Rectangle2D`, or `null` for the whole
  buffer.
  ([PixelBuffer javadoc, JavaFX 27](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/image/PixelBuffer.html))
- **[I]** A frame takes this route: the GPU renders into an FBO, `glReadPixels` reads into a
  pixel-pack buffer (PBO, asynchronous, double- or triple-buffered), the data lands in the direct
  `ByteBuffer` behind the `PixelBuffer`, `updateBuffer` runs on the FX thread, and Prism uploads the
  buffer to its own D3D or Metal texture. Reading as `GL_BGRA` with `GL_UNSIGNED_INT_8_8_8_8_REV`
  matches `BYTE_BGRA_PRE` and avoids swizzling. The image is upside down; flip it in the final blit
  or with `ImageView.setScaleY(-1)`.

**Per-frame copy cost [I], from arithmetic (to be measured in the prototype):**

| Physical size | Bytes per frame (4 B/px) | At 60 fps, per direction | Total (readback + upload) |
|---|---|---|---|
| 1280x720 | 3.5 MiB | ~221 MB/s | ~0.44 GB/s |
| 1920x1080 | 7.9 MiB (8,294,400 B) | ~498 MB/s | ~1.0 GB/s |
| 2880x1620 (1080p logical at 150% DPI) | 17.8 MiB | ~1.12 GB/s | ~2.2 GB/s |
| 3840x2160 | 31.6 MiB | ~1.99 GB/s | ~4.0 GB/s |

- **[I]** Integrated GPUs share system RAM, so these "transfers" are memory copies inside DRAM.
  Dual-channel DDR4/DDR5 gives tens of GB/s, so 1 to 2 GB/s is affordable. Bandwidth is not the real
  risk. The risks are:
  - pipeline stalls from synchronous `glReadPixels` (avoid them with PBOs and fences);
  - time spent on the FX thread for `updateBuffer` and the Prism upload;
  - about one frame of extra latency.
- **[I]** The tray viewport does not have to fill the window. Rendering only the tray region, or
  rendering it at a lower resolution and letting JavaFX scale it, cuts the cost proportionally.

### Path B: GPU texture sharing via Prism internals (openglfx)

- **[V]** openglfx chooses an interop type at runtime from the OS, the active Prism pipeline and the
  GL extensions it finds
  ([`GLInteropType.kt`](https://github.com/husker-dev/openglfx/blob/master/modules/core/kotlin/com/huskerdev/openglfx/internal/GLInteropType.kt)):
  - **Windows, `d3d` pipeline (the default):** `ExternalObjectsD3D` if `GL_EXT_memory_object(_win32)`
    is present and works; otherwise `WGLDXInterop` (`WGL_NV_DX_interop`) if present; otherwise `Blit`.
  - **Windows, `es2` pipeline:** `ExternalObjectsESWin`.
  - **Linux, `es2` pipeline:** `ExternalObjectsESLinux` (OpenGL to Vulkan to OpenGL, using
    `EXT_external_objects_fd`), otherwise `Blit`.
  - **macOS:** `IOSurface` only when the pipeline is `es2`, otherwise `Blit`.
- **[V]** The maintainer's guidance (issue #114, Sep 2025): "For Windows and Linux the best interop type
  is externalObjects. For MacOS - IOSurface. WGLDXInterop and Blit are used as fallback". `Blit`
  "uses 'downloading' of each frame to RAM, and 'sending' it back to the GPU". He also said that NV_DX
  is an NVIDIA extension that other vendors "may not implement it, or do it with problems (hello
  AMD)". One user measured WGLDXInterop and Blit as faster than ExternalObjectsD3D on their machine,
  and also saw tearing with NV_DX on large, fast canvases.
  ([husker-dev/openglfx#114](https://github.com/husker-dev/openglfx/issues/114))
- **[I]** On macOS under JavaFX 27, the default pipeline is Metal, not `es2`. openglfx will therefore
  fall back to `Blit` unless the app passes `-Dprism.order=es2`. That is acceptable, because macOS is
  "keep possible" rather than primary.
- **[I]** I could not verify from public data (gpuinfo.org renders client-side) whether Intel and AMD
  Windows iGPU drivers expose `GL_EXT_memory_object_win32`. The prototype should log which
  `GLInteropType` it selects on the target iGPU. Blit is the guaranteed floor.

## Candidates

### 1. openglfx + LWJGL 3.4 (OpenGL). Recommended

| Aspect | Finding |
|---|---|
| What | **[V]** "OpenGL canvas for JavaFX": a `GLCanvas` node with executors for LWJGL, LWJGL2, JOGL and libGDX. Apache-2.0. Maven `com.huskerdev:openglfx-lwjgl:4.2.3`. ([repo](https://github.com/husker-dev/openglfx)) |
| Frame path | Path B, or Path A-equivalent `Blit` as the fallback (see above). |
| Features | **[V]** MSAA at creation ("For maximum possible MSAA level, specify -1"), `flipY`, an `fps` property, and JOGL-like init/render/reshape/dispose events. PBR, shadows, environment maps and transparency are whatever you write in GL 3.3 to 4.x, or whatever libGDX + gdx-gltf provides (see 1a). ([README](https://github.com/husker-dev/openglfx)) |
| Maintenance | **[V]** Releases: 4.2.0 to 4.2.2 in May 2025; 4.2.3 on 2026-01-10 ("Fixed crash on integrated GPU on Linux", #108). Last push 2026-01-10, 150 stars, effectively one maintainer. ([releases API](https://api.github.com/repos/husker-dev/openglfx/releases)) |
| JDK/JavaFX 27 compatibility | **[V]** Built against **JavaFX 17.0.2, LWJGL 3.3.2, Kotlin 2.1** ([libs.versions.toml](https://github.com/husker-dev/openglfx/blob/master/gradle/libs.versions.toml)). It uses internal `com.sun.prism.*` classes (`GraphicsPipeline`, `Texture`, `ResourceFactory`) and a custom `NGNode`. By default it forces `--add-exports` for seven `javafx.graphics` packages at runtime, using `sun.misc.Unsafe.putBooleanVolatile` and `objectFieldOffset` ([GLFXUtils.kt](https://github.com/husker-dev/openglfx/blob/master/modules/core/kotlin/com/huskerdev/openglfx/internal/GLFXUtils.kt)). Setting `-Dopenglfx.disable.exports=true` and passing the `--add-exports` flags yourself avoids the Unsafe trick (README). **[I]** I found no evidence that it has been tested on JavaFX 26/27 or LWJGL 3.4. Prism internals can change between feature releases, and a future D3D12 default would invalidate the D3D9-based interop paths. This is the main risk to check in the prototype. |
| Natives | **[V]** It ships its own native library per OS, loaded from resources. Issue #99 shows the Linux build needed a newer `GLIBCXX`; #105 lowered the build baseline to Ubuntu 22.04. |
| Physics | None bundled. **[V]** Separate options are maintained: [jolt-jni](https://github.com/stephengold/jolt-jni) (MIT; 6.2.0 released 2026-10-14) and [Libbulletjme](https://github.com/stephengold/Libbulletjme) (23.2.0 released 2026-10-13). |
| iGPU feasibility | **[I]** Good. Plain GL 3.3 to 4.x is the most widely supported API on OpenGL 4.x iGPUs. openglfx specifically fixed an iGPU crash on Linux in 4.2.3. |

**1a. Rendering layer option: libGDX + gdx-gltf via `openglfx-libgdx`.**
- **[V]** `openglfx-libgdx` exists (4.2.2). [libGDX](https://github.com/libgdx/libgdx) 1.14.2 was
  released 2026-06-05.
- **[V]** [gdx-gltf](https://github.com/mgsx-dev/gdx-gltf) 2.3.0 was released 2026-02-05. It provides
  "Physic Based Rendering (PBR) shaders", "metallic/roughness, Image based lighting (IBL)", shadows,
  skybox and `KHR_materials_transmission`. It needs desktop GL 3.0+ for `textureLod`.
- **[I]** This is the fastest way to get PBR, IBL and shadows on screen for a realism comparison
  without writing shaders. Transmission is relevant to the translucent-dice stretch goal.
- **[I]** Risks to check:
  - libGDX's JPMS/`jlink` story, which was not verified;
  - the fact that openglfx-libgdx lags one patch version behind the core (4.2.2 vs 4.2.3).

### 2. Plain LWJGL offscreen + `PixelBuffer`. Robust fallback

| Aspect | Finding |
|---|---|
| Frame path | Path A. Use a hidden GLFW window or EGL/pbuffer context for GL, or headless Vulkan (both bindings ship in LWJGL); render to an FBO or image, then read back. **[I]** |
| Features | Anything: PBR, shadow maps, IBL cube maps, MSAA (multisample FBO, then resolve), sorted or OIT transparency. You write all of it. **[I]** LWJGL also binds **bgfx** (a D3D11/GL/Vulkan/Metal abstraction), **shaderc**/**spvc**, **assimp**, **meshoptimizer** and **ktx**. ([modules list](https://github.com/LWJGL/lwjgl3/tree/master/modules/lwjgl)) |
| Maintenance | **[V]** Very active: 3.4.0 (2026-01-18), 3.4.1 (2026-02-03), 3.4.2 (2026-07-13), 3.4.3 (2026-08-23). BSD-3. ([releases API](https://api.github.com/repos/LWJGL/lwjgl3/releases)) |
| JDK 27 compatibility | **[V]** "As of version 3.4.0 and JDK 25, LWJGL supports the FFM API and is fully functional when running with `--sun-misc-unsafe-memory-access=deny`" ([README](https://github.com/LWJGL/lwjgl3/blob/master/README.md)). The source tree has a `core27` module with JDK-27-specific memory backends (`MemoryBackendFFM27`, `MemoryBackendUnsafe27`), added 2026-05-15 as "configurable memory access backends". The 3.4.2 notes say the MemoryUtil backend is runtime-configurable and defaults to FFM on JDK 25+. It is distributed as modules (per-binding jars plus `natives-<platform>` jars) with `module-info`. Supported platforms include Windows x64/arm64, Linux x64/arm64 and macOS x64/arm64. |
| Physics | None. **[V]** LWJGL 3.4.0 removed several bindings, and none of the current modules is a physics engine. Pair it with jolt-jni or Libbulletjme. |
| iGPU feasibility | **[I]** Good. The copy cost is the only extra versus openglfx, and openglfx's `Blit` mode is this same path. |
| Why rank it second | It uses only public JavaFX API, so Metal, D3D12 and Prism refactors cannot break it. It does pay the CPU copy on every platform. |

### 3. jMonkeyEngine 3.9 rendered offscreen into a `PixelBuffer`. Feature-rich, no bridge

| Aspect | Finding |
|---|---|
| Features | **[V]** PBR (`PBRLighting.j3md`; 3.9 adds the KHR PBR neutral tonemap), shadow renderers and filters (for example `PointLightShadowRenderer` and `AbstractShadowFilter`, both in the 3.9 notes), Transparent/Translucent render buckets with back-to-front sorting, and a filter post-processor. **[I]** It also has light probes for IBL and MSAA through `AppSettings` samples; I did not re-check these in the docs for this note. ([v3.9.0 notes](https://github.com/jMonkeyEngine/jmonkeyengine/releases/tag/v3.9.0-stable), [Light and Shadow](https://wiki.jmonkeyengine.org/docs/3.8/core/light/light_and_shadow.html), [Render Buckets](https://wiki.jmonkeyengine.org/docs/3.8/core/renderer/jme3_renderbuckets.html)) |
| Physics | **[V]** It integrates Bullet through [Minie](https://github.com/stephengold/Minie) (9.0.3, 2025-12-20), maintained separately by the same author as jolt-jni and Libbulletjme. |
| Maintenance | **[V]** v3.9.0-stable was released 2026-01-20. The 3.10 line is in beta (beta2, 2026-07-21) and is changing desktop internals: "Replace desktop backend with SDL3 GL and ANGLE-GLES", "build only on java 25", LWJGL 3.4.1. ([alpha5](https://github.com/jMonkeyEngine/jmonkeyengine/releases/tag/v3.10.0-alpha5), [beta2](https://github.com/jMonkeyEngine/jmonkeyengine/releases/tag/v3.10.0-beta2)) |
| JavaFX embedding | **[V]** There is no maintained bridge. jayfella's `jfx-11-jme-embedded` (2020) rendered to an `ImageView` ([forum post](https://hub.jmonkeyengine.org/t/embed-jme-into-javafx/43387)), but its GitHub repo no longer resolves, and forum members describe the author as inactive. An earlier community bridge drew complaints that the frame-buffer copy "hogs CPU" and stutters ([forum](https://hub.jmonkeyengine.org/t/sceneprocessorcopytoimageview-performance-problem-in-jme-inside-jfx-imageview-solution-hogs-cpu-framerate-unstable-and-stutter/37198)). **[I]** A custom `SceneProcessor` would read the jME framebuffer into a `PixelBuffer` (Path A). jME expects to own its context and main loop, so lifecycle, threading and input glue are on us, and the 3.10 backend change makes that glue a moving target. |
| iGPU feasibility | **[I]** Good. jME targets GL 3.x-class hardware, and ANGLE (GLES over D3D11) in 3.10 may even improve Windows iGPU robustness. |

### 4. JOGL `NewtCanvasJFX`. Not recommended

- **[V]** JogAmp 2.4.0 added "JavaFX Support for NEWT utilizing native Window parenting via
  NewtCanvasJFX" (bug 1364, fixed in 2019). This makes it a heavyweight native child window, not a
  scene-graph texture.
  ([JogAmp bug 1364](https://jogamp.org/bugzilla/show_bug.cgi?id=1364))
- **[V]** A forum thread (JDK 23, JavaFX 23, JogAmp 2.5.0) reports two problems:
  - the canvas ignores its layout offset inside `VBox` and `BorderPane`;
  - "the z-order of the StackPane also seems to be ignored". JavaFX nodes cannot be drawn over it.

  The JogAmp admin replied "I rarely use OpenJFX". Bug 1504 tracks the layout problem.
  ([forum](https://forum.jogamp.org/Placing-a-NewtCanvasJFX-within-other-JavaFX-layout-elements-td4043329.html))
- **[V]** JOGL itself is still active: the [repo](https://github.com/JogAmp/jogl) was pushed 2026-10-20,
  and the 2.6.0 release is tracked in [JogAmp bug 1512](https://jogamp.org/bugzilla/show_bug.cgi?id=1512).
- **[I]** We can't overlay JavaFX UI on the tray, and layout is buggy. JOGL rendered offscreen would
  be the same as candidate 2 with a less active binding, so this path adds nothing over LWJGL.

### 5. Filament. Not recommended for v1

- **[V]** Google's PBR engine (v1.78.1 released 2026-10-21) offers:
  - backends: OpenGL 4.1+, Vulkan 1.0, Metal and WebGPU;
  - lighting and shadows: Cook-Torrance PBR, IBL, and "EVSM, PCSS, DPCF, or PCF shadows";
  - anti-aliasing: MSAA, TAA and FXAA;
  - transparency-related: screen-space refraction and `KHR_materials_transmission`.

  ([README](https://github.com/google/filament/blob/main/README.md))
- **[V]** The top-level README advertises only the "Java/JNI API for Android". `filament/README.md`
  mentions `filament-java.jar` and `filament-jni` for Java use, but `build.sh` has no desktop-Java
  option.
- **[I]** We would have to build the JNI bindings from source for three OSes, with no published
  artifacts, and still read frames back (Path A). Its quality is the best on paper, but it is the
  highest-effort and highest-risk option.

### Excluded

- **DriftFX.** **[V]** "We stopped developing DriftFX - feel free to fork and move on yourself". The
  repo was archived on 2026-10-14; the last release was v1.0.0 in 2022. It supported only Prism D3D9Ex
  on Windows and was broken on Linux with Intel graphics.
  ([efxclipse-drift](https://github.com/eclipse-efx/efxclipse-drift))
- **FXyz3D.** **[V]** A JavaFX 3D component library (last release 0.6.0, 2023). It inherits all of
  JavaFX 3D's limits, so it is not a separate renderer. ([FXyz](https://github.com/FXyz/FXyz))
- **jzy3d.** **[V]** "A Java API for 3d and 2d charts". **[I]** It is a charting library, not suited
  to realistic material rendering. ([jzy3d-api](https://github.com/jzy3d/jzy3d-api))

## Ranked shortlist

| Rank | Candidate | Frame path | Realism ceiling | Break risk on JavaFX 27+ | Effort |
|---|---|---|---|---|---|
| 1 | **openglfx + LWJGL GL** (optionally libGDX + gdx-gltf on top) | Zero-copy on Windows `d3d` when the driver allows, Blit elsewhere | High (PBR, IBL, shadows, MSAA) | **Medium**: Prism internals, built against FX 17, single maintainer | Low to medium |
| 2 | **LWJGL offscreen + `PixelBuffer`** | CPU copy, ~1 GB/s at 1080p60 | High (you write it) | **Low**: public API only | Medium to high |
| 3 | jME 3.9 offscreen + custom `PixelBuffer` glue | CPU copy | Highest out of the box, plus Minie physics | Low (public FX API), but jME 3.10 backend churn | Medium, with lifecycle glue |
| 4 | JOGL `NewtCanvasJFX` | Native child window | High | Medium | Blocked by z-order and layout bugs |
| 5 | Filament (custom JNI build) | CPU copy | Highest | High (no artifacts) | High |

**Recommendation for the side-by-side prototype.** Use **openglfx 4.2.3 with LWJGL 3.4 on JavaFX
27 / JDK 27**, and run with `-Dopenglfx.disable.exports=true` plus explicit `--add-exports`. Log the
selected `GLInteropType` on the target iGPU. Then force `Blit` (which is the same as candidate 2's
cost) to measure the CPU-copy path on the same scene. That single prototype answers both "does
zero-copy work on JavaFX 27?" and "is the copy path good enough?". If openglfx fails on JavaFX 27,
candidate 2 is a small step from its Blit mode.

## Open questions for the prototype

1. Does openglfx 4.2.3 run unmodified on JavaFX 27.0.1 / JDK 27 with LWJGL 3.4.3? (It was built
   against FX 17 and LWJGL 3.3.2.)
2. Which `GLInteropType` does it pick on the target Intel or AMD iGPU under the default `d3d`
   pipeline? What are the frame time and FX-thread time for each type at 1080p and at 150% DPI?
3. How much FX-thread time do `PixelBuffer.updateBuffer` and the Prism upload cost at 1080p60?
4. Does libGDX (if used for 1a) work on the module path with `jlink`, or does it force a classpath
   launch?
5. Which physics library pairs best (jolt-jni vs Libbulletjme)? That belongs to a separate ticket.
