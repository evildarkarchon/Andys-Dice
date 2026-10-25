/**
 * The JavaFX shell and Roll UI, persistence, the lock file, {@code --smoke-test} and the wiring of the modules
 * below it (spec §5).
 */
module io.github.evildarkarchon.andysdice.app {
    requires io.github.evildarkarchon.andysdice.core;
    requires io.github.evildarkarchon.andysdice.physics;
    requires io.github.evildarkarchon.andysdice.render;
    requires io.github.evildarkarchon.andysdice.sound;
    requires javafx.controls;
    // System.Logger's records go through java.util.logging to the log file (spec §6). Requiring it is also what
    // puts it in the jlinked image: without it System.Logger would fall back to a console-only logger.
    requires java.logging;

    // JavaFX's launcher instantiates the Application subclass reflectively, from javafx.graphics.
    exports io.github.evildarkarchon.andysdice.app to javafx.graphics;
}
