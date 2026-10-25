package io.github.evildarkarchon.andysdice.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Where the app keeps its writable state (spec §3.6, ADR-0004): the log now, and persisted state later.
 *
 * <p>It is {@code data\} beside the launcher, so the unzipped folder stays portable, or
 * {@code %LOCALAPPDATA%\AndysDice} when that folder can't be written (unzipped into {@code Program Files}, say).
 * The system property {@value #OVERRIDE_PROPERTY} overrides both for dev runs and tests. The app chooses once,
 * at start-up, and the log records the choice and why.
 *
 * @param folder the chosen folder, absolute
 * @param reason why it was chosen, in words for the log
 */
record DataFolder(Path folder, String reason) {

    /** The app-owned override of the data folder, for dev runs and tests. */
    static final String OVERRIDE_PROPERTY = "andysdice.data.dir";

    /** Set by every {@code jpackage} launcher to the launcher's own path. Absent in dev runs. */
    static final String LAUNCHER_PROPERTY = "jpackage.app-path";

    /** The file-system name (ADR-0004), used for the fallback folder. */
    private static final String APP_FOLDER = "AndysDice";

    /** Checks both parts are present. */
    DataFolder {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * Chooses the data folder for this run from the real system properties and environment.
     *
     * @return the choice
     */
    static DataFolder choose() {
        Map<String, String> properties = new HashMap<>();
        System.getProperties().stringPropertyNames().forEach(name -> properties.put(name, System.getProperty(name)));
        return choose(properties, System.getenv(), DataFolder::isWritable);
    }

    /**
     * Chooses the data folder: the override if set, else {@code data} beside the launcher if it is writable,
     * else the per-user fallback. The fallback is chosen even if it can't be written either; the log's own
     * set-up then reports that.
     *
     * @param properties the system properties to read ({@value #OVERRIDE_PROPERTY}, {@value #LAUNCHER_PROPERTY},
     *     {@code os.name}, {@code user.home})
     * @param environment the environment ({@code LOCALAPPDATA}, {@code XDG_DATA_HOME})
     * @param writable whether a folder can be written; it may create the folder
     * @return the choice
     */
    static DataFolder choose(Map<String, String> properties, Map<String, String> environment,
            Predicate<Path> writable) {
        String override = properties.get(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return new DataFolder(Path.of(override).toAbsolutePath(), "set by -D" + OVERRIDE_PROPERTY);
        }
        Path fallback = fallback(properties, environment).toAbsolutePath();
        String launcher = properties.get(LAUNCHER_PROPERTY);
        if (launcher == null || launcher.isBlank()) {
            return new DataFolder(fallback, "the per-user folder, as there is no launcher to sit beside (a dev run)");
        }
        Path beside = Path.of(launcher).toAbsolutePath().resolveSibling("data");
        if (writable.test(beside)) {
            return new DataFolder(beside, "beside the launcher");
        }
        return new DataFolder(fallback, "the per-user folder, as " + beside + " isn't writable");
    }

    /**
     * Whether the app can keep files in a folder: it creates the folder if needed, then writes and deletes a
     * probe file, since a folder's permission bits alone don't say whether Windows will let us write there.
     *
     * @param folder the folder to try
     * @return {@code true} if a file could be created in it
     */
    static boolean isWritable(Path folder) {
        try {
            Files.createDirectories(folder);
            Path probe = Files.createTempFile(folder, ".write-probe", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }

    /** {@code %LOCALAPPDATA%\AndysDice} on Windows; the platform's per-user data folder elsewhere. */
    private static Path fallback(Map<String, String> properties, Map<String, String> environment) {
        String localAppData = environment.get("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, APP_FOLDER);
        }
        String home = properties.getOrDefault("user.home", ".");
        if (properties.getOrDefault("os.name", "").startsWith("Mac")) {
            // Where App Translocation would always send us on macOS anyway (ADR-0004).
            return Path.of(home, "Library", "Application Support", APP_FOLDER);
        }
        String xdg = environment.get("XDG_DATA_HOME");
        return xdg != null && !xdg.isBlank() ? Path.of(xdg, APP_FOLDER) : Path.of(home, ".local", "share", APP_FOLDER);
    }
}
