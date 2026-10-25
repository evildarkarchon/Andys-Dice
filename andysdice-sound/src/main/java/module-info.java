/**
 * The streaming mixer over {@code javax.sound.sampled}, the sample bank and its {@code SOURCES.md}, and the
 * sink seam, driven by {@code core}'s playback clock (spec §5, ADR-0012). It loads no natives.
 */
module io.github.evildarkarchon.andysdice.sound {
    // Transitive: the API takes core's Recordings, contact events and Die Kinds.
    requires transitive io.github.evildarkarchon.andysdice.core;
    // javax.sound.sampled: already in the image, since JavaFX requires java.desktop (ADR-0012).
    requires java.desktop;

    exports io.github.evildarkarchon.andysdice.sound;
}
