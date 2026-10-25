package io.github.evildarkarchon.andysdice.app;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * One key of {@code settings.properties} (ADR-0007): its name, its default, and how its value is read and written.
 * Declare each as a constant beside the code it configures ({@link WindowGeometry#BOUNDS}, say) and read it with
 * {@link Settings#get}; nothing needs registering, because a key no code asks for is simply never read.
 *
 * <p>A value that can't be read (malformed, out of range, an unknown choice) falls back to this key's default
 * alone; the other keys are unaffected.
 *
 * @param key the property key; dotted lower camel case by convention ({@code sound.volume})
 * @param fallback the default, used when the key is absent or its value can't be read; may be {@code null} for a
 *     key whose absence means something ("no saved bounds")
 * @param parser reads a stripped value; it may throw a {@code RuntimeException} or return {@code null} to reject it
 * @param formatter writes a value; its output is escaped by {@code Properties}, so it may hold anything
 * @param <T> the value's type, immutable
 */
record Setting<T>(String key, T fallback, Function<String, T> parser, Function<T, String> formatter) {

    /** Checks the key is set and the functions are present. */
    Setting {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(formatter, "formatter");
        if (key.isBlank()) {
            throw new IllegalArgumentException("a setting needs a key");
        }
    }

    /**
     * A {@code true}/{@code false} key, read ignoring case.
     *
     * @param key the key
     * @param fallback the default
     * @return the setting
     */
    static Setting<Boolean> flag(String key, boolean fallback) {
        return new Setting<>(key, fallback, value -> switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        }, String::valueOf);
    }

    /**
     * A whole number within a range; one outside it is a bad value.
     *
     * @param key the key
     * @param fallback the default, within the range
     * @param min the smallest value allowed
     * @param max the largest value allowed
     * @return the setting
     */
    static Setting<Integer> integer(String key, int fallback, int min, int max) {
        if (min > max || fallback < min || fallback > max) {
            throw new IllegalArgumentException(key + ": the default " + fallback + " isn't in " + min + ".." + max);
        }
        return new Setting<>(key, fallback, value -> {
            int n = Integer.parseInt(value);
            return n >= min && n <= max ? n : null;
        }, String::valueOf);
    }

    /**
     * One of an enum's constants, written by name and read ignoring case; an unknown name is a bad value.
     *
     * @param key the key
     * @param type the enum
     * @param fallback the default
     * @param <E> the enum
     * @return the setting
     */
    static <E extends Enum<E>> Setting<E> choice(String key, Class<E> type, E fallback) {
        Objects.requireNonNull(type, "type");
        return new Setting<>(key, fallback, value -> {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(value)) {
                    return constant;
                }
            }
            return null;
        }, Enum::name);
    }

    /**
     * Free text, such as a Dice Set's identity; an empty value is a bad one.
     *
     * @param key the key
     * @param fallback the default
     * @return the setting
     */
    static Setting<String> text(String key, String fallback) {
        return new Setting<>(key, fallback, value -> value.isEmpty() ? null : value, Function.identity());
    }

    /**
     * Reads a raw value as this key's.
     *
     * @param raw the value in the file, or {@code null} if the key is absent
     * @return the value, or the {@linkplain #fallback default} if it is absent or can't be read
     */
    T read(String raw) {
        if (raw == null) {
            return fallback;
        }
        try {
            T value = parser.apply(raw.strip());
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            // A bad value is the Player's (or a newer build's) business; it costs only this key.
            return fallback;
        }
    }

    /**
     * Writes a value as this key's.
     *
     * @param value the value, not {@code null}
     * @return the raw value
     */
    String format(T value) {
        return formatter.apply(Objects.requireNonNull(value, "value"));
    }
}
