package io.github.evildarkarchon.andysdice.app;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The content of {@code settings.properties} (spec §3.6, ADR-0007): flat keys and their raw values, as an immutable
 * value. Each key is read through its {@link Setting}, so a bad value falls back to that key's default alone, and an
 * unknown key (one no code asks for) is ignored: kept as it is and written back, but never read.
 *
 * <p>{@link #FILE} is its format: the version header, then {@code key=value} lines escaped as {@code Properties}
 * escapes them and sorted by key. The only body it can't read is one {@code Properties.load} rejects (a malformed
 * {@code \\u} escape); that file is set aside.
 */
final class Settings {

    /** The file's name in the data folder. */
    static final String FILE_NAME = "settings.properties";

    /** The file's format; version 1. */
    static final PersistedFile<Settings> FILE = new Format();

    private static final Settings DEFAULTS = new Settings(new TreeMap<>());

    private final SortedMap<String, String> values;

    private Settings(SortedMap<String, String> values) {
        this.values = Collections.unmodifiableSortedMap(values);
    }

    /**
     * Returns settings with every key at its default.
     *
     * @return no keys at all
     */
    static Settings defaults() {
        return DEFAULTS;
    }

    /**
     * Reads one key.
     *
     * @param setting the key
     * @param <T> its value's type
     * @return its value, or its default if it is absent or can't be read
     */
    <T> T get(Setting<T> setting) {
        return setting.read(values.get(setting.key()));
    }

    /**
     * Returns these settings with one key changed.
     *
     * @param setting the key
     * @param value its new value, or {@code null} to remove the key, putting it back to its default
     * @param <T> its value's type
     * @return the changed settings; this one is unchanged
     */
    <T> Settings with(Setting<T> setting, T value) {
        SortedMap<String, String> changed = new TreeMap<>(values);
        if (value == null) {
            changed.remove(setting.key());
        } else {
            changed.put(setting.key(), setting.format(value));
        }
        return new Settings(changed);
    }

    /**
     * Returns every key's raw value, unknown keys included.
     *
     * @return a read-only map sorted by key
     */
    Map<String, String> values() {
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Settings that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "Settings" + values;
    }

    /** {@code settings.properties}, version 1: a {@code Properties} body under the header. */
    private static final class Format implements PersistedFile<Settings> {

        @Override
        public String name() {
            return FILE_NAME;
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Settings fresh() {
            return DEFAULTS;
        }

        @Override
        public Settings read(List<String> body) throws UnreadableException {
            Properties properties = new Properties();
            try {
                properties.load(new StringReader(String.join("\n", body)));
            } catch (IllegalArgumentException e) {
                throw new UnreadableException("it isn't a properties file: " + e.getMessage(), e);
            } catch (IOException e) {
                // A StringReader never fails to read.
                throw new UncheckedIOException(e);
            }
            SortedMap<String, String> values = new TreeMap<>();
            properties.stringPropertyNames().forEach(key -> values.put(key, properties.getProperty(key)));
            return new Settings(values);
        }

        @Override
        public List<String> write(Settings value) {
            Properties properties = new Properties();
            properties.putAll(Objects.requireNonNull(value, "value").values);
            StringWriter text = new StringWriter();
            try {
                // Properties escapes every key and value (line terminators included) and, since JDK 18, sorts the
                // keys; only its date comment is dropped, so an unchanged file is written byte for byte the same.
                properties.store(text, null);
            } catch (IOException e) {
                // A StringWriter never fails to write.
                throw new UncheckedIOException(e);
            }
            return text.toString().lines().filter(line -> !line.startsWith("#")).toList();
        }
    }
}
