# v1 is verified by invariants, not trajectories or golden images, and GL runs in CI on Mesa

Automated tests assert on invariants: how a Throw ended, the shape of its recording, the face it settled on, whether a shader compiles, whether a frame was delivered. They never assert on exact trajectories or pixels. Physics is not deterministic (ADR-0003), so trajectories can't be pinned. Golden images change between GPU drivers, and the driving dev has a single GPU to generate them on. Everything before the GL call is pure Java and is tested exhaustively, without physics or a GPU: the Roll Expression engine, Face Value selection, the numbering rotation, mesh generation and the numeral atlas. The thin GL layer runs for real in CI. GitHub's `windows-latest` runner offers only GDI Generic OpenGL 1.1, so CI installs Mesa as a system-wide OpenGL driver, which gives a 4.6 core context. The renderer therefore takes the same native WGL path in CI as on a real machine. What only an eye can judge is left to a gallery of rendered stills and a release checklist.

## Considered Options

- **Golden-image comparison.** Rejected. Pixels differ between drivers and between Mesa versions, and there is only one GPU to generate the reference images on. Correctness of the up-face numeral is proven instead by composing three tested pieces: the solid's face table, the numbering rotation and the atlas mapping.
- **Skipping GL tests when no 3.3 context exists.** Rejected. A silent skip hides exactly the failure the tests exist to catch. A `gl`-tagged test fails without a context.
- **Pointing LWJGL at Mesa's `opengl32.dll`** (`org.lwjgl.opengl.libname`). Rejected for CI. That DLL needs its dependency `libgallium_wgl.dll` reachable through `PATH`. gdi32 may also load System32's `opengl32.dll` as a second copy in the process. The system-wide driver deploy (`ssciwr/setup-mesa-dist-win`) has neither problem and needs no app changes.
- **Jolt tests under Failsafe.** Rejected. A second plugin would need a second copy of the native-access flag set, and Jolt tests are fast. Failsafe is kept for tests that need the packaged app-image.
- **Coverage or static-analysis gates** (JaCoCo, Error Prone, SpotBugs). Rejected for v1. A coverage number invites tests written to hit it. The exhaustive and invariant tests are the real bar. `javac -Xlint:all -Werror` is the only gate.

## Consequences

- **Stack.** The stack is JUnit 6 (Jupiter) with AssertJ and parameterised tables. There is no property-based library; a small seeded generator stands in where random valid input is wanted.
- **Exhaustive geometry.** For every Die Kind, every face (or d4 vertex) that can land up, and every target numeral, the chosen symmetry must do three things. It maps the solid onto itself, it puts the target numeral up, and it preserves the layout's opposite sums and neighbours. This also proves the assumption behind ADR-0001 that each solid's rotation group can reach every face.
- **Fairness.** Mapping tests check that the generator's draw maps one-to-one onto each Die Kind's numerals. A chi-square test per Die Kind, at a fixed seed, catches a mapping that is one-to-one but skewed. Fairness is never tested against `SecureRandom`.
- **Fake simulator.** A test fixture behind ADR-0003's engine-neutral seam returns cocked or escaped N times before at rest. It proves three things: retries are silent, exceeding the cap is an error, and a fixed seed gives the same Face Value sequence whatever the retries.
- **Real Jolt, no GPU.** Scenario tests force each of the four endings and check the recording's invariants. The final pose must put the chosen face up. A soak test of many 20-Dice Throws is tagged `slow` and runs in a `soak` profile. It is also the tool for tuning the Cocked Die tolerance and the retry cap.
- **Smoke launch.** The app has a hidden `--smoke-test` flag. It starts JavaFX and logs the Prism pipeline, runs Jolt's eager start-up, creates the GL context, compiles the shaders, plays one fixed Roll Expression at speed and exits. Failsafe in the assembly module runs the *built* `AndysDice.exe` with it. The run must exit 0, must write its log to `data\`, must log no native-access warnings, and must not create `~/.openjfx/cache`. A second CI job runs the image without Mesa. It must exit non-zero with a log line naming the GL 3.3 requirement and the detected renderer, which verifies ADR-0002's fail-fast rule.
- **CI shape.**
  - **On every push and PR (`windows-latest`, pinned Mesa):** `mvn verify` runs the unit, Jolt, `gl` and smoke tests. The no-Mesa fail-fast job runs alongside it. An `ubuntu-latest` job runs compile, pure and Jolt tests only, which keeps "Linux-capable" (ADR-0004) checked.
  - **Nightly:** the `soak` profile.
  - **On a `v*` tag:** the release job runs all of the above plus `soak` before attaching the zip.
- **By eye.**
  - **Gallery.** A dev-only mode renders stills of each Die Kind × Dice Set × fixed poses into `target\gallery`.
  - **Release checklist.** A checklist in `docs/` is worked by hand before every `v*` tag. It covers legible numerals that match the Roll Result, no interpenetration, no visibly Cocked Die, shadows and reflections, resizing without hangs, the quality levers, and the first launch from the unzipped folder.
