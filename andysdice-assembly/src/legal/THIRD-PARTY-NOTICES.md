# Andy's Dice ${project.version}: licence and third-party notices

Andy's Dice is free software under the MIT licence; its text is in `LICENSE` in this folder.

The app bundles the software and sounds below. Each row names what it ships as in this folder (`app\`) or in
`runtime\`, its licence, and the file in this folder that holds that licence's full text. The Java runtime's and
JavaFX's own notices are where `jlink` put them, one folder per module in `runtime\legal\`.

| Component | Version | Licence | Bundled as | Notice |
|---|---|---|---|---|
| OpenJDK (the linked Java runtime in `runtime\`) | ${java.version} | GPL-2.0 with the Classpath Exception | `java.base`, `java.datatransfer`, `java.desktop`, `java.logging`, `java.prefs`, `java.xml` | `openjdk.md` |
| OpenJFX (JavaFX), from Gluon's jmods | ${javafx.version} | GPL-2.0 with the Classpath Exception | `javafx.base`, `javafx.graphics`, `javafx.controls` | `javafx.md` |
| LWJGL (Lightweight Java Game Library) | ${lwjgl.version} | BSD-3-Clause | `org.lwjgl`, `org.lwjgl.opengl`, `lwjgl.dll`, `lwjgl_opengl.dll` | `lwjgl-LICENSE.md` |
| libffi, built into LWJGL's `lwjgl.dll` | as in LWJGL ${lwjgl.version} | MIT | `lwjgl.dll` | `lwjgl-libffi-LICENSE.txt` |
| Khronos OpenGL registry, from which LWJGL's OpenGL bindings are generated | as in LWJGL ${lwjgl.version} | MIT-style (Khronos) | `org.lwjgl.opengl`, `lwjgl_opengl.dll` | `lwjgl-khronos-LICENSE.txt` |
| Jolt JNI | ${jolt-jni.version} | MIT | `com.github.stephengold.joltjni`, `joltjni.dll` (the ReleaseSp build) | `jolt-jni-LICENSE.txt` |
| Jolt Physics, built into `joltjni.dll` | Stephen Gold's fork, tag `sg261013`, as in Jolt JNI ${jolt-jni.version} | MIT | `joltjni.dll` | `jolt-physics-LICENSE.txt` |
| V-HACD, built into `joltjni.dll` | 4.1.0, as in Jolt JNI ${jolt-jni.version} | BSD-3-Clause | `joltjni.dll` | `v-hacd-LICENSE.txt` |
| JOML (Java OpenGL Math Library) | ${joml.version} | MIT | `org.joml` | `joml-LICENSE.txt` |
| The dice sounds (the sample bank inside the app's sound module) | as listed | CC0 1.0 | `io.github.evildarkarchon.andysdice.sound` | `SOURCES.md` |
