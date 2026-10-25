package io.github.evildarkarchon.andysdice.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * The built app-image and zip, as files (ADR-0004): the layout, the one flat natives folder with jolt-jni's
 * default {@code ReleaseSp} native, JavaFX linked from Gluon's jmods, and the launcher's JVM options being the
 * build's shared option set with {@code --illegal-native-access=deny}. Nothing here runs the app; the smoke tests
 * do that.
 */
class PackagedImageIT {

    private final Path image = BuiltImage.dir();

    @Test
    void theZipIsNamedForTheVersionAndHoldsTheImageFolder() throws IOException {
        Path zip = Path.of(BuiltImage.property("andysdice.image.zip"));
        String version = BuiltImage.property("andysdice.version");

        assertThat(zip).isRegularFile();
        assertThat(zip.getFileName()).hasToString("AndysDice-" + version + "-windows-x64.zip");
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            List<String> names = archive.stream().map(ZipEntry::getName).toList();
            assertThat(names).contains("AndysDice/AndysDice.exe", "AndysDice/app/AndysDice.cfg",
                    "AndysDice/app/natives/joltjni.dll", "AndysDice/app/natives/lwjgl.dll",
                    "AndysDice/app/natives/lwjgl_opengl.dll", "AndysDice/runtime/release");
            assertThat(names).allMatch(name -> name.startsWith("AndysDice/"), "is inside the AndysDice folder");
            assertThat(names).noneMatch(name -> name.startsWith("AndysDice/data/"));
        }
    }

    @Test
    void theImageHasTheLauncherTheAppAndTheLinkedRuntime() {
        assertThat(image.resolve("AndysDice.exe")).isRegularFile();
        assertThat(image.resolve("app").resolve("AndysDice.cfg")).isRegularFile();
        assertThat(image.resolve("runtime").resolve("release")).isRegularFile();
        // jlink's --no-header-files (ADR-0004).
        assertThat(image.resolve("runtime").resolve("include")).doesNotExist();
    }

    /** One flat folder, holding exactly Jolt's native and LWJGL's two: nothing nested, nothing else. */
    @Test
    void theNativesAreFlatInAppNatives() throws IOException {
        try (Stream<Path> files = Files.list(image.resolve("app").resolve("natives"))) {
            assertThat(files.map(file -> file.getFileName().toString()))
                    .containsExactlyInAnyOrder("joltjni.dll", "lwjgl.dll", "lwjgl_opengl.dll");
        }
    }

    /** The default flavour, byte for byte (never {@code _avx2} or {@code _fma}: ADR-0003, ADR-0004). */
    @Test
    void joltsNativeIsTheReleaseSpFlavour() throws IOException {
        String version = BuiltImage.property("andysdice.image.joltVersion");
        Path jar = Path.of(BuiltImage.property("andysdice.image.mavenRepo"), "com", "github", "stephengold",
                "jolt-jni-Windows64", version, "jolt-jni-Windows64-" + version + "-ReleaseSp.jar");
        byte[] releaseSp;
        try (ZipFile archive = new ZipFile(jar.toFile());
                InputStream in = archive.getInputStream(
                        archive.getEntry("windows/x86-64/com/github/stephengold/joltjni.dll"))) {
            releaseSp = in.readAllBytes();
        }

        assertThat(Files.readAllBytes(image.resolve("app").resolve("natives").resolve("joltjni.dll")))
                .isEqualTo(releaseSp);
    }

    /**
     * No natives jar is anywhere in the image: LWJGL would extract a native it found as a module resource
     * (ADR-0004), and JavaFX's Maven jars, which carry their DLLs, stay out in favour of the jmods.
     */
    @Test
    void noJarCarryingNativesIsLinked() throws IOException {
        String modules = Files.readString(image.resolve("runtime").resolve("release"));

        assertThat(modules).contains("javafx.graphics", "org.lwjgl.opengl", "com.github.stephengold.joltjni",
                "io.github.evildarkarchon.andysdice.app", "java.logging");
        assertThat(modules).doesNotContain("org.lwjgl.natives");
        try (Stream<Path> files = Files.walk(image)) {
            // The runtime's own jrt-fs.jar and the jmods' javafx-swt.jar carry no natives and may stay.
            assertThat(files.map(file -> file.getFileName().toString()))
                    .noneMatch(name -> name.matches("(?i).*natives.*\\.jar|javafx-(base|graphics|controls).*\\.jar"));
        }
    }

    /** jlink put JavaFX's DLLs where JavaFX looks first, so it never extracts them to ~/.openjfx/cache. */
    @Test
    void javaFxsNativesComeFromTheJmods() {
        assertThat(image.resolve("runtime").resolve("bin").resolve("javafx").resolve("glass.dll")).isRegularFile();
    }

    /**
     * The launcher's JVM options are the build's shared option set (andysdice.nativeAccess, lwjglOptions,
     * joltNatives, jomlOptions) in order, one per entry, with both natives directories at {@code $APPDIR\natives}.
     */
    @Test
    void theLauncherCarriesTheSharedOptionSetUnderDeny() throws IOException {
        List<String> options = new ArrayList<>();
        for (String line : Files.readAllLines(image.resolve("app").resolve("AndysDice.cfg"))) {
            if (line.startsWith("java-options=") && !line.startsWith("java-options=-Djpackage.app-version=")) {
                options.add(line.substring("java-options=".length()));
            }
        }

        assertThat(options).containsExactlyElementsOf(
                Arrays.asList(BuiltImage.property("andysdice.image.javaOptions").trim().split("\\s+")));
        assertThat(options).contains("--illegal-native-access=deny",
                "--enable-native-access=com.github.stephengold.joltjni,org.lwjgl,"
                        + "io.github.evildarkarchon.andysdice.physics,javafx.graphics",
                "-Dorg.lwjgl.librarypath=$APPDIR\\natives", "-Dandysdice.natives.dir=$APPDIR\\natives");
    }
}
