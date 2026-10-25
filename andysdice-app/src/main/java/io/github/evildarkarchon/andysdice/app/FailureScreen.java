package io.github.evildarkarchon.andysdice.app;

import java.lang.System.Logger.Level;
import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Puts the {@link FailurePanel} on screen (spec §3.2 "Runtime failure"): over a running Roll UI when the session
 * fails, or in a window of its own, Layout C around an empty Tray area, when start-up can't get an OpenGL 3.3 core
 * context or Jolt (ADR-0002's fail-fast). Either way the panel takes the Tray's place and the sidebar is disabled
 * behind it.
 *
 * <p>Thread contract: JavaFX Application Thread only.
 */
final class FailureScreen {

    private static final System.Logger LOG = System.getLogger(FailureScreen.class.getName());

    private FailureScreen() {}

    /**
     * Replaces the Tray with the failure panel and disables the sidebar and toolbar.
     *
     * @param ui the window's Roll UI
     * @param failure what ended the session
     * @param quit what Quit does
     * @return the panel shown
     */
    static FailurePanel show(RollUi ui, Failure failure, Runnable quit) {
        Objects.requireNonNull(ui, "ui");
        FailurePanel panel = new FailurePanel(failure, quit);
        ui.showFailure(panel);
        panel.quitButton().requestFocus();
        LOG.log(Level.INFO, "Failure panel shown: " + failure.cause());
        return panel;
    }

    /**
     * Opens the app window as start-up's fail-fast: Layout C with the failure panel where the Tray would be.
     *
     * @param stage the window to open
     * @param failure why start-up failed, naming the requirement and what was detected
     * @param quit what Quit does
     * @return the panel shown
     */
    static FailurePanel startup(Stage stage, Failure failure, Runnable quit) {
        // No Tray could be made; an empty region stands in for it until the panel replaces it.
        RollUi ui = new RollUi(new Region());
        MainWindow.open(stage, ui);
        return show(ui, failure, quit);
    }

    /**
     * Finds the failure panel in a Roll UI.
     *
     * @param ui the Roll UI
     * @return its failure panel, or {@code null} while the session hasn't failed
     */
    static FailurePanel panelOf(RollUi ui) {
        Node found = ui.lookup("." + FailurePanel.STYLE_CLASS);
        return found instanceof FailurePanel panel ? panel : null;
    }
}
