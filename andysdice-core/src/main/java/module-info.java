/**
 * The pure-Java core of Andy's Dice: the Roll Expression engine, Face Value selection, the Die Kind solids,
 * the engine-neutral Recording types and simulator seam, the Throw planner, the playback clock and the
 * {@code .diceset} and {@code .tray} parsers (spec §5).
 *
 * <p>It sits at the bottom of the reactor and depends on none of our modules. It loads no natives, so the
 * build's native-access option set leaves it denied.
 */
module io.github.evildarkarchon.andysdice.core {
    exports io.github.evildarkarchon.andysdice.core.expression;
}
