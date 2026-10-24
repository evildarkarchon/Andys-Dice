# JavaFX 27 3D API capabilities and JDK 27 toolchain readiness

Research for [issue #2](https://github.com/evildarkarchon/Andys-Dice/issues/2). Researched 2026-10-23.

The question feeds the renderer decision: should JavaFX 3D draw the dice itself, or should the app
host an embedded renderer inside JavaFX? Each section separates **Verified** facts (each with a
primary source) from **Inference**, which is my reading of those facts.

## TL;DR

- **Toolchain: ready.** JDK 27 went GA on 2026-09-15. JavaFX 27 went GA the same day, and 27.0.1
  is already on Maven Central. `maven-compiler-plugin` passes `<release>27</release>` straight
  through to javac, and JDK 27 made no jlink or jpackage changes. The weak link is
  `javafx-maven-plugin`: its last release is 0.0.8 (2021) and it is effectively unmaintained.
- **JavaFX 3D: functional, but a 2013-era fixed-function Phong renderer.** It has one material
  (`PhongMaterial`, now sealed), with no PBR, no environment or reflection maps, no Fresnel, and no
  custom shaders. It has four light types, but **no shadows**, and **at most 3 non-ambient lights
  per shape**. Transparency means alpha blending with no sorting. Antialiasing is on/off only
  (up to 4x MSAA on D3D). D3D 3D textures do not use mipmaps. There is no built-in model importer.
- **Nothing on the roadmap changes this.** No PBR, shadow, or shader-API proposal is active. The
  only big 3D-adjacent work is an Oracle Direct3D 12 pipeline (PR open since 2026-10-16, opt-in).
  Its stated goal is parity with D3D9, not new features.
- **For "maximally realistic" dice**, JavaFX 3D cannot reach PBR-quality metal, glass, or
  translucent resin dice, or contact shadows, without heavy faking (inference). This favours an
  embedded renderer, or at least a prototype comparing the two, which issue #8 is set up to decide.

## 1. Materials

**Verified**

- `Material` is declared `public abstract sealed class Material ... permits PhongMaterial`, so
  `PhongMaterial` is the only material, and applications cannot add their own. The class was
  sealed in JavaFX 24 (JDK-8346227).
  Sources: [Material javadoc 27](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/paint/Material.html),
  [JavaFX 24 release notes](https://github.com/openjdk/jfx/blob/master/doc-files/release-notes-24.md).
- `PhongMaterial` has these properties: `diffuseColor`, `diffuseMap`, `specularColor`,
  `specularMap`, `specularPower`, `bumpMap` (a normal map, not a height map), and
  `selfIlluminationMap`. The javadoc gives the final color as `(A+D)*CDM + S*CSM + CLM`.
  Source: [PhongMaterial javadoc 27](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/paint/PhongMaterial.html).
- The javadoc itself says `PhongMaterial` "is not suitable for surfaces that act like mirrors and
  reflect their environment", and that Fresnel effects (refraction, reflectivity varying with angle)
  are not implemented. The alpha channels of the specular and self-illumination colors and of the
  bump map are "not used at this time". A self-illumination color cannot be set directly; the
  workaround is a 1x1 map. Fully transparent (0 opacity) objects may not render. Same source.
- **PBR:** JBS returns zero JavaFX issues for `text ~ "PBR"` and zero for "physically based".
  Source: JBS REST search, project JDK, component javafx, run 2026-10-23.
- **Custom shaders:** [JDK-8134662 "Public Shader API"](https://bugs.openjdk.org/browse/JDK-8134662)
  has been open since 2015. Kevin Rushforth (OpenJFX lead) commented then: "We do not currently plan
  to do this, but could consider this in a future version." Nothing has changed since.
- **Environment, reflection, or cube maps:** JBS has no JavaFX issue for any of them.

**Inference**

- Polished-metal, glass, and gemstone dice, which are common real-world styles, need environment
  reflections, Fresnel, and (for glass) refraction. JavaFX 3D can only approximate these with baked
  textures and specular tricks.
- The javadoc changes in JavaFX 22 (JDK-8314147) documented the existing model. They did not
  extend it.

## 2. Lighting

**Verified**

- `LightBase` is `sealed ... permits AmbientLight, DirectionalLight, PointLight`, and `SpotLight`
  extends `PointLight`. Lights support `scope`, `exclusionScope` and attenuation, where
  `attn = 1 / (ca + la*dist + qa*dist^2)`.
  Source: [LightBase javadoc 27](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/LightBase.html).
- When each light type arrived: PointLight attenuation in JavaFX 16
  ([JDK-8217472](https://bugs.openjdk.org/browse/JDK-8217472)), `SpotLight` in 17
  ([JDK-8234920](https://bugs.openjdk.org/browse/JDK-8234920)), and `DirectionalLight` in 18
  ([JDK-8234921](https://bugs.openjdk.org/browse/JDK-8234921)).
  Sources: [release notes 16](https://github.com/openjdk/jfx/blob/master/doc-files/release-notes-16.md),
  [17](https://github.com/openjdk/jfx/blob/master/doc-files/release-notes-17.md),
  [18](https://github.com/openjdk/jfx/blob/master/doc-files/release-notes-18.md).
- **Shadows: none.** The only "shadow" the LightBase javadoc mentions is a negative-attenuation
  light that subtracts color. That darkens a region; it does not block light. A JBS search for
  shadow plus 3D or light finds no 3D shadow-mapping request, only 2D `DropShadow` and
  `InnerShadow` effect bugs.
- **At most 3 non-ambient lights per shape.** `D3DMeshView.h` has `#define MAX_NUM_LIGHTS 3`.
  `NGShape3D.setupLights` adds all ambient lights together, then takes point, spot and directional
  lights in scene order. Its own comment reads "Currently we simply select the first 3", and it
  resets the slots up to 3. With no lights in the scene, JavaFX adds a default white point light at
  the camera.
  Sources: [D3DMeshView.h @ jfx27](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.graphics/src/main/native-prism-d3d/D3DMeshView.h),
  [NGShape3D.java @ jfx27](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.graphics/src/main/java/com/sun/javafx/sg/prism/NGShape3D.java).
- On openjfx-dev, Nir Lisker (3D maintainer) gave the D3D9 sampler limit as the reason "we are
  currently limited to 3 lights per mesh".
  Source: [openjfx-dev, 2024-10](https://mail.openjdk.org/pipermail/openjfx-dev/2024-October/049960.html).
- [JDK-8295961 "Revisit performance improvements of the 3D pipeline"](https://bugs.openjdk.org/browse/JDK-8295961)
  is still open. It mentions "removing the restriction of 3 lights" as a future cost.
- [jfx PR #2319](https://github.com/openjdk/jfx/pull/2319) (D3D9: cache lights instead of
  re-uploading them every call) was integrated on 2026-10-22, on the master branch, which means
  JavaFX 28. It is a performance fix only.

**Inference**

- A typical dice scene (key light, fill light, rim light, plus ambient) fits inside 3 lights. The
  missing shadows matter more: dice resting on a table look like they float unless the app fakes
  contact shadows, for example with a blurred decal quad under each die.

## 3. Transparency

**Verified**

- The diffuse alpha (from the color or the map) controls how much light passes through
  ([PhongMaterial javadoc](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/paint/PhongMaterial.html)).
  Blending has worked since 8u60 (RT-40600). In [JDK-8090548](https://bugs.openjdk.org/browse/JDK-8090548),
  Chien Yang (Oracle) wrote that this works "as long as any overlapping 3D shapes are sorted such
  that the transparent 3D shapes (nearest last) are rendered after opaque 3D shapes". That issue is
  still open. Kevin Rushforth noted there that the blend equation assumes premultiplied alpha and
  would need to be re-examined.
- **JavaFX does not sort.** The `Node.viewOrder` javadoc tells applications to do transparency
  sorting themselves by setting `viewOrder` to each node's distance from the viewer
  ([Node javadoc 27](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/Node.html)).
  Sorting happens per node within a parent. Nothing sorts the triangles inside one mesh.
- [JDK-8170852 "Order-independent transparency for 3D objects"](https://bugs.openjdk.org/browse/JDK-8170852)
  has been open since 2016. Its status: "unplanned at this time".

**Inference**

- One translucent die among opaque ones can work if the app sorts by distance every frame. A
  translucent die with visible inner numbers, or several overlapping translucent dice, will show
  artefacts. Back faces inside one mesh composite in the wrong order, and depth writes cause
  self-occlusion. Translucent resin dice are a popular style, so this is a real gap for "maximally
  realistic".

## 4. Meshes and importers

**Verified**

- `TriangleMesh` supports two vertex formats: `POINT_TEXCOORD` (the default) and
  `POINT_NORMAL_TEXCOORD` (explicit per-vertex normals). It also supports texture coordinates and up
  to 32 face smoothing groups. Smoothing groups are ignored when explicit normals are used. Front
  faces wind counter-clockwise. An invalid mesh logs a warning and renders nothing. There are no
  tangent vectors, vertex colors, multiple UV sets, skinning, or morph targets.
  Source: [TriangleMesh javadoc 27](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/shape/TriangleMesh.html).
- `Shape3D` is `sealed ... permits Box, Cylinder, MeshView, Sphere`, sealed in JavaFX 24
  ([JDK-8339603](https://bugs.openjdk.org/browse/JDK-8339603)). `Mesh` is not sealed, but
  `TriangleMesh` is the only implementation.
- **There is no built-in importer.** The public `javafx.scene.shape` package holds only Box,
  CullFace, Cylinder, DrawMode, Mesh, MeshView, ObservableFaceArray, Shape3D, Sphere, TriangleMesh
  and VertexFormat. The jfx repository does have importers for OBJ, Maya (.ma), 3ds Max (.ase) and
  Collada (.dae), but only inside the unshipped `apps/samples/3DViewer` sample
  ([source](https://github.com/openjdk/jfx/tree/master/apps/samples/3DViewer/src/main/java/com/javafx/experiments/importers)).
- Two texture problems are open:
  [JDK-8311022](https://bugs.openjdk.org/browse/JDK-8311022) reports that D3D creates mipmaps but
  never sets `D3DSAMP_MIPFILTER`, so 3D diffuse and self-illumination maps are sampled without
  mipmaps on Windows. [JDK-8324594](https://bugs.openjdk.org/browse/JDK-8324594) reports that 3D
  texture filtering is always linear and cannot be configured.

**Inference**

- Dice geometry is simple: d4, d6, d8, d10, d12, d20 and d100 shapes, with bevels. Generating it in
  code, or with a small OBJ or glTF loader we write ourselves, is easy. The lack of an importer is
  minor.
- No mipmaps on Windows means small or distant dice will shimmer as they tumble. That matters for
  the numbers printed on the faces.

## 5. Antialiasing, pipelines and integrated graphics

**Verified**

- `SceneAntialiasing` has only two values: `DISABLED` and `BALANCED`. The sample count cannot be
  chosen ([javadoc 27](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/SceneAntialiasing.html)).
  On D3D, `BALANCED` gives 4x MSAA, or 2x if the hardware's maximum is below 4 (`D3DResourceFactory`:
  `aaSamples = maxSamples < 2 ? 0 : (maxSamples < 4 ? 2 : 4)`).
  Source: [D3DResourceFactory.java @ jfx27](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.graphics/src/main/java/com/sun/prism/d3d/D3DResourceFactory.java).
- Default pipeline order in `PrismSettings` @ jfx27: Windows `d3d, sw`; macOS `mtl, es2, sw` (Metal
  became the default in JavaFX 27,
  [JDK-8373091](https://bugs.openjdk.org/browse/JDK-8373091)); Linux `es2, sw`.
  The `sw` pipeline returns `is3DSupported() == false`.
  Sources: [PrismSettings.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.graphics/src/main/java/com/sun/prism/impl/PrismSettings.java),
  [SWPipeline.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.graphics/src/main/java/com/sun/prism/sw/SWPipeline.java),
  [release notes 27](https://github.com/openjdk/jfx/blob/master/doc-files/release-notes-27.md).
- The Windows pipeline is **Direct3D 9Ex** (`Direct3DCreate9Ex()` in `D3DPipelineManager.cc`). It
  needs pixel shader 3.0, and it rejects GPUs on a hard-coded blocklist (`D3DBadHardware.h`). Every
  entry on that list is a GPU from roughly 2007 to 2011: Intel GMA and early HD (Clarkdale,
  Arrandale, Sandy Bridge with old drivers), GeForce 5/6/7300, Quadro FX, ATI X1xxx, S3, SiS,
  Matrox.
  Sources: [D3DPipelineManager.cc](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.graphics/src/main/native-prism-d3d/D3DPipelineManager.cc),
  [D3DBadHardware.h](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.graphics/src/main/native-prism-d3d/D3DBadHardware.h).
- The only unresolved JavaFX JBS issue with "Intel" in its title is
  [JDK-8357043](https://bugs.openjdk.org/browse/JDK-8357043), about render performance on Intel
  MacBooks. It does not affect Windows.
- **Direct3D 12 pipeline:** Oracle (Lukasz Kostyra) announced it in October 2024. Early-access
  builds are at [jdk.java.net/javafxdirect3d12](https://jdk.java.net/javafxdirect3d12/), and
  [PR #2346](https://github.com/openjdk/jfx/pull/2346) (JDK-8394770) opened on 2026-10-16 to bring
  it into mainline. It is opt-in with `-Dprism.order=d3d12`; D3D9 stays the default. It uses HLSL
  SM6 compiled with DXC. Kostyra said on the list: "We're not planning any new features yet". The
  goal is "a working backend which is a suitable replacement to D3D9 backend".
  Sources: [PR #2346](https://github.com/openjdk/jfx/pull/2346),
  [openjfx-dev msg15511](https://www.mail-archive.com/openjfx-dev@openjdk.org/msg15511.html).

**Inference**

- Any current Intel UHD or Iris Xe, or AMD Radeon integrated GPU, supports D3D9Ex with SM3 and is
  not blocklisted, so JavaFX 3D will run on the target hardware. The real risk is the silent
  fallback: if D3D fails to initialise (driver trouble, RDP, some VMs), JavaFX drops to `sw`, and
  3D disappears entirely. The app should check `Platform.isSupported(ConditionalFeature.SCENE3D)`
  and show a clear message.
- D3D12 might eventually lift the 3-light limit, since the D3D9 sampler limit is the stated cause.
  No one has committed to that. It will not land before JavaFX 28 (expected around March 2027) and
  will be opt-in.

## 6. 3D changes in JavaFX 18–27

From the [release notes](https://github.com/openjdk/jfx/tree/master/doc-files), grepped for
3D, light, material, mesh, pipeline and shader terms:

| Release | 3D-relevant changes |
| --- | --- |
| 18 | `DirectionalLight` added (JDK-8234921). PointLight illumination fix (JDK-8255015). |
| 19 | ES2 AMD Vega20 detection fix. |
| 20 | Requires JDK 17. Point3D docs. |
| 21 | Attenuation fix for removed lights (JDK-8313227). |
| 22 | PhongMaterial documentation rewrite (JDK-8314147). |
| 23 | Requires JDK 21. |
| 24 | Requires JDK 22. `Node`, `Camera`, `LightBase`, `Shape`, `Shape3D` sealed (JDK-8339603). `Paint` and `Material` sealed (JDK-8346227). `TriangleMesh.vertexFormat` default fix. |
| 25 | Requires JDK 23. macOS 14+ 3D lighting fix (JDK-8318985). |
| 26 | Requires JDK 24. macOS Metal pipeline added (JDK-8271024). D3D NPE fix. |
| 27 | Requires JDK 25. Metal is the macOS default (JDK-8373091). |

**Inference:** in ten releases, the only new 3D feature is `DirectionalLight` (18). Everything else
is bug fixes, documentation, sealing, and a new macOS backend. The 3D feature set is effectively
frozen.

## 7. JDK 27, JavaFX 27 and the Maven toolchain

**Verified**

- **JDK 27 is GA**, released on 2026-09-15
  ([openjdk.org/projects/jdk/27](https://openjdk.org/projects/jdk/27/)). The
  [JDK 27 release notes](https://www.oracle.com/java/technologies/javase/27-relnote-issues.html)
  have no jlink or jpackage entries. The javac entries are minor source-validation tightenings.
  Compact object headers and G1 are now the defaults everywhere.
- **JavaFX 27 is GA.** `org.openjfx:javafx-*:27` was published to Maven Central on 2026-09-15, and
  `27.0.1` on 2026-10-20. Platform classifiers: `win`, `linux`, `linux-aarch64`, `mac`,
  `mac-aarch64` ([Maven Central](https://repo1.maven.org/maven2/org/openjfx/javafx-graphics/27.0.1/)).
  JavaFX 27 requires JDK 25 or later and recommends JDK 27
  ([release notes 27](https://github.com/openjdk/jfx/blob/master/doc-files/release-notes-27.md)).
  [jdk.java.net/javafx27](https://jdk.java.net/javafx27/) offers the 27.0.1 SDK and **JMODs** for
  Windows x64. JavaFX 28 is in early access (28-ea+12 on Central).
- **maven-compiler-plugin 3.16.0** was published on 2026-08-30. `release` passes straight through
  to `javac --release`, with no upper bound in the plugin. 4.0.0-beta-5 is the line for Maven 4.
  Source: [compile-mojo docs](https://maven.apache.org/plugins/maven-compiler-plugin/compile-mojo.html).
- **Maven:** 3.9.16 (2026-05-13) and 3.10.0 (2026-09-27) both require Java 8 or later to run.
  4.0.0-rc-7 (2026-09-24) requires Java 17 and is not GA.
  Source: [Maven release history](https://maven.apache.org/docs/history.html). The `sun.misc.Unsafe`
  warning that Guice printed under JDK 24+ was fixed by
  [apache/maven#10312](https://github.com/apache/maven/issues/10312), which shipped after 3.9.11.
  [apache/maven#11167](https://github.com/apache/maven/issues/11167) (Windows **mapped network
  drives** resolve wrongly on JDK 25+) was closed on 2026-10-22, and the fix may not be released yet.
- **javafx-maven-plugin** (`org.openjfx`): the latest version is **0.0.8, last updated
  2021-10-05**. The repository's last push was 2023-08-15. An open issue, "Is this project dead?"
  ([#191](https://github.com/openjfx/javafx-maven-plugin/issues/191)), has no reply. Its `jlink`
  goal checks `compress` as an integer, so it cannot pass the newer `zip-N` values
  ([#179](https://github.com/openjfx/javafx-maven-plugin/issues/179),
  [#188](https://github.com/openjfx/javafx-maven-plugin/issues/188)). It also does not put
  `jfx.incubator.*` modules on the path for non-modular apps
  ([#189](https://github.com/openjfx/javafx-maven-plugin/issues/189)).
- **maven-jlink-plugin** 3.3.0 (Apache) was published on 2026-03-09. **jpackage-maven-plugin**
  (org.panteleyev, third-party) 1.8.0 was published on 2026-06-27. Apache has no official jpackage
  plugin.

**Inference**

- Recommended setup: Maven 3.9.16 or 3.10.0, run on JDK 27, with `maven-compiler-plugin` 3.16.0 and
  `<release>27</release>`, and the `org.openjfx` 27.0.1 dependencies with the `win` classifier.
  Use `javafx-maven-plugin` 0.0.8 only for `javafx:run` during development, where it still works by
  building a module path. Do packaging with `maven-jlink-plugin` or a direct `jlink`/`jpackage`
  call (exec-maven-plugin, or panteleyev's plugin), using the JavaFX 27 Windows JMODs.
- The project lives on `H:`. If that is a mapped network drive and not a local disk, watch for
  apache/maven#11167.
- None of this is verified by building yet. A one-hour spike that runs `javafx:run` on a lit
  `MeshView` and then `jpackage`s it would confirm the toolchain end to end.

## Implications for the renderer decision (issue #8)

| Need for realistic dice | JavaFX 3D (D3D9) | Notes |
| --- | --- | --- |
| Metallic, glossy, glass, resin materials | No (Phong only; no environment maps, Fresnel, or refraction) | Could be faked with textures. |
| Shadows (contact shadows, dice on table) | No | Fake with decal quads. |
| Translucent dice | Partial (manual per-node sorting; no OIT) | Self-sorting artefacts inside a mesh. |
| Lights | 3 non-ambient per shape | Enough for a 3-point setup. |
| Antialiasing | 4x MSAA, on/off | Adequate. |
| Texture quality (face numbers) | No mipmaps on D3D | Shimmer when minified. |
| Runs on integrated GPUs | Yes (D3D9Ex/SM3) | Falls back to no-3D `sw` if D3D fails. |
| Roadmap relief | None planned | D3D12 aims for parity, not features. |

**Inference:** JavaFX 3D can render credible, simple dice (opaque, Phong-shaded, textured numbers,
3-point lighting) with little integration work and good integrated-GPU support. It cannot reach
"maximally realistic" for metal, glass, or translucent dice, or for real shadows, and nothing in
OpenJFX will change that soon. An embedded renderer would provide PBR, image-based lighting, shadow
maps, and order-independent or sorted transparency. Possible routes include an LWJGL/OpenGL or
Vulkan renderer drawn into a JavaFX `WritableImage` or `PixelBuffer`, or a native engine. The cost
is a harder JavaFX interop problem (frame copy or shared texture), which needs its own research.

## Sources consulted

- OpenJFX release notes 16–27: <https://github.com/openjdk/jfx/tree/master/doc-files>
- JavaFX 27 javadoc: <https://openjfx.io/javadoc/27/>
- openjdk/jfx source at tag `jfx27`: D3DMeshView.h, D3DPipelineManager.cc, D3DBadHardware.h,
  D3DResourceFactory.java, NGShape3D.java, PrismSettings.java, SWPipeline.java
- JBS (bugs.openjdk.org) REST searches and issues: JDK-8090548, JDK-8170852, JDK-8134662,
  JDK-8295961, JDK-8311022, JDK-8324594, JDK-8357043
- openjfx-dev: <https://mail.openjdk.org/pipermail/openjfx-dev/2024-October/049960.html>,
  <https://www.mail-archive.com/openjfx-dev@openjdk.org/msg15511.html>,
  <https://www.mail-archive.com/openjfx-dev@openjdk.org/msg22073.html>
- jfx PRs #2319 and #2346
- JDK 27: <https://openjdk.org/projects/jdk/27/>,
  <https://www.oracle.com/java/technologies/javase/27-relnote-issues.html>
- JavaFX 27 builds: <https://jdk.java.net/javafx27/>; Maven Central metadata for `org.openjfx`,
  `maven-compiler-plugin`, `maven-core`, `maven-jlink-plugin`, `jpackage-maven-plugin`
- Maven: <https://maven.apache.org/docs/history.html>, compile-mojo docs, apache/maven #10312 and
  #11167, openjfx/javafx-maven-plugin issues #179, #188, #189, #191
