package io.github.evildarkarchon.andysdice.core.diceset;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads {@code .diceset} files (ADR-0009): flat {@code java.util.Properties} in UTF-8 with a {@code format} key,
 * colours as sRGB {@code #rrggbb}. In the spirit of ADR-0007's {@code settings.properties}, an unknown key is
 * ignored and an invalid value (not a number, not {@code #rrggbb}, or outside its key's range) falls back to that
 * key's default without discarding the rest; only a missing, malformed or newer {@code format} rejects the file.
 *
 * <p>The keys, defaults and ranges are the materials prototype's ({@code DiceSet.java} at {@code 2f808fe}). The
 * prototype clamped an out-of-range number; here it is invalid and takes the default, as ADR-0009 says. The
 * format is a Player-facing contract: a new key needs a default that leaves existing files looking the same.
 */
public final class DiceSetFile {

    /** The {@code format} this build reads and its built-in files carry. */
    public static final int FORMAT = 1;

    /** The extension of a Dice Set file; the rest of the file's name is the Dice Set's identity. */
    public static final String EXTENSION = ".diceset";

    private DiceSetFile() {}

    /**
     * Reads a Dice Set file. Its identity is its file name without {@value #EXTENSION}.
     *
     * @param file the file, UTF-8
     * @return the Dice Set
     * @throws DiceSetFormatException if its {@code format} is missing, malformed or newer than {@value #FORMAT}
     * @throws IOException if it can't be read
     */
    public static DiceSet read(Path file) throws IOException {
        String name = file.getFileName().toString();
        String identity = name.endsWith(EXTENSION) ? name.substring(0, name.length() - EXTENSION.length()) : name;
        return parse(identity, Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Parses a Dice Set file's text. Never fails over a value, only over the format.
     *
     * @param identity the Dice Set's identity, its file name without {@value #EXTENSION}
     * @param text the file's text
     * @return the Dice Set, every bad or missing value replaced by its default; with no {@code name}, the
     *     identity stands in for it
     * @throws DiceSetFormatException if the text isn't a properties file, or its {@code format} is missing,
     *     malformed or newer than {@value #FORMAT}
     */
    public static DiceSet parse(String identity, String text) throws DiceSetFormatException {
        Properties p = new Properties();
        try (StringReader reader = new StringReader(text)) {
            // From a Reader, not a stream: Properties reads streams as ISO-8859-1, and the files are UTF-8.
            p.load(reader);
        } catch (IOException e) {
            // A StringReader doesn't fail; this is only here because load() declares it.
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            // A malformed Unicode escape (a backslash, u and four hex digits).
            throw new DiceSetFormatException("Dice Set " + identity + " is not a properties file: " + e.getMessage());
        }
        checkFormat(identity, p);
        Values v = new Values(p);
        String name = v.text("name");
        return new DiceSet(identity, name.isEmpty() ? identity : name,
                v.colour("base", 0x20, 0x20, 0x20), v.number("roughness", 0.2, 0.02, 1), v.number("metallic", 0, 0, 1),
                new DiceSet.Swirl(v.colour("swirl.color", 0xff, 0xff, 0xff), v.number("swirl.scale", 8, 0.5, 64),
                        v.number("swirl.warp", 1, 0, 4), v.number("swirl.bands", 0, 0, 8),
                        v.number("swirl.amount", 0, 0, 1), v.number("swirl.softness", 0.08, 0.005, 0.5)),
                new DiceSet.Flakes(v.colour("flakes.color", 0xff, 0xff, 0xff), v.number("flakes.density", 0, 0, 1),
                        v.number("flakes.size", 0.4, 0.05, 3), v.number("flakes.metallic", 0, 0, 1)),
                v.number("pearl", 0, 0, 2),
                new DiceSet.Transmission(v.number("transmission", 0, 0, 1),
                        v.colour("transmission.color", 0xff, 0xff, 0xff), v.number("ior", 1.54, 1, 2.5),
                        v.number("cloudiness", 0, 0, 1)),
                new DiceSet.Ink(v.colour("ink", 0xf0, 0xf0, 0xe8), v.number("ink.metallic", 0, 0, 1),
                        v.number("ink.roughness", 0.6, 0.02, 1)));
    }

    /** Rejects a file this build can't read: no {@code format}, one that isn't a version, or a newer one. */
    private static void checkFormat(String identity, Properties p) throws DiceSetFormatException {
        String format = p.getProperty("format", "").strip();
        if (format.isEmpty()) {
            throw new DiceSetFormatException("Dice Set " + identity + " has no format");
        }
        int version;
        try {
            version = Integer.parseInt(format);
        } catch (NumberFormatException e) {
            throw new DiceSetFormatException("Dice Set " + identity + " has format '" + format
                    + "', which is not a version");
        }
        if (version > FORMAT) {
            throw new DiceSetFormatException("Dice Set " + identity + " has format " + version
                    + ", newer than this version of Andy's Dice reads (" + FORMAT + ")");
        }
        if (version < 1) {
            throw new DiceSetFormatException("Dice Set " + identity + " has format " + version
                    + ", which is not a version");
        }
        // Older formats would be migrated forward here (ADR-0009's Consequences); 1 is the first.
    }

    /** Typed, trimmed reads of the keys this format knows; any other key is simply never read. */
    private record Values(Properties p) {

        String text(String key) {
            return p.getProperty(key, "").strip();
        }

        double number(String key, double fallback, double min, double max) {
            String value = text(key);
            if (value.isEmpty()) {
                return fallback;
            }
            try {
                double d = Double.parseDouble(value);
                // NaN fails both comparisons, so it falls back too.
                return d >= min && d <= max ? d : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        SrgbColour colour(String key, int red, int green, int blue) {
            return SrgbColour.parse(text(key)).orElseGet(() -> new SrgbColour(red, green, blue));
        }
    }
}
