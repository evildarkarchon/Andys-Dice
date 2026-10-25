package andysdice.prototype.rollui;

import java.util.ArrayList;
import java.util.List;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** PROTOTYPE contract for one radically different Roll UI layout. */
interface Variant {

    /** What a variant is built with, plus the listeners to drop when the switcher moves on. */
    final class Ctx {
        final AppState state;
        final Behaviour beh;
        final Stage stage;
        private final List<Runnable> cleanup = new ArrayList<>();

        Ctx(AppState state, Behaviour beh, Stage stage) {
            this.state = state;
            this.beh = beh;
            this.stage = stage;
        }

        /** Adds a listener to shared state that is removed again when the variant is switched away. */
        <T> void listen(ObservableList<T> list, ListChangeListener<? super T> l) {
            list.addListener(l);
            cleanup.add(() -> list.removeListener(l));
        }

        void onDispose(Runnable r) {
            cleanup.add(r);
        }

        void dispose() {
            cleanup.forEach(Runnable::run);
            cleanup.clear();
        }
    }

    String key();

    String name();

    /** Sets this variant's own choice on each behaviour dimension; the switcher can override them after. */
    void applyDefaults(Behaviour b);

    Region build(Ctx ctx);

    TrayView tray();

    /** Capture tour: put {@code expression} in the entry box, as a player would type it. */
    void demoType(String expression);

    /** Capture tour: press Roll (or Enter). */
    void demoRoll();

    /** Capture tour: open whatever this variant hides (drawer, menus stay shut). */
    void demoPanels();
}
