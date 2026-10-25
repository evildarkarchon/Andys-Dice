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

    // JavaFX's launcher instantiates the Application subclass reflectively, from javafx.graphics.
    exports io.github.evildarkarchon.andysdice.app to javafx.graphics;
}
