package io.github.evildarkarchon.andysdice.physics;

import com.github.stephengold.joltjni.Jolt;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Our native loader (ADR-0003, ADR-0004): it {@code System.load}s jolt-jni's native from the natives directory
 * and runs Jolt's process-wide setup, once per process.
 *
 * <p>This is the only class of ours that makes a restricted call, which is why the build's native-access set
 * grants this module. LWJGL loads its own natives; this loads only Jolt's.
 */
final class JoltLoader {

    /**
     * The natives directory, holding Jolt's native under its platform name ({@code joltjni.dll},
     * {@code libjoltjni.so}). The launcher sets it to {@code $APPDIR\natives}; dev and test runs to the
     * build's {@code target/natives}.
     */
    static final String NATIVES_DIR_PROPERTY = "andysdice.natives.dir";

    /** A dev override naming Jolt's native library file itself, e.g. a DebugSp build; wins over the directory. */
    static final String LIBRARY_OVERRIDE_PROPERTY = "andysdice.jolt.library";

    /** The thread that ran Jolt's setup, or {@code null} before it has run. Guarded by the class lock. */
    private static Thread startedOn;

    private JoltLoader() {
    }

    /**
     * Works out which file to load from the given system properties.
     *
     * @param properties the system properties to read
     * @return the absolute path of Jolt's native library
     * @throws JoltStartupException if neither property is set
     */
    static Path libraryPath(Properties properties) {
        String override = properties.getProperty(LIBRARY_OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        String dir = properties.getProperty(NATIVES_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            throw new JoltStartupException("Can't find " + library() + " for " + platform() + ": set -D"
                    + NATIVES_DIR_PROPERTY + " to the natives directory (or -D" + LIBRARY_OVERRIDE_PROPERTY
                    + " to the library file)");
        }
        return Path.of(dir).toAbsolutePath().resolve(System.mapLibraryName("joltjni"));
    }

    /**
     * Loads Jolt's native library from a file.
     *
     * @param library the library's path
     * @throws JoltStartupException if the file is missing or won't load
     */
    @SuppressWarnings("restricted") // System.load is the loader's whole job; the build grants this module
    static void load(Path library) {
        if (!Files.isRegularFile(library)) {
            throw new JoltStartupException("Can't start Jolt Physics on " + platform() + ": " + library()
                    + " isn't at " + library);
        }
        try {
            System.load(library.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError | IllegalCallerException e) {
            throw new JoltStartupException("Can't start Jolt Physics on " + platform() + ": " + library()
                    + " at " + library + " wouldn't load: " + e.getMessage(), e);
        }
    }

    /**
     * Loads Jolt's native and runs its process-wide setup (allocator, callbacks, factory, type registration),
     * unless that has already happened in this process. Call it on the physics worker (spec §5).
     *
     * <p>jolt-jni's cleaner thread is deliberately not started (ADR-0003): every native object is freed
     * explicitly, so no native state outlives an Attempt.
     *
     * @throws JoltStartupException if Jolt can't be started
     */
    static synchronized void start() {
        if (startedOn != null) {
            return;
        }
        load(libraryPath(System.getProperties()));
        try {
            Jolt.registerDefaultAllocator();
            Jolt.installDefaultAssertCallback();
            // Jolt's trace output goes to the app log (spec §6), not stdout.
            Jolt.installJavaTraceCallback(new PrintStream(new LoggingStream(), true, StandardCharsets.UTF_8));
            if (!Jolt.newFactory()) {
                throw new JoltStartupException("Can't start Jolt Physics on " + platform()
                        + ": its factory already existed");
            }
            Jolt.registerTypes();
        } catch (UnsatisfiedLinkError e) {
            throw new JoltStartupException("Can't start Jolt Physics on " + platform() + ": " + library()
                    + " doesn't match jolt-jni's classes: " + e.getMessage(), e);
        }
        startedOn = Thread.currentThread();
    }

    /**
     * Returns the thread Jolt's setup ran on.
     *
     * @return the thread, or {@code null} if Jolt hasn't been started in this process
     */
    static synchronized Thread startedOn() {
        return startedOn;
    }

    /** The platform as the failure messages name it. */
    private static String platform() {
        return System.getProperty("os.name") + " " + System.getProperty("os.arch");
    }

    /** The library as the failure messages name it. */
    private static String library() {
        return "jolt-jni's native library " + System.mapLibraryName("joltjni");
    }

    /** Collects Jolt's trace output into lines and logs each one. Only Jolt's native code writes to it. */
    private static final class LoggingStream extends OutputStream {

        private static final System.Logger LOG = System.getLogger("io.github.evildarkarchon.andysdice.physics.Jolt");

        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                line.write(b);
            }
        }

        @Override
        public synchronized void flush() {
            flushLine();
        }

        private void flushLine() {
            if (line.size() > 0) {
                LOG.log(System.Logger.Level.INFO, line.toString(StandardCharsets.UTF_8));
                line.reset();
            }
        }
    }
}
