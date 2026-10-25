package andysdice.prototype.rollui;

import javafx.application.Application;

/** PROTOTYPE launcher: a plain main class so JavaFX starts from the class path without a module descriptor. */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(RollUiApp.class, args);
    }
}
