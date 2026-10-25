package andysdice.prototype.tray;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * PROTOTYPE. One Dice Set as the shaders see it, parsed from a {@code .diceset} file: a versioned,
 * flat {@code java.util.Properties} file in the spirit of ADR-0007 (UTF-8, a {@code format} key, no
 * JSON library). Every Dice Set uses the same parameters; a solid resin is simply one whose swirl,
 * flakes, pearl and transmission are all zero, so one uber-shader draws every look.
 *
 * <p>Colours are written as sRGB hex and held here in linear RGB, which is what the shaders want.
 *
 * @param file         the file the set came from (for the HUD)
 * @param source       the file's text, shown verbatim in the HUD
 * @param warnings     unknown keys and bad values, which fall back to their defaults
 * @param transmission 0 = opaque resin, 1 = clear; anything above 0 is a translucent Dice Set
 * @param absorb       linear colour of white light after 10 mm inside the body (translucent sets)
 * @param cloudiness   how much light the body scatters (0 = glass-clear, 1 = milky)
 */
record DiceSet(String file, String source, List<String> warnings, String name,
               float[] base, float roughness, float metallic,
               float[] swirlColor, float swirlScale, float swirlWarp, float swirlBands, float swirlAmount, float swirlSoftness,
               float[] flakeColor, float flakeDensity, float flakeSize, float flakeMetallic,
               float pearl,
               float transmission, float[] absorb, float ior, float cloudiness,
               float[] ink, float inkMetallic, float inkRoughness) {

    static final int FORMAT = 1;

    private static final Set<String> KEYS = Set.of("format", "name", "base", "roughness", "metallic",
            "swirl.color", "swirl.scale", "swirl.warp", "swirl.bands", "swirl.amount", "swirl.softness",
            "flakes.color", "flakes.density", "flakes.size", "flakes.metallic", "pearl",
            "transmission", "transmission.color", "ior", "cloudiness",
            "ink", "ink.metallic", "ink.roughness");

    boolean translucent() {
        return transmission > 0;
    }

    boolean hasSwirl() {
        return swirlAmount > 0;
    }

    /** Parses one file's text. Never throws: problems become {@link #warnings} and defaults. */
    static DiceSet parse(String file, String text) {
        Properties p = new Properties();
        List<String> warnings = new ArrayList<>();
        try (Reader r = new StringReader(text)) {
            p.load(r);
        } catch (IOException ex) {
            warnings.add("unreadable: " + ex.getMessage());
        }
        Set<String> unknown = new TreeSet<>(p.stringPropertyNames());
        unknown.removeAll(KEYS);
        for (String k : unknown) warnings.add("unknown key " + k);
        String format = p.getProperty("format", "").trim();
        if (!format.equals(Integer.toString(FORMAT))) warnings.add("format is '" + format + "', expected " + FORMAT);

        Reader2 g = new Reader2(p, warnings);
        float[] base = g.colour("base", "#202020");
        return new DiceSet(file, text, List.copyOf(warnings), p.getProperty("name", file).trim(),
                base, g.num("roughness", 0.2f, 0.02f, 1), g.num("metallic", 0, 0, 1),
                g.colour("swirl.color", "#ffffff"), g.num("swirl.scale", 8, 0.5f, 64), g.num("swirl.warp", 1, 0, 4),
                g.num("swirl.bands", 0, 0, 8), g.num("swirl.amount", 0, 0, 1), g.num("swirl.softness", 0.08f, 0.005f, 0.5f),
                g.colour("flakes.color", "#ffffff"), g.num("flakes.density", 0, 0, 1), g.num("flakes.size", 0.4f, 0.05f, 3),
                g.num("flakes.metallic", 0, 0, 1),
                g.num("pearl", 0, 0, 2),
                g.num("transmission", 0, 0, 1), g.colour("transmission.color", "#ffffff"), g.num("ior", 1.54f, 1, 2.5f),
                g.num("cloudiness", 0, 0, 1),
                g.colour("ink", "#f0f0e8"), g.num("ink.metallic", 0, 0, 1), g.num("ink.roughness", 0.6f, 0.02f, 1));
    }

    /**
     * Loads every {@code *.diceset} in {@code dir}, sorted by file name (the catalogue order). A missing
     * folder yields an empty list.
     */
    static List<DiceSet> loadAll(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<DiceSet> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".diceset")).sorted().toList()) {
                out.add(parse(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    /** Typed getters that clamp, and record a warning instead of failing. */
    private record Reader2(Properties p, List<String> warnings) {
        float num(String key, float def, float lo, float hi) {
            String v = p.getProperty(key);
            if (v == null || v.isBlank()) return def;
            try {
                float f = Float.parseFloat(v.trim());
                if (f < lo || f > hi) warnings.add(key + " " + f + " clamped to [" + lo + ", " + hi + "]");
                return Math.clamp(f, lo, hi);
            } catch (NumberFormatException ex) {
                warnings.add(key + " is not a number: " + v.trim());
                return def;
            }
        }

        float[] colour(String key, String def) {
            String v = p.getProperty(key, def).trim();
            if (!v.matches("#[0-9a-fA-F]{6}")) {
                warnings.add(key + " is not #rrggbb: " + v);
                v = def;
            }
            int rgb = Integer.parseInt(v.substring(1), 16);
            return new float[]{linear(rgb >> 16 & 0xff), linear(rgb >> 8 & 0xff), linear(rgb & 0xff)};
        }

        private static float linear(int c8) {
            double c = c8 / 255.0;
            return (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /** A short one-line description of the body, for captions. */
    String summary() {
        List<String> parts = new ArrayList<>();
        parts.add(metallic >= 0.5f ? "metal" : translucent() ? String.format(Locale.ROOT, "translucent %.2f", transmission) : "opaque resin");
        if (hasSwirl()) parts.add(swirlBands > 0 ? "banded swirl" : "cloud swirl");
        if (flakeDensity > 0) parts.add(flakeMetallic > 0.5f ? "glitter" : "speckle");
        if (pearl > 0) parts.add("pearl");
        if (cloudiness > 0) parts.add(String.format(Locale.ROOT, "cloudy %.2f", cloudiness));
        return String.join(", ", parts);
    }
}
