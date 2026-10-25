/**
 * The streaming mixer over {@code javax.sound.sampled}, the sample bank and its {@code SOURCES.md}, and the
 * sink seam, driven by {@code core}'s playback clock (spec §5, ADR-0012). It loads no natives.
 */
module io.github.evildarkarchon.andysdice.sound {
    requires io.github.evildarkarchon.andysdice.core;
}
