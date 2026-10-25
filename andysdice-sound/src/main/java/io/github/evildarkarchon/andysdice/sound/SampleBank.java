package io.github.evildarkarchon.andysdice.sound;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The slices of every category, in memory (ADR-0012 "Samples", "Lifecycle"). The built-in bank and its
 * {@code SOURCES.md} are loaded by the sound module from its resources; tests build synthetic banks.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class SampleBank {

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
     * Returns a category's slices.
     *
     * @param category the category
     * @return its slices, at least one; unmodifiable
     */
    public List<Slice> slices(SoundCategory category) {
        return slices.get(Objects.requireNonNull(category, "category"));
    }
}
