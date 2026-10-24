package andysdice.prototype.bakeoff;

import javafx.application.Application;

/**
 * PROTOTYPE entry point. A separate main class (not the {@link Application} subclass) because the app
 * itself runs from the class path while JavaFX is on the module path.
 */
public final class Launcher {
    private Launcher() {
    }

    /**
     * Applies frame-pacing switches before the JavaFX toolkit starts (they are read once at startup),
     * then launches {@link BakeoffApp}.
     */
    public static void main(String[] args) {
        if (Boolean.getBoolean("bakeoff.uncapped")) {
            // Lets both renderers run as fast as they can, so frame rate reflects cost, not the monitor.
            System.setProperty("prism.vsync", "false");
            System.setProperty("javafx.animation.fullspeed", "true");
        }
        Application.launch(BakeoffApp.class, args);
    }
}
