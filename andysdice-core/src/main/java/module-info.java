/**
 * The pure-Java core of Andy's Dice: the Roll Expression engine, Face Value selection, the Die Kind solids,
 * the engine-neutral Recording types and simulator seam, the Throw planner, the playback clock and the
 * {@code .diceset} and {@code .tray} parsers (spec §5).
 *
 * <p>It sits at the bottom of the reactor and depends on none of our modules. It loads no natives, so the
 * build's native-access option set leaves it denied.
 */
module io.github.evildarkarchon.andysdice.core {
    // Transitive: the geometry API (solids, poses) is written in JOML types (spec §6: the math library).
    requires transitive org.joml;

    exports io.github.evildarkarchon.andysdice.core.diceset;
    exports io.github.evildarkarchon.andysdice.core.expression;
    exports io.github.evildarkarchon.andysdice.core.facevalue;
    exports io.github.evildarkarchon.andysdice.core.geometry;
    exports io.github.evildarkarchon.andysdice.core.planner;
    exports io.github.evildarkarchon.andysdice.core.playback;
    exports io.github.evildarkarchon.andysdice.core.simulation;
    exports io.github.evildarkarchon.andysdice.core.tray;
}
