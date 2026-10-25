package io.github.evildarkarchon.andysdice.app;

import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

/**
 * Every icon in the Roll UI, drawn in code as a JavaFX {@link SVGPath} from path data in this source (spec §3.2
 * "Glyphs"): none depends on a system or emoji font, so how a machine renders ↻ or 🗑 never matters. Each path
 * sits on a 24 × 24 grid and is filled in {@link Palette#ICON}; the stylesheet gives it the {@code icon} style
 * class's colour too, so a later rule (the unread bell, #52) can recolour it.
 *
 * <p>The shapes are this project's own simple geometry, not copied from an icon set. Each call returns a new node,
 * as a node can sit in only one place in the scene graph. Thread contract: like any JavaFX node, build them on
 * the JavaFX Application Thread or before they join a showing scene.
 */
final class Icons {

    /** The Notice bell: a bell body on a rim, with its clapper below. */
    static final String BELL = "M12 2.5c-.83 0-1.5.67-1.5 1.5v.7C7.6 5.4 6 7.9 6 10.8V15l-2 2.5V19h16v-1.5L18 15v-4.2"
            + "c0-2.9-1.6-5.4-4.5-6.1V4c0-.83-.67-1.5-1.5-1.5zM9.8 20a2.2 2.2 0 0 0 4.4 0z";
    /** ↻, the reroll badge: an open ring turning clockwise, with an arrowhead at its top end. */
    static final String REROLL = "M12 3V0l4.5 4.5L12 9V6a6 6 0 1 0 6 6h2.5A8.5 8.5 0 1 1 12 3.5z";
    /** ✎, inline edit: a pencil lying from bottom left to top right. */
    static final String EDIT = "M3 21l1.2-5.2L15.6 4.4l4 4L8.2 19.8zM16.9 3.1l1.6-1.6 4 4-1.6 1.6z";
    /** 🗑, delete: a lid with its handle over a tapered bin. */
    static final String DELETE = "M9 2h6v2h5v2.5H4V4h5zM5.5 8h13l-1.2 14H6.7zM9 10.5v9h1.6v-9zM13.4 10.5v9H15v-9z";
    /** ⚠, a Saved Roll that no longer parses: a triangle with an exclamation mark cut out of it (even-odd). */
    static final String WARNING = "M12 1.5L.5 22h23zM10.8 8.5h2.4v7h-2.4zM10.8 17h2.4v2.5h-2.4z";
    /** ↑, move a Saved Roll up. */
    static final String UP = "M12 2.5l8 9h-5v10H9v-10H4z";
    /** ↓, move a Saved Roll down. */
    static final String DOWN = "M12 21.5l-8-9h5v-10h6v10h5z";
    /** The not-kept slash across a Die's chip: a bar from bottom left to top right. */
    static final String NOT_KEPT = "M2 20.2L20.2 2 22 3.8 3.8 22z";

    private Icons() {}

    /**
     * Returns the Notice bell.
     *
     * @return a new icon
     */
    static SVGPath bell() {
        return icon(BELL, FillRule.NON_ZERO);
    }

    /**
     * Returns the reroll badge's ↻.
     *
     * @return a new icon
     */
    static SVGPath reroll() {
        return icon(REROLL, FillRule.NON_ZERO);
    }

    /**
     * Returns the inline-edit pencil.
     *
     * @return a new icon
     */
    static SVGPath edit() {
        return icon(EDIT, FillRule.NON_ZERO);
    }

    /**
     * Returns the delete bin.
     *
     * @return a new icon
     */
    static SVGPath delete() {
        // Even-odd, so the bin's two slots are cut out of its body.
        return icon(DELETE, FillRule.EVEN_ODD);
    }

    /**
     * Returns the warning triangle.
     *
     * @return a new icon
     */
    static SVGPath warning() {
        return icon(WARNING, FillRule.EVEN_ODD);
    }

    /**
     * Returns the up arrow.
     *
     * @return a new icon
     */
    static SVGPath up() {
        return icon(UP, FillRule.NON_ZERO);
    }

    /**
     * Returns the down arrow.
     *
     * @return a new icon
     */
    static SVGPath down() {
        return icon(DOWN, FillRule.NON_ZERO);
    }

    /**
     * Returns the not-kept slash.
     *
     * @return a new icon
     */
    static SVGPath notKept() {
        return icon(NOT_KEPT, FillRule.NON_ZERO);
    }

    private static SVGPath icon(String content, FillRule rule) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        path.setFillRule(rule);
        path.setFill(Palette.ICON);
        path.getStyleClass().add("icon");
        return path;
    }
}
