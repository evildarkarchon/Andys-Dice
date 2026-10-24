# Java physics engines for tumbling dice on JDK 27

Research for [issue #4](https://github.com/evildarkarchon/Andys-Dice/issues/4). Researched 2026-10-23.

**Question:** Which Java rigid-body physics engines suit tumbling dice on JDK 27?

The 7-piece set (d4, d6, d8, d10, percentile d10, d12, d20) is all **convex** polyhedra. Each Die can
therefore be a single convex collision shape, with no compound or decomposition needed. What matters is
rounded edges, determinism, headless stepping, packaging, and rest detection.

Labelling: **[V]** means verified against the cited primary source. **[I]** means my inference or
engineering judgement, not stated by the source.

## TL;DR

1. **Jolt Physics via [jolt-jni](https://github.com/stephengold/jolt-jni) is the best fit.**
   - Rounded convex hulls are built in: `ConvexHullShapeSettings` takes a convex radius.
   - It has documented cross-platform determinism, and jolt-jni enables it in its default natives.
   - It has a single-threaded job system, `SaveState`/`RestoreState`, and sleep detection.
   - It is actively maintained and has a JDK 27 CI job.
2. **Bullet via [Libbulletjme](https://github.com/stephengold/Libbulletjme) is a solid fallback.**
   - It has the same maintainer, packaging model, and JDK 27 CI as jolt-jni.
   - The catch: no cross-platform determinism guarantee, and hull margins enlarge shapes rather than
     rounding them in place.
3. **PhysX via [physx-jni](https://github.com/fabmax/physx-jni)** is capable, but its determinism is
   only "same platform, same release". Its rounded-shape primitive (`PxConvexCoreGeometry`) has no
   arbitrary-hull core, so it cannot round a d20 directly.
4. **ode4j** is pure Java with no natives, but it has been dormant since mid-2025. It has no rounding
   primitive, and its process-global RNG makes parallel simulations awkward.
5. **JBullet** (and Minie, which needs jMonkeyEngine) are not recommended.

## Comparison table

| | jolt-jni (Jolt) | Libbulletjme (Bullet) | physx-jni (PhysX 5) | ode4j (ODE) | JBullet |
|---|---|---|---|---|---|
| Latest release | 6.2.0, 2026-10-14 [V] | 23.2.0, 2026-10-13 [V] | v2.8.0, 2026-09-27 (PhysX 5.11.0) [V] | 0.5.4, 2025-05-25 [V] | 1.0.3 (stephengold fork), 2024-06-13 [V] |
| Activity | Commits 2026-10-21; upstream Jolt active [V] | Commits 2026-10-21 [V] | Pushed 2026-09-27 [V] | Last commit 2025-06-12 [V] | Legacy 2008 port [V] |
| Implementation | JNI to C++ | JNI to C++ | JNI to C++ | Pure Java | Pure Java |
| Rounded convex hull | Yes: hull plus convex radius, shrunk then inflated, so size is preserved [V] | Hull margin *enlarges* the shape; move vertices inward to compensate [V] | Convex mesh (no radius); `PxConvexCoreGeometry` plus margin only for box, ellipsoid, cylinder, cone, segment, point cores [V] | Convex hull, no radius; bevel vertices yourself [I] | As Bullet (old) |
| Same-machine determinism | Yes, if API calls are in the same order on the same binary [V] | Achievable with a fixed step, a fresh space, and `useDeterministicDispatch(true)` [V/I] | Yes: "same scene … same PhysX release … same platform" [V] | Yes, if the global seed is reset and runs are single-threaded [I] | Likely, not documented |
| Cross-platform determinism | Yes, with `JPH_CROSS_PLATFORM_DETERMINISTIC`; jolt-jni enables it for every platform except `Linux64_fma` and `Windows64_avx2` [V] | Not promised [V: no such claim] | Not claimed [V] | Not guaranteed: uses `Math.sin`, which may vary by platform [V/I] | Not guaranteed |
| Faster than real time | `PhysicsSystem.update(dt, …)`; caller-driven; `JobSystemSingleThreaded` available [V] | `PhysicsSpace.update(dt, 0)` = one step of exactly `dt` [V] | `simulate(dt)`/`fetchResults` (caller-driven) [I, standard API] | `quickStep(dt)` [V] | caller-driven |
| Rest detection | Island sleep: `mTimeBeforeSleep` 0.5 s, `mPointVelocitySleepThreshold` 0.03 m/s; activation listener [V] | Deactivation: 2 s default time; thresholds 0.8 linear / 1.0 angular; `isActive()` [V] | Kinetic-energy sleep threshold plus wake counter [V] | Auto-disable islands [V] | Bullet-style |
| CCD | `LinearCast` motion quality [V] | Swept-sphere CCD (`setCcdMotionThreshold`, `setCcdSweptSphereRadius`) [V] | Yes (PhysX CCD) [I] | No | Basic |
| Desktop natives | Win x64 (+avx2), Win ARM64, Linux x64 (+fma), Linux ARM64/ARM32, macOS x64/ARM64 [V] | Win x64, Win ARM64, Linux x64/ARM64, macOS x64/ARM64 [V] | Win x64, Linux x64, macOS x64/ARM64 [V] | none needed | none needed |
| JPMS | Real `module-info` (`com.github.stephengold.joltjni`); modular-build bug fixed in 6.2.0 [V] | No `module-info` (automatic module) [V] | Not checked | `module-info` in a java9 source set [V] | No |
| JDK 27 | Bytecode 11; CI job `Java27-Linux64` passing [V] | Bytecode 8; CI job `Java27-Linux64` passing [V] | Built with toolchain 17; CI on 17 only [V] | CI on 11 only [V] | Unknown |
| License | MIT [V] | BSD-3 (Bullet zlib) [V] | MIT (PhysX BSD-3) [V] | LGPL-2.1 / BSD dual (repo reports LGPL-2.1) [V] | zlib [V] |

## Findings per engine

### 1. Jolt Physics via jolt-jni

**Maintenance and packaging**

- jolt-jni ships a release roughly monthly. 6.2.0 came out on 2026-10-14 and the repo was last
  pushed on 2026-10-21. [V] [releases](https://github.com/stephengold/jolt-jni/releases)
- Upstream Jolt (11.7k stars) is very active: v5.6.0 shipped on 2026-07-11, and commits are landing
  daily. [V] [JoltPhysics](https://github.com/jrouwe/JoltPhysics/releases)
- Java bytecode targets release 11 (`options.release = 11`). [V]
  [build.gradle](https://github.com/stephengold/jolt-jni/blob/master/build.gradle)
- CI includes a `Java27-Linux64` job (Zulu 27, `build install`). Recent master runs succeed. [V]
  [push.yml](https://github.com/stephengold/jolt-jni/blob/master/.github/workflows/push.yml)
- Maven Central: group `com.github.stephengold`.
  - The JVM classes are in `jolt-jni-Windows64` and the other platform artifact IDs, which are
    interchangeable.
  - Natives are `runtimeOnly` artifacts per platform, with classifiers `{Debug,Release}{Sp,Dp}`.
  - The docs load natives with JSnapLoader (`io.github.electrostat-lab:snaploader:1.2.0-stable`)
    using `LoadingCriterion.CLEAN_EXTRACTION`.
  - Source: [V] [How to add Jolt JNI](https://stephengold.github.io/jolt-jni-docs/jolt-jni-en/English/add.html)
- Native flavours: `Linux64`, `Linux64_fma`, `MacOSX64`, `MacOSX_ARM64`, `Windows64`,
  `Windows64_avx2`, and ARM targets. Windows_ARM64 was added in 6.0.0. [V] build.gradle,
  [6.0.0 notes](https://github.com/stephengold/jolt-jni/releases/tag/6.0.0)
- `module-info.java` declares `open module com.github.stephengold.joltjni` and exports its packages. [V]
  [module-info.java](https://github.com/stephengold/jolt-jni/blob/master/src/main/java/module-info.java)
- 6.2.0 fixed "incorrect records in module-info.class cause modular builds to fail", so modular apps
  need ≥ 6.2.0. [V] [6.2.0 notes](https://github.com/stephengold/jolt-jni/releases/tag/6.2.0)
- Native objects are freed explicitly, or by a cleaner thread if you call
  `JoltPhysicsObject.startCleaner()`. [V] add.html

**Convex hulls and rounding**

- `ConvexHullShapeSettings(points, maxConvexRadius)` builds a rounded hull. [V]
  [ConvexHullShape.h](https://github.com/jrouwe/JoltPhysics/blob/master/Jolt/Physics/Collision/Shape/ConvexHullShape.h)
  - The radius "is subtracted so the total size will not grow with the convex radius".
  - It "is automatically lowered if the hull requires it".
  - `mMaxErrorConvexRadius` (0.05) bounds the deviation from the true hull.
  - At most 256 points per hull (`cMaxPointsInHull`).
- Jolt docs: the shape is "first shrunk by the convex radius, then inflated back by the same amount",
  which rounds edges. A larger radius performs better but is less accurate. [V]
  [Jolt docs, Convex Radius](https://jrouwe.github.io/JoltPhysics/index.html)
- `cDefaultConvexRadius = 0.05f`. [V]
  [PhysicsSettings.h](https://github.com/jrouwe/JoltPhysics/blob/master/Jolt/Physics/PhysicsSettings.h)
- [I] This matches "rounded polyhedron" exactly. Feed the sharp polyhedron's vertices and set the
  radius to the physical edge-rounding radius. Each Die Kind is then one `ConvexHullShape`, with no
  compound shapes and no hand-made bevel geometry for physics.

**Determinism**

- Deterministic "if the simulation-modifying APIs are called in exactly the same order" on the same
  binary. [V] [Jolt docs, Deterministic Simulation](https://jrouwe.github.io/JoltPhysics/index.html)
- Cross-platform mode (the `CROSS_PLATFORM_DETERMINISTIC` CMake option, i.e. the
  `JPH_CROSS_PLATFORM_DETERMINISTIC` define) works across compilers, OSes, and architectures at about
  8% cost. All platforms must use the same source and defines. [V] Jolt docs
- Multithreading makes callback order, broadphase query result order, and `GetActiveBodies` order
  nondeterministic. The simulation state itself stays deterministic. [V] Jolt docs
- Upstream removed `PhysicsSettings::mDeterministicSimulation`: "The simulation is now always
  deterministic". [V] [ReleaseNotes.md](https://github.com/jrouwe/JoltPhysics/blob/master/Docs/ReleaseNotes.md)
  jolt-jni 6.0.0 removed the matching Java accessors. [V]
- jolt-jni 6.0.0 "Enabled cross-platform determinism on all platforms except `Linux64_fma` and
  `Windows64_avx2`". It also added `Jolt.implementsCrossPlatformDeterminism()` to check at runtime.
  [V] [6.0.0 notes](https://github.com/stephengold/jolt-jni/releases/tag/6.0.0)
- In build.gradle, `JPH_CROSS_PLATFORM_DETERMINISTIC` is defined whenever the AVX2/FMA flavours are
  *not* selected. [V]
- `SaveState`/`RestoreState` support rollback. They don't cover bodies added or removed in between.
  [V] Jolt docs, "Rolling Back a Simulation". jolt-jni has `StateRecorder`/`StateRecorderImpl`. [V]
  (source tree)

**Stepping and rest**

- `physicsSystem.update(timeStep, numCollisionSteps, tempAllocator, jobSystem)` is called by the
  caller, so headless stepping in a loop runs as fast as the CPU allows. [V] add.html
- `JobSystemSingleThreaded` is exposed. [V] (source tree)
- Sleep works per island. The criteria are `mTimeBeforeSleep` (0.5 s) and
  `mPointVelocitySleepThreshold` (0.03 m/s). [V] Jolt docs, PhysicsSettings.h
- `BodyActivationListener` callbacks come from multiple threads during `Update`. [V] Jolt docs
  jolt-jni has `CustomBodyActivationListener`. [V]
- Defaults: `mSpeculativeContactDistance` 0.02, `mPenetrationSlop` 0.02, `mLinearCastThreshold` 0.75,
  `mMinVelocityForRestitution` 1.0, 10 velocity steps and 2 position steps. [V] PhysicsSettings.h
- `EMotionQuality::LinearCast` gives CCD for fast small bodies. Fast-*rotating* elongated bodies can
  still tunnel. [V] Jolt docs

### 2. Bullet via Libbulletjme (and Minie)

**Maintenance and packaging**

- 23.2.0 came out on 2026-10-13. Recent releases fixed a ReleaseSpMt access violation (#59), ARM64
  `SIGILL` (#57), and init races (#56). [V]
  [releases](https://github.com/stephengold/Libbulletjme/releases)
- 23.0.1 added Windows_ARM64 and moved the Windows build to VS 2026. [V]
- Bytecode target is 8. Flavours are Sp/Dp plus multithreaded `SpMt`/`DpMt`. [V]
  [build.gradle](https://github.com/stephengold/Libbulletjme/blob/master/build.gradle)
- CI has a `Java27-Linux64` job and recent runs succeed. [V]
  [push.yml](https://github.com/stephengold/Libbulletjme/blob/master/.github/workflows/push.yml)
- Packaging and loading are the same as jolt-jni: per-platform native artifacts, loaded with
  JSnapLoader. [V] [How to add Libbulletjme](https://stephengold.github.io/Libbulletjme/lbj-en/English/add.html)
- No `module-info.java` in the repo, so it works only as an automatic module. [V] (source tree) [I]
  The module name would be derived from the jar file name.
- Minie wraps the same natives for jMonkeyEngine: "Integrate Bullet Physics … into jMonkeyEngine".
  10.0.0-test1 came out on 2026-08-21. [V] [Minie](https://github.com/stephengold/Minie)
  [I] Minie pulls in jme3-core and is pointless for a JavaFX app; use Libbulletjme directly.

**Convex hulls and rounding**

- `HullCollisionShape` is "imprecise": the margin (default 0.04) uniformly *enlarges* the effective
  shape. The advice is to move hull vertices inward to compensate. [V]
  [Minie shape tutorial](https://stephengold.github.io/Minie/minie/minie-library-tutorials/shape.html)
- `MultiSphere` (the convex hull of spheres) is precise. [V] same page
- [I] Inward-offset vertices plus a margin give a GJK-rounded hull, much like Jolt. But the rounding
  radius is tied to the collision margin, which also affects performance and robustness, and the
  sizing is manual.

**Determinism**

- No determinism guarantee is documented. [V: absence]
- `CollisionSpace.useDeterministicDispatch(true)` turns on Bullet's `m_deterministicOverlappingPairs`,
  i.e. "sorted/deterministic order" (default false). [V]
  [CollisionSpace.java](https://github.com/stephengold/Libbulletjme/blob/master/src/main/java/com/jme3/bullet/CollisionSpace.java)
- The sequential-impulse solver keeps a seed `m_btSeed2` for row reordering, which `reset()` clears.
  [V] [btSequentialImpulseConstraintSolver.h](https://github.com/stephengold/Libbulletjme/blob/master/src/main/native/bullet3/BulletDynamics/ConstraintSolver/btSequentialImpulseConstraintSolver.h)
- Upstream bullet3 has open issues on save/restore nondeterminism: #4722 (2025) and #4387. [V]
  [#4722](https://github.com/bulletphysics/bullet3/issues/4722)
- In jME-hub threads, run-to-run differences were traced to variable-`tpf` stepping. They went away
  with a self-stepped `PhysicsSpace` on a fixed step. [V]
  [jME hub thread p.2](https://hub.jmonkeyengine.org/t/nondeterminism-in-bullet/43396?page=2)
- The maintainer notes the natives are built with several different compilers, which hurts
  portability. [V] [p.1](https://hub.jmonkeyengine.org/t/nondeterminism-in-bullet/43396)
- [I] Same-machine determinism is realistic if you use a fresh `PhysicsSpace` per Throw, a fixed
  step, deterministic dispatch, and no `Mt` flavour. Cross-machine determinism is unlikely.

**Stepping and rest**

- `PhysicsSpace.update(timeInterval, 0)` does "a single step of size timeInterval". [V]
  [PhysicsSpace.java](https://github.com/stephengold/Libbulletjme/blob/master/src/main/java/com/jme3/bullet/PhysicsSpace.java)
- Deactivation defaults: `gDeactivationTime` 2 s, linear sleeping threshold 0.8, angular 1.0. [V]
  btRigidBody.cpp/.h in the Libbulletjme tree
- The Java API has `setSleepingThresholds`, `setDeactivationTime`, `isActive()`, CCD (motion threshold,
  swept-sphere radius), and rolling/spinning friction. [V] PhysicsRigidBody.java,
  PhysicsCollisionObject.java

### 3. PhysX 5 via physx-jni

- v2.8.0 came out on 2026-09-27 and binds PhysX 5.11.0. Natives cover Windows x64, Linux x64, and
  macOS x64/arm64. They are published as `natives-*` classifiers. [V]
  [README](https://github.com/fabmax/physx-jni)
- Built with a JDK 17 toolchain, and CI uses only JDK 17. [V]
  [build.yml](https://github.com/fabmax/physx-jni/blob/main/.github/workflows/build.yml)
- Native objects need manual `destroy()` calls or stack allocation. [V] README
- Determinism: "PhysX provides limited deterministic simulation". Results are identical given the same
  scene, the same insertion order, the same time-stepping, the same release, and the same platform.
  Results are independent of worker-thread count. `eENABLE_ENHANCED_DETERMINISM` isolates islands
  from each other. No cross-platform claim is made. [V]
  [PhysX docs, Rigid Body Dynamics](https://nvidia-omniverse.github.io/PhysX/physx/5.6.1/docs/RigidBodyDynamics.html)
- Sleep uses a kinetic-energy threshold plus a wake counter. [V] same page
- Rounded shapes: `PxConvexCoreGeometry` adds a margin around a core. The bound core types are POINT,
  SEGMENT, BOX, ELLIPSOID, CYLINDER, and CONE. There is no hull core. [V]
  [PxGeometry.idl](https://github.com/fabmax/physx-jni/blob/main/physx-jni/src/main/webidl/geometry/PxGeometry.idl)
  [I] So a rounded d6 is native, but a rounded d4/d8/d10/d12/d20 needs a bevelled convex mesh.
- [I] PhysX is heavier, with GPU/articulation/vehicle features this project doesn't need. It has a
  single maintainer and no Windows ARM64 natives.

### 4. ode4j

- Pure-Java port of ODE 0.16.6. 0.5.4 came out on 2025-05-25 and the last commit was 2025-06-12,
  about 16 months before this research. CI runs Java 8 and Java 11. [V]
  [README](https://github.com/tzaeschke/ode4j), commits
- It has `DConvex` and a `module-info` (java9 source set). [V] (source tree)
- [I] ODE's convex geometry has no rounding radius, so bevelled vertices must be modelled explicitly.
- Rest detection: auto-disable of islands. README recommends `quickStep` over `step`. [V] README
- Determinism:
  - QuickStep reorders constraints with `dRandInt`, which draws from a process-global
    `static AtomicLong seed`. [V]
    [Misc.java](https://github.com/tzaeschke/ode4j/blob/master/core/src/main/java/org/ode4j/ode/internal/Misc.java)
    [I] Reset the seed before each Throw, and never run two simulations at once in one JVM, or the
    results interleave.
  - Since JDK 17 all floating-point arithmetic is strict ([JEP 306](https://openjdk.org/jeps/306)). But
    ode4j calls `Math.sin` and friends (`Common.java`, `DQuaternion.java`), and `Math` may use
    "platform-specific native libraries or microprocessor instructions", unlike `StrictMath`.
    [V/I] Bitwise cross-platform determinism is therefore not guaranteed even for this pure-Java
    engine.

### 5. JBullet and others

- **JBullet:** Martin Dvořák's 2008 Java port of Bullet. It survives as `com.github.stephengold:jbullet`
  1.0.3 (2024) for jME's `jme3-jbullet`. It depends on vecmath and on Ant-time "stack-alloc" bytecode
  instrumentation. [V] [stephengold/jbullet](https://github.com/stephengold/jbullet)
  [I] It is old Bullet 2.x with no rounding beyond margins. Not recommended.
- **jolt-java** (aecsocket): last pushed 2023-04. [V] Dead.
- **xpenatan/jJolt:** another Jolt binding, 17 stars, pushed 2026-08. [V] Not evaluated in depth.
- **dyn4j:** 2D only. Out of scope.
- **Rapier:** has no maintained Java binding. [V: GitHub search]

## Cross-cutting: JDK 27 and native access

- JDK 27 went GA on 2026-09-15. Its JEPs (G1 default, compact headers by default, Vector API
  incubator, etc.) include nothing about JNI or native access. [V]
  [JDK 27](https://openjdk.org/projects/jdk/27/)
- [JEP 472](https://openjdk.org/jeps/472) (JDK 24) still applies. [V]
  - Loading a native library or binding `native` methods without native access enabled prints a
    warning, at most once per module.
  - A future release will make `deny` the default.
  - Enable access with `--enable-native-access=<module>|ALL-UNNAMED`, or with the
    `Enable-Native-Access: ALL-UNNAMED` manifest attribute for `java -jar`.
- [I] Implications for the JNI engines (jolt-jni, Libbulletjme, physx-jni):
  - Plan for this from day one. Add `--enable-native-access=com.github.stephengold.joltjni` plus the
    native loader's module to the launcher or jlink/jpackage options.
  - Do this now, because a later JDK will fail hard instead of warning.
  - Pure-Java engines are unaffected.

## Cross-cutting: dice-scale tuning

All of this section is [I], engineering judgement from the defaults above:

- **Scale.** Real dice are about 12–25 mm, but the engines' defaults assume metre-scale bodies:
  - Jolt's speculative distance and penetration slop are 0.02, and its convex radius is 0.05.
  - Bullet's margin is 0.04.
  - Either model the tray in centimetres (gravity about 981 units/s²), or keep SI units and shrink the
    contact tolerances and convex radius. Rescaling the world is less error-prone.
- **Small timestep and CCD.** Use 240–480 Hz fixed steps for bouncy small dice. With headless
  pre-simulation the cost is negligible. Use `LinearCast` (Jolt) or CCD (Bullet) for release velocity.
- **Restitution and friction.** Expect to tune restitution, friction, and rolling friction against
  reference video. Defaults are generic.
- **Rest detection.** Use the engine's sleep state, but poll it on the stepping thread after each
  `update`:
  - Use `BodyInterface.isActive` in Jolt, or `isActive()` in Bullet.
  - Don't rely on multithreaded callbacks.
  - Confirm with a face check: the highest face normal is within a few degrees of vertical. That
    catches dice leaning against a wall or another die ("cocked dice").

## Cross-cutting: do we even need determinism?

[I] For the "choose the result first" strategy, pre-simulate headless, read the resting face, remap the
numerals, then play back. There are two options:

- **(a) Record the transforms** of each Die per frame during pre-simulation and replay the recording.
  This needs **no determinism at all** and works with any engine.
- **(b) Re-simulate live from the same initial state.** This needs same-binary, same-machine
  determinism. All four engines can plausibly provide that, with the caveats above.

Cross-machine determinism matters only if Throws are shared or replayed on other machines, which is
not a current goal. Only Jolt via jolt-jni offers it out of the box. Option (a) also decouples the
render frame rate from the physics rate.

## Ranked shortlist

1. **Jolt via jolt-jni ≥ 6.2.0.**
   - Native rounded hulls (convex radius) fit the dice geometry exactly.
   - It is the only candidate with documented, enabled cross-platform determinism. Use the default
     `Windows64`/`Linux64` natives, *not* `_avx2`/`_fma`.
   - It has a single-threaded job system, state save/restore, sleep, and CCD.
   - It has a real JPMS module and a passing JDK 27 CI job, with very active upstream and binding.
   - Risks:
     - Single maintainer for the binding.
     - Manual or cleaner-based native memory management.
     - JNI native-access flags.
     - The API is large and C++-shaped.
2. **Bullet via Libbulletjme.**
   - Same maintainer, packaging, and JDK 27 CI as #1, with a mature engine.
   - Weaker: margin-based rounding enlarges hulls, there is no cross-platform determinism, and it has
     no `module-info`.
   - Pick this only if Jolt shows a blocking problem in a spike.
3. **PhysX via physx-jni.** Capable and maintained. But only same-platform determinism, no rounded
   arbitrary hulls, CI only on JDK 17, and no Windows ARM64 natives.
4. **ode4j.** Only if avoiding native code becomes a hard requirement. It is dormant, has no rounding
   primitive, and has a global-RNG determinism caveat.
5. **Not recommended:** JBullet (legacy), Minie (needs jMonkeyEngine), jolt-java (dead).

**Suggested next step [I]:** run a spike with jolt-jni.

- Seven rounded-hull dice in a box tray.
- Fixed 1/240 s steps, `JobSystemSingleThreaded`.
- Run the same seed twice and compare transforms bitwise.
- Measure the wall-clock time to settle a 10-dice Throw headless.
- Confirm the native-access flags under jlink/jpackage on JDK 27.

## Sources

- jolt-jni:
  - [repo](https://github.com/stephengold/jolt-jni)
  - [releases](https://github.com/stephengold/jolt-jni/releases)
  - [add guide](https://stephengold.github.io/jolt-jni-docs/jolt-jni-en/English/add.html)
  - [build.gradle](https://github.com/stephengold/jolt-jni/blob/master/build.gradle)
  - [module-info.java](https://github.com/stephengold/jolt-jni/blob/master/src/main/java/module-info.java)
  - [CI](https://github.com/stephengold/jolt-jni/blob/master/.github/workflows/push.yml)
- Jolt Physics:
  - [docs](https://jrouwe.github.io/JoltPhysics/index.html)
  - [ConvexHullShape.h](https://github.com/jrouwe/JoltPhysics/blob/master/Jolt/Physics/Collision/Shape/ConvexHullShape.h)
  - [PhysicsSettings.h](https://github.com/jrouwe/JoltPhysics/blob/master/Jolt/Physics/PhysicsSettings.h)
  - [ReleaseNotes.md](https://github.com/jrouwe/JoltPhysics/blob/master/Docs/ReleaseNotes.md)
- Libbulletjme:
  - [repo](https://github.com/stephengold/Libbulletjme)
  - [releases](https://github.com/stephengold/Libbulletjme/releases)
  - [add guide](https://stephengold.github.io/Libbulletjme/lbj-en/English/add.html)
  - [CollisionSpace.java](https://github.com/stephengold/Libbulletjme/blob/master/src/main/java/com/jme3/bullet/CollisionSpace.java)
  - [PhysicsSpace.java](https://github.com/stephengold/Libbulletjme/blob/master/src/main/java/com/jme3/bullet/PhysicsSpace.java)
- Minie:
  - [repo](https://github.com/stephengold/Minie)
  - [shape tutorial](https://stephengold.github.io/Minie/minie/minie-library-tutorials/shape.html)
- jME hub, "Nondeterminism in Bullet?":
  - [p.1](https://hub.jmonkeyengine.org/t/nondeterminism-in-bullet/43396)
  - [p.2](https://hub.jmonkeyengine.org/t/nondeterminism-in-bullet/43396?page=2)
- bullet3 issues:
  - [#4722](https://github.com/bulletphysics/bullet3/issues/4722)
  - [#127](https://github.com/bulletphysics/bullet3/issues/127)
- physx-jni:
  - [repo](https://github.com/fabmax/physx-jni)
  - [PxGeometry.idl](https://github.com/fabmax/physx-jni/blob/main/physx-jni/src/main/webidl/geometry/PxGeometry.idl)
- PhysX:
  - [Rigid Body Dynamics (determinism, sleeping)](https://nvidia-omniverse.github.io/PhysX/physx/5.6.1/docs/RigidBodyDynamics.html)
- ode4j:
  - [repo](https://github.com/tzaeschke/ode4j)
  - [Misc.java](https://github.com/tzaeschke/ode4j/blob/master/core/src/main/java/org/ode4j/ode/internal/Misc.java)
- JBullet:
  - [stephengold/jbullet](https://github.com/stephengold/jbullet)
- OpenJDK:
  - [JDK 27](https://openjdk.org/projects/jdk/27/)
  - [JEP 472](https://openjdk.org/jeps/472)
  - [JEP 306](https://openjdk.org/jeps/306)
