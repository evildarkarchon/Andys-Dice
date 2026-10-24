# v1 ships as a portable, unsigned Windows app-image

v1 is published only for Windows x64. It ships as a zipped `jpackage --type app-image`: a folder holding `AndysDice.exe`, `app\` and a `jlink`ed `runtime\`. There is no installer and no code signing. The user unzips the folder anywhere, and deleting the folder uninstalls it. The app is a single-user hobby tool, so an installer, a WiX toolchain and a signing certificate or subscription cost more than they return. The build stays mac- and Linux-capable: there is no Windows-only code, ADR-0003 already lists the native flavours for every platform, and packaging sits in an OS-activated Maven profile. Publishing macOS and Linux builds is someone else's job and outside this effort.

## Considered Options

- **Installers (MSI, EXE, DMG, DEB).** They need a per-platform toolchain and add install and uninstall state. The project doesn't want either.
- **A plain `jlink` image with a `.cmd` launcher.** It has no real exe or icon, and it needs `javaw` to avoid a console window.
- **Signing with Azure Trusted Signing or an Authenticode certificate.** Signing is the only way past Smart App Control. It was rejected for v1 on cost and identity-verification grounds. A CI step can add it later without changing the artifact.
- **JavaFX linked from its Maven Central jars.** Those jars carry their DLLs as resources. In a `jrt:` image, JavaFX's `NativeLibLoader` then finds nothing in `runtime\bin\javafx` and extracts the DLLs to `~/.openjfx/cache`, which leaves files outside the portable folder. Copying the DLLs into `bin\javafx` by hand would work, but it is untested and leaves a second copy of each DLL inside `lib\modules`.

## Consequences

- **Smart App Control.** An unsigned exe gets SmartScreen's "Run anyway" prompt on first launch. On a Windows 11 machine where Smart App Control is enforcing, it is blocked outright, and there is no per-app exception. The release notes state both as known v1 limitations.
- **JavaFX from the official jmods.** The assembly module downloads Gluon's `openjfx-<version>_windows-x64_bin-jmods.zip`, verified against a pinned SHA-256 and kept at the same version as the JavaFX Maven dependency. `jlink` places JavaFX's DLLs in `runtime\bin\javafx`, which is the first place JavaFX looks. Compiling and dev runs still use the Maven jars.
- **One natives directory.** The `ReleaseSp` Jolt native and the LWJGL natives are unpacked at build time into a flat `app\natives`. The LWJGL natives jars never go on the module path, because LWJGL extracts any native it finds as a module resource. LWJGL loads its own natives through `-Dorg.lwjgl.librarypath`. Our loader `System.load`s only Jolt's native, from the directory named by an app-owned property. This corrects ADR-0003, which had our loader load LWJGL's natives too.
- **One `jpackage` call.** The assembly module is the only Maven module that knows about packaging. It stages our modules, the third-party class jars and the JavaFX jmods. A single `jpackage --type app-image`, run through `org.panteleyev:jpackage-maven-plugin` on the JDK 27 toolchain, then links and packages them. `jlink` resolves JDK modules from the main module's `requires` and runs with `--strip-debug --no-header-files --no-man-pages --compress=zip-6`. It does not use `--bind-services`.
- **Launcher options.** The launcher's `.cfg` carries:
  - `--enable-native-access` for `org.lwjgl`, jolt-jni's module, our loader module and `javafx.graphics`
  - `--add-exports=java.base/jdk.internal.misc=org.lwjgl`, never `ALL-UNNAMED`
  - `-Djoml.nounsafe=true`
  - the two natives-directory properties, both set to `$APPDIR\natives`
  - `--illegal-native-access=deny`

  `deny` now applies to shipped builds as well, not only dev and tests: the bundled JDK never changes under the user, so a build that runs at release keeps running. The parent POM defines this flag set once. Dev runs, through `exec-maven-plugin`, and tests, through Surefire's `argLine`, reuse it with a build-time `target\natives` directory, so no environment ever extracts natives at runtime.
- **Writable state beside the app.** Logs and any later persisted state go to a `data\` folder beside the launcher. If that folder isn't writable (for example, when unzipped into `Program Files`), the app falls back to `%LOCALAPPDATA%\AndysDice`. The log records which location was chosen. On macOS, App Translocation would always force the fallback, to `~/Library/Application Support`.
- **Naming and releases.** The window and display name is "Andy's Dice". The launcher, folder and file-system name is `AndysDice`, because the launcher exe can't be renamed: its `.cfg` is looked up by the exe's base name. The zip is `AndysDice-<version>-windows-x64.zip`, with a SemVer version taken from the POM. A GitHub Actions job on a `windows-latest` runner builds it when a `v*` tag is pushed. The job fails if the tag isn't `v` plus the POM version, then attaches the zip to a GitHub Release. Locally, the same build is one Maven command.
- **Notices and licence.** `jlink` brings the JDK and JavaFX notices into `runtime\legal\`. The assembly module copies the LWJGL, Jolt/jolt-jni and JOML notices into `app\legal\`, and the build fails if a bundled third-party artifact has no notice. Andy's Dice itself is MIT-licensed. The app icon is a still from our own d20 renderer, packed into a committed `.ico`. Until the renderer exists, the build uses `jpackage`'s default icon.
