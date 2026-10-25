package io.github.evildarkarchon.andysdice.sound;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The slices of every category, in memory (ADR-0012 "Samples", "Lifecycle"). The built-in bank and its
 * {@code SOURCES.md} are loaded by the sound module from its resources; tests build synthetic banks.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class SampleBank {

    /**
     * The built-in slices' folder, relative to this class. A hyphenated name isn't a package, so the folder
     * stays a plain resource directory of the module.
     */
    private static final String FOLDER = "sample-bank";

    private final Map<SoundCategory, List<Slice>> slices;

    /**
     * Makes a bank of these slices.
     *
     * @param slices the slices of every category, in a fixed order; copied
     * @throws IllegalArgumentException if a category has no slices
     */
    public SampleBank(Map<SoundCategory, List<Slice>> slices) {
        Map<SoundCategory, List<Slice>> copy = new EnumMap<>(SoundCategory.class);
        for (SoundCategory category : SoundCategory.values()) {
            List<Slice> list = slices.get(category);
            if (list == null || list.isEmpty()) {
                throw new IllegalArgumentException("A sample bank needs slices for " + category);
            }
            copy.put(category, List.copyOf(list));
        }
        this.slices = copy;
    }

    /**
     * Loads the built-in bank: the CC0 slices in this module's {@value #FOLDER} resources, beside their
     * {@code SOURCES.md}. Each category's slices are named after it in lower case, {@code felt-floor-01.wav},
     * {@code felt-floor-02.wav} and so on with no gaps; loading a category stops at the first number missing.
     *
     * @return the bank, in file order
     * @throws IOException if a category has no slices or one can't be read as a mono 16-bit WAV at
     *     {@link MixFormat#SAMPLE_RATE}
     */
    public static SampleBank builtIn() throws IOException {
        Map<SoundCategory, List<Slice>> slices = new EnumMap<>(SoundCategory.class);
        for (SoundCategory category : SoundCategory.values()) {
            String prefix = category.name().toLowerCase(Locale.ROOT).replace('_', '-');
            List<Slice> list = new ArrayList<>();
            for (int n = 1; ; n++) {
                String name = FOLDER + "/%s-%02d.wav".formatted(prefix, n);
                // A resource of this module: Class.getResourceAsStream reads it whatever the folder's name.
                try (InputStream in = SampleBank.class.getResourceAsStream(name)) {
                    if (in == null) {
                        break;
                    }
                    list.add(Wav.read(in, name));
                }
            }
            if (list.isEmpty()) {
                throw new IOException("The built-in sample bank has no slices for " + category);
            }
            slices.put(category, list);
        }
        return new SampleBank(slices);
    }

    /**
     * Returns a category's slices.
     *
     * @param category the category
     * @return its slices, at least one; unmodifiable
     */
    public List<Slice> slices(SoundCategory category) {
        return slices.get(Objects.requireNonNull(category, "category"));
    }
}
