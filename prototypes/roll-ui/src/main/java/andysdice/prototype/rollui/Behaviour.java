package andysdice.prototype.rollui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * PROTOTYPE behaviour dimensions that are independent of layout. Each variant picks defaults, and the switcher
 * bar lets any of them be swapped onto any layout ("the layout of B with the skip of A").
 */
final class Behaviour {

    enum Skip {
        SKIP_ALL("Skip all: Esc / click tray"),
        SKIP_THROW("Skip one Throw: Esc / click tray"),
        FAST_FORWARD("Fast-forward: hold Shift / hold on tray"),
        NONE("No skip");

        final String label;

        Skip(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum Input {
        INTERRUPT("Editable; Enter skips to the result, then rolls"),
        QUEUE("Editable; Enter queues the next Roll"),
        TYPE_AHEAD("Editable; Roll waits until at rest"),
        LOCKED("Locked while dice move");

        final String label;

        Input(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum Camera {
        AUTO("Settle-in only"),
        ZOOM("Settle-in + wheel zoom"),
        ORBIT("Settle-in + drag orbit, wheel zoom");

        final String label;

        Camera(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    final ObjectProperty<Skip> skip = new SimpleObjectProperty<>(Skip.SKIP_ALL);
    final ObjectProperty<Input> input = new SimpleObjectProperty<>(Input.INTERRUPT);
    final ObjectProperty<Camera> camera = new SimpleObjectProperty<>(Camera.ORBIT);
}
