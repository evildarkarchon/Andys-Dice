/**
 * The Jolt simulator behind {@code core}'s engine-neutral seam, and our native loader (spec §5, ADR-0003).
 *
 * <p>The build's native-access option set grants this module native access, because the loader
 * {@code System.load}s Jolt's native from the app's natives directory (ADR-0004).
 */
module io.github.evildarkarchon.andysdice.physics {
    requires transitive io.github.evildarkarchon.andysdice.core;
    requires com.github.stephengold.joltjni;

    exports io.github.evildarkarchon.andysdice.physics;
}
