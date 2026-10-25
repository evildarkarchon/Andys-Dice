package io.github.evildarkarchon.andysdice.core.tray;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * Reads {@code .tray} files (ADR-0010), under ADR-0009's rules for {@code .diceset} files: a flat
 * {@code java.util.Properties} file in UTF-8 with a {@code format = 1} key, whose identity is its file name without
 * the extension. An unknown key is ignored, and an invalid value falls back to that key's default without
 * discarding the rest. A missing, malformed or newer {@code format} rejects the whole file.
 *
 * <p>The keys (spec §6 leaves their names to the build), each value trimmed, kinds in either case:
 *
 * <ul>
 *   <li>{@code name}: what the Player sees; default the identity
 *   <li>{@code outline}: {@code rectangle}, {@code hexagon} or {@code oval}; default {@code rectangle}
 *   <li>{@code walls.profile}: {@code square} or {@code padded}; default {@code square}
 *   <li>{@code walls.material}: {@code wood} or {@code leather}; default {@code wood}
 *   <li>{@code walls.color}: sRGB {@code #rrggbb}; default walnut {@code #957355}
 *   <li>{@code walls.height}: in mm, {@value Tray#MIN_WALL_HEIGHT_MM} to {@value Tray#MAX_WALL_HEIGHT_MM}; default
 *       30
 *   <li>{@code floor.surface}: {@code felt}, {@code leather} or {@code wood}; default {@code felt}
 *   <li>{@code floor.color}: sRGB {@code #rrggbb}; default green felt {@code #305b40}
 * </ul>
 *
 * <p>The defaults are the default Tray's look (spec Appendix C), so a file that sets nothing but its format is green
 * felt in walnut. The Footprint and the wall thickness are fixed and have no keys.
 */
public final class TrayFile {

    /** The {@code format} this build reads and writes. */
    public static final int FORMAT = 1;

    /** The file name extension of a Tray file, dot included. */
    public static final String EXTENSION = ".tray";

    /** The tray prototype's walnut, linear (0.30, 0.17, 0.09), in sRGB. */
    private static final TrayColour WALNUT = new TrayColour(0x95, 0x73, 0x55);
    /** The tray prototype's green felt, linear (0.030, 0.105, 0.052), in sRGB. */
    private static final TrayColour GREEN_FELT = new TrayColour(0x30, 0x5b, 0x40);

    private TrayFile() {}

    /**
     * Reads a Tray file, as UTF-8.
     *
     * @param file the file; its name, less {@value #EXTENSION}, is the Tray's identity
     * @return the Tray
     * @throws IOException if the file can't be read
     * @throws TrayFormatException if its {@code format} is missing, malformed or newer than {@value #FORMAT}
     */
    public static Tray read(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String identity = fileName.endsWith(EXTENSION)
                ? fileName.substring(0, fileName.length() - EXTENSION.length())
                : fileName;
        return parse(identity, Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Parses a Tray file's text.
     *
     * @param identity the Tray's identity: its file name without {@value #EXTENSION}
     * @param text the file's contents
     * @return the Tray
     * @throws TrayFormatException if its {@code format} is missing, malformed or newer than {@value #FORMAT}
     * @throws IllegalArgumentException if the identity is blank
     */
    public static Tray parse(String identity, String text) throws TrayFormatException {
        Properties keys = new Properties();
        try (Reader reader = new StringReader(text)) {
            keys.load(reader);
        } catch (IOException e) {
            // A StringReader never fails to read.
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            // Properties.load's only complaint about the text itself: a malformed backslash-u escape.
            throw new TrayFormatException("The Tray " + identity + " isn't a properties file: " + e.getMessage());
        }
        checkFormat(identity, keys.getProperty("format"));

        String name = value(keys, "name").filter(s -> !s.isEmpty()).orElse(identity);
        TrayOutline outline = kind(keys, "outline", TrayOutline.class).orElse(TrayOutline.RECTANGLE);
        Tray.Walls walls = new Tray.Walls(
                kind(keys, "walls.profile", WallProfile.class).orElse(WallProfile.SQUARE),
                kind(keys, "walls.material", WallMaterial.class).orElse(WallMaterial.WOOD),
                colour(keys, "walls.color").orElse(WALNUT),
                value(keys, "walls.height").flatMap(TrayFile::number)
                        .filter(h -> h >= Tray.MIN_WALL_HEIGHT_MM && h <= Tray.MAX_WALL_HEIGHT_MM)
                        .orElse(Tray.MIN_WALL_HEIGHT_MM));
        Tray.Floor floor = new Tray.Floor(
                kind(keys, "floor.surface", FloorSurface.class).orElse(FloorSurface.FELT),
                colour(keys, "floor.color").orElse(GREEN_FELT));
        return new Tray(identity, name, outline, walls, floor);
    }

    /** Rejects a file whose format this build can't read: missing, not a whole number, below 1, or newer. */
    private static void checkFormat(String identity, String format) throws TrayFormatException {
        if (format == null) {
            throw new TrayFormatException("The Tray " + identity + " has no format key");
        }
        int version;
        try {
            version = Integer.parseInt(format.trim());
        } catch (NumberFormatException e) {
            throw new TrayFormatException("The Tray " + identity + " has a malformed format: " + format.trim());
        }
        if (version < 1) {
            throw new TrayFormatException("The Tray " + identity + " has a malformed format: " + version);
        }
        if (version > FORMAT) {
            throw new TrayFormatException("The Tray " + identity + " is format " + version
                    + ", newer than this version of Andy's Dice reads (" + FORMAT + ")");
        }
    }

    /** A key's trimmed value, if the file has the key. */
    private static Optional<String> value(Properties keys, String key) {
        return Optional.ofNullable(keys.getProperty(key)).map(String::strip);
    }

    /** A key's value as a constant of an enum, named in either case; empty if absent or not one of them. */
    private static <E extends Enum<E>> Optional<E> kind(Properties keys, String key, Class<E> kinds) {
        Function<String, Optional<E>> lookUp = word -> {
            for (E constant : kinds.getEnumConstants()) {
                if (constant.name().toLowerCase(Locale.ROOT).equals(word.toLowerCase(Locale.ROOT))) {
                    return Optional.of(constant);
                }
            }
            return Optional.empty();
        };
        return value(keys, key).flatMap(lookUp);
    }

    /** A key's value as an sRGB colour; empty if absent or not {@code #rrggbb}. */
    private static Optional<TrayColour> colour(Properties keys, String key) {
        return value(keys, key).flatMap(TrayColour::parse);
    }

    /** A finite decimal number; empty for anything else, NaN and the infinities included. */
    private static Optional<Double> number(String text) {
        try {
            double d = Double.parseDouble(text);
            return Double.isFinite(d) ? Optional.of(d) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
