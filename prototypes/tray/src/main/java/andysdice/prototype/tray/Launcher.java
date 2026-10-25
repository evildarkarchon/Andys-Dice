package andysdice.prototype.tray;

import javafx.application.Application;

/**
 * PROTOTYPE entry point. A separate main class (not the {@link Application} subclass) because the app
 * itself runs from the class path while JavaFX is on the module path.
 */
public final class Launcher {
    private Launcher() {
    }

    /** Launches {@link TrayApp}. */
    public static void main(String[] args) {
        Application.launch(TrayApp.class, args);
    }
}
