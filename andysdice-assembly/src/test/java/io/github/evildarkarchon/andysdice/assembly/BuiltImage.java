package io.github.evildarkarchon.andysdice.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The app-image the build just made, and runs of its launcher, for the {@code *ImageIT} tests (ADR-0005).
 *
 * <p>The windows-image profile hands its locations to Failsafe as system properties. Each test runs its own
 * copy of the image, made in the build's {@code image-tests} folder, so every run starts with no {@code data\}
 * folder, the built image (and so the zip) never gets one, and the launcher is shown to run from anywhere.
 */
final class BuiltImage {

    /** The launcher's file name (ADR-0004). */
    static final String LAUNCHER = "AndysDice.exe";

    private BuiltImage() {}

    /**
     * The built image's folder, holding {@code AndysDice.exe}, {@code app\} and {@code runtime\}.
     *
     * @return its path
     */
    static Path dir() {
        return Path.of(property("andysdice.image.dir"));
    }

    /**
     * Reads a system property the build sets, failing the test (never skipping it) when it is missing.
     *
     * @param name the property's name
     * @return its value
     */
    static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            fail("-D" + name + " isn't set: the *ImageIT tests run only in the assembly's windows-image profile");
        }
        return value;
    }

    /**
     * Copies the built image into a fresh folder of its own.
     *
     * @param name the copy's folder name, unique per test
     * @return the copy's image folder
     * @throws IOException if the copy fails
     */
    static Path copy(String name) throws IOException {
        Path target = Path.of(property("andysdice.image.work")).resolve(name).resolve("AndysDice");
        deleteTree(target.getParent());
        Path source = dir();
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path to = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(file, to, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        assertThat(target.resolve("data")).as("a fresh copy has no data folder").doesNotExist();
        return target;
    }

    /**
     * Runs an image's launcher and waits for it to exit.
     *
     * @param image the image folder
     * @param timeout how long it may run before it is killed and the test fails
     * @param args the command line
     * @return the finished run
     * @throws IOException if it can't be started or its output can't be read
     * @throws InterruptedException if the wait is interrupted
     */
    static Run run(Path image, Duration timeout, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(image.resolve(LAUNCHER).toString());
        command.addAll(List.of(args));
        Path out = image.resolveSibling("stdout.txt");
        Path err = image.resolveSibling("stderr.txt");
        // A neutral working folder, so nothing can pass by finding its files relative to the image.
        Process process = new ProcessBuilder(command).directory(image.getParent().toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly().waitFor();
            fail(LAUNCHER + " " + String.join(" ", args) + " didn't exit within " + timeout + "; its log:\n"
                    + Run.read(image.resolve("data").resolve("logs").resolve("andysdice.log")));
        }
        return new Run(image, process.exitValue(), Run.read(out), Run.read(err));
    }

    /** Deletes a folder and everything in it, if it exists. */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /**
     * One finished run of the launcher.
     *
     * @param image the image folder it ran from
     * @param exitStatus its exit status
     * @param stdout what it printed to standard output
     * @param stderr what it printed to standard error, which is where the JVM's own start-up warnings go
     */
    record Run(Path image, int exitStatus, String stdout, String stderr) {

        /**
         * The log file in {@code data\} beside the launcher.
         *
         * @return its path
         */
        Path logFile() {
            return image.resolve("data").resolve("logs").resolve("andysdice.log");
        }

        /**
         * The log file's text.
         *
         * @return it, or "" if there is no log file
         * @throws IOException if it can't be read
         */
        String log() throws IOException {
            return read(logFile());
        }

        /**
         * What a reader needs to see when a check on this run fails.
         *
         * @return the exit status, the standard error and the log
         */
        String describe() {
            try {
                return "exit status " + exitStatus + "\n--- stderr\n" + stderr + "\n--- log\n" + log();
            } catch (IOException e) {
                return "exit status " + exitStatus + "\n--- stderr\n" + stderr + "\n--- log unreadable: " + e;
            }
        }

        private static String read(Path file) throws IOException {
            if (!Files.exists(file)) {
                return "";
            }
            // The log is UTF-8; the console streams use the platform's native charset, which may not be. Decoding
            // the bytes (rather than Files.readString) replaces anything malformed instead of throwing.
            Charset charset = file.getFileName().toString().endsWith(".log") ? StandardCharsets.UTF_8
                    : Charset.forName(System.getProperty("native.encoding"), StandardCharsets.UTF_8);
            return new String(Files.readAllBytes(file), charset);
        }
    }
}
