package andysdice.prototype.bakeoff;

import javafx.scene.Node;

/** PROTOTYPE. One renderer under test, swappable at runtime by the floating switcher. */
interface Variant {
    /** Short switcher key, e.g. "A". */
    String key();

    /** Human-readable name shown in the switcher pill. */
    String title();

    /** The node that fills the viewport. Created once per activation. */
    Node node();

    /** Called once per JavaFX pulse on the FX thread, to push the current {@link SceneSpec.Frame}. */
    void update();

    /** One or more lines of live diagnostics for the HUD. */
    String stats();

    /** Releases GPU resources when the switcher moves away from this variant. */
    void dispose();
}
