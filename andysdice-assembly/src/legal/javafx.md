# OpenJFX (JavaFX)

The `javafx.base`, `javafx.graphics` and `javafx.controls` modules in `runtime\`, and JavaFX's native libraries in
`runtime\bin\javafx\`, were linked by `jlink` from Gluon's official OpenJFX jmods for Windows x64. OpenJFX is
licensed under the GNU General Public License, version 2, with the Classpath Exception.

`jlink` keeps JavaFX's notices beside the runtime, one folder per module:

- `..\..\runtime\legal\javafx.base\`: the licence and the assembly exception (the licence text itself is shared
  with the JDK's, in `..\..\runtime\legal\java.base\LICENSE`);
- `..\..\runtime\legal\javafx.graphics\`: also the notices of the third-party code inside JavaFX's graphics
  libraries.

The OpenJFX source code is at https://openjdk.org/projects/openjfx/ and https://github.com/openjdk/jfx.
