# PROTOTYPE: renderer bake-off (throwaway)

> Lives only on the `prototype/renderer-bakeoff` branch. Never merge it. It answers one question for
> [Renderer bake-off: JavaFX 3D vs embedded renderer d20](https://github.com/evildarkarchon/Andys-Dice/issues/8)
> and is kept as a primary source.

**Question.** Rendered side by side, which gets a die closest to real: JavaFX 3D, or the strongest
embedded-renderer candidate (openglfx + LWJGL OpenGL, per the research)?

Both variants draw **the same scene from the same Java code**: one procedural rounded Chessex-layout d20
(or 20 of them), the same numeral atlas, felt and wood textures, camera, lights and motion. Only the
renderer differs.

| Key | Variant | What it is |
|---|---|---|
| A | JavaFX 3D (Phong) | `SubScene` + `MeshView`, `PhongMaterial` with diffuse/specular/normal maps, 2 directional + ambient light (JavaFX's 3-light limit), 4x MSAA. No shadows (a blob texture fakes one), no reflections. |
| B | openglfx + LWJGL GL (auto interop) | openglfx `GLCanvas` in the JavaFX window, hand-written GL 3.3 core shaders: GGX PBR, procedural studio environment reflections, 2048² shadow map with PCF, parallax engraving, contact AO. openglfx picks zero-copy GPU sharing where the driver allows. |
| C | openglfx + LWJGL GL (forced Blit) | Same renderer as B, but frames are copied through the CPU every frame, which is what a public-API-only `PixelBuffer` embed would cost. |

## Run

From the repo root, with JDK 27 (Windows paths for `mvn.cmd`):

```
JAVA_HOME='C:\OpenJDK\jdk-27' mvn.cmd -q -f prototypes/renderer-bakeoff/pom.xml package exec:exec
```

Options (append to the command):

- `-Dbakeoff.variant=B`: start on a variant.
- `-Dbakeoff.uncapped=true`: vsync off and JavaFX full-speed pulses, so the HUD frame rate reflects cost.
- `-Dbakeoff.capture=target/capture`: unattended run that screenshots every variant and scenario with
  the clock frozen at the same instant, writes `stats.txt`, then exits.

Keys: `<-`/`->` variant, `M` Dice Set look (crimson resin, ivory, gunmetal), `T` tumble, `D` 1 or 20
dice, `Space` pause the orbit, drag to orbit.

## What to judge

1. **Realism.** Flip A, B and C on each Dice Set look (`M`), especially the gunmetal one, and with 20
   dice (`D`). Watch the shadows while tumbling (`T`).
2. **Frame rate on integrated graphics.** Run with `-Dbakeoff.uncapped=true` on an iGPU machine and read
   the HUD: JavaFX pulses/s, GPU ms (B/C), the interop type B picked, and whether C keeps up.
3. **Effort.** Line counts below.

## Findings from the build (2026-10-24, RTX 4070, JavaFX 27.0.1, JDK 27)

- openglfx 4.2.3 **runs unmodified on JavaFX 27 + JDK 27 + LWJGL 3.4.3**, with JavaFX on the module
  path and the 7 `--add-exports` passed explicitly (`-Dopenglfx.disable.exports=true`, so no Unsafe
  patching). On the NVIDIA driver it chose `ExternalObjectsD3D` (zero-copy).
- **Hang:** disposing one `GLCanvas` and creating another on the FX thread hung the app once (Windows
  `AppHangB1`) after succeeding 6 times. The prototype now builds each variant once and keeps it. v1
  would hold a single long-lived canvas, but the hang belongs in the risk ledger.
- Packaging flags discovered: `openglfx-lwjgl`/`grapl-gl` publish `kotlin-stdlib-jdk8` with
  `<version>unspecified</version>` (pin it in `dependencyManagement`); LWJGL needs
  `--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED` or it falls back to `sun.misc.Unsafe` with
  JDK 27 deprecation warnings; JOML needs `-Djoml.nounsafe=true` for the same reason; exec-maven-plugin
  rewrites a bare `--module-path` argument, so pass `--module-path=...` as one token.
- With vsync off, every variant hits JavaFX's 1000 pulses/s ceiling on this GPU, so frame rate does not
  separate them here. B's GPU time is 0.09-0.15 ms/frame at 1280x800 even with 20 dice; C's Blit copy
  still sustained 740-880 GL renders/s. **Not measured: any integrated GPU** (this machine has none
  enabled).
- JavaFX 3D's bump map uses the same tangent convention as the GL path (u right, v image-down), so one
  normal map serves both.
- A JavaFX gotcha: converting a Y-up scene by negating Y is a reflection and mirrors every numeral;
  use a 180 degree rotation about X instead.

## Size (lines, excluding blank-line differences)

| Part | Lines |
|---|---|
| Shared: d20 mesh, textures, scene, app shell | ~840 |
| A: JavaFX 3D variant | 221 |
| B/C: GL variant (343 Java + 233 GLSL) | 576 |

Screenshots in [`screenshots/`](screenshots/), frame stats in
[`screenshots/stats-uncapped.txt`](screenshots/stats-uncapped.txt).
