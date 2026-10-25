package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Reduced motion for the UI's own controls (spec §3.2 "Accessibility": with it on, UI transitions are instant). It
 * follows the system through the scene's preferences, which follow {@code Platform.Preferences.isReducedMotion()}
 * unless a test overrides them, and has no setting of its own.
 *
 * <p>JavaFX's own animated controls mostly honour it already (the tab pane's tab animations ask the scene). The one
 * that doesn't, among the controls the app uses, is {@link Slider}: a click on its track glides the thumb to the new
 * value over about 0.2 s. {@link #instantTrackClicks} makes that click a jump.
 *
 * <p>Thread contract: the JavaFX Application Thread.
 */
final class ReducedMotion {

    /** The style class of a slider skin's track, which a click glides the thumb along. */
    private static final String TRACK_CLASS = "track";

    private ReducedMotion() {}

    /**
     * Tells whether reduced motion is on for a node's scene.
     *
     * @param node a node, in a scene or not
     * @return {@code true} if its scene prefers reduced motion; {@code false} for a node in no scene
     */
    static boolean on(Node node) {
        Scene scene = node.getScene();
        return scene != null && scene.getPreferences().isReducedMotion();
    }

    /**
     * Makes a click on a slider's track move the thumb at once while reduced motion is on; otherwise the slider is
     * left as JavaFX draws it.
     *
     * <p>The slider's skin glides the thumb only for a press on the track; a drag on the track jumps. So, with reduced
     * motion on, the press is consumed before the track sees it and handed back to the track as a drag at the same
     * point, which sets the same value (snapped to ticks as a press would be) and focuses the slider as a press does,
     * without the glide.
     *
     * @param slider the slider
     */
    static void instantTrackClicks(Slider slider) {
        Objects.requireNonNull(slider, "slider");
        slider.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && on(slider)
                    && event.getTarget() instanceof Node target && target.getStyleClass().contains(TRACK_CLASS)) {
                event.consume();
                Event.fireEvent(target, event.copyFor(target, target, MouseEvent.MOUSE_DRAGGED));
            }
        });
    }
}
