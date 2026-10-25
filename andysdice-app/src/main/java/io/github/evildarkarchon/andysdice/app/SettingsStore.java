package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The app's live settings: {@code settings.properties} loaded once at start-up and saved through {@link Persistence}
 * whenever a key changes (ADR-0007 "rewritten when it changes"). Window geometry is the exception only in when it is
 * set: on close ({@link WindowGeometry.Tracker#save}).
 *
 * <p>Thread contract: made and used on one thread, the JavaFX Application Thread in the app (each save's snapshot is
 * taken there; the write happens on the writer thread).
 */
final class SettingsStore {

    private final Persistence persistence;
    private Settings current;

    /**
     * Loads the settings, set aside and started fresh (with a Notice) if the file can't be read.
     *
     * @param persistence where they are kept
     */
    SettingsStore(Persistence persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.current = persistence.load(Settings.FILE);
    }

    /**
     * Returns the settings as they are now.
     *
     * @return an immutable snapshot
     */
    Settings current() {
        return current;
    }

    /**
     * Reads one key.
     *
     * @param setting the key
     * @param <T> its value's type
     * @return its value, or its default
     */
    <T> T get(Setting<T> setting) {
        return current.get(setting);
    }

    /**
     * Changes one key and saves the settings, if that changes them.
     *
     * @param setting the key
     * @param value its new value, or {@code null} to put it back to its default
     * @param <T> its value's type
     */
    <T> void set(Setting<T> setting, T value) {
        update(settings -> settings.with(setting, value));
    }

    /**
     * Changes several keys in one save, if that changes anything.
     *
     * @param change makes the new settings from the current ones
     */
    void update(UnaryOperator<Settings> change) {
        Settings changed = Objects.requireNonNull(change.apply(current), "changed settings");
        if (!changed.equals(current)) {
            current = changed;
            persistence.save(Settings.FILE, changed);
        }
    }
}
