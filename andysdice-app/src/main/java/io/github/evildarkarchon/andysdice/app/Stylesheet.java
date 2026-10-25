package io.github.evildarkarchon.andysdice.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import javafx.scene.paint.Color;

/**
 * The Roll UI's stylesheet, written from the {@link Palette} constants so the colours a contrast test checks are
 * the colours the controls are drawn in. It is served as a {@code data:} URI (JavaFX reads those as stylesheets),
 * so there is no CSS resource to keep in step with the palette.
 *
 * <p>It sits on top of JavaFX's Modena: it redefines Modena's looked-up colours ({@code -fx-base},
 * {@code -fx-text-base-color} and the rest) on {@code .roll-ui}, so every control below inherits the dark theme,
 * and sets the backgrounds, outlines and focus rings Layout C needs outright. A popup or dialog is its own scene
 * and doesn't inherit it: add {@link #uri()} to its scene, or its root's stylesheets, and give its root the
 * {@code roll-ui} style class.
 *
 * <p>Pure; safe on any thread.
 */
final class Stylesheet {

    /** The style class that scopes the theme: Layout C's root, and the root of any popup or dialog. */
    static final String ROOT_CLASS = "roll-ui";

    private static final String URI = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(css().getBytes(StandardCharsets.UTF_8));

    private Stylesheet() {}

    /**
     * Returns the stylesheet as a URI for {@code getStylesheets().add(...)}.
     *
     * @return a {@code data:} URI holding {@link #css()}
     */
    static String uri() {
        return URI;
    }

    /**
     * Returns the stylesheet's text.
     *
     * @return CSS in JavaFX's dialect
     */
    static String css() {
        String background = hex(Palette.BACKGROUND);
        String surface = hex(Palette.SURFACE);
        String raised = hex(Palette.RAISED);
        String field = hex(Palette.FIELD);
        String outline = hex(Palette.OUTLINE);
        String text = hex(Palette.TEXT);
        String muted = hex(Palette.TEXT_MUTED);
        String icon = hex(Palette.ICON);
        String accent = hex(Palette.ACCENT);
        String onAccent = hex(Palette.ON_ACCENT);
        String focus = hex(Palette.FOCUS);
        return String.join("\n",
                // Modena's looked-up colours, redefined for everything inside the Roll UI.
                "." + ROOT_CLASS + " {",
                "  -fx-base: " + raised + ";",
                "  -fx-background: " + background + ";",
                "  -fx-control-inner-background: " + field + ";",
                "  -fx-accent: " + accent + ";",
                "  -fx-focus-color: " + focus + ";",
                "  -fx-faint-focus-color: transparent;",
                "  -fx-text-base-color: " + text + ";",
                "  -fx-text-background-color: " + text + ";",
                "  -fx-text-inner-color: " + text + ";",
                "  -fx-mark-color: " + text + ";",
                "  -fx-selection-bar: " + accent + ";",
                "  -fx-selection-bar-text: " + onAccent + ";",
                "  -fx-background-color: " + background + ";",
                "}",
                "." + ROOT_CLASS + " .label { -fx-text-fill: " + text + "; }",
                "." + ROOT_CLASS + " .icon { -fx-fill: " + icon + "; }",

                // The sidebar: header over the tabs, on the surface colour, with a hairline against the main area.
                ".sidebar { -fx-background-color: " + surface + "; -fx-border-color: transparent " + raised
                        + " transparent transparent; -fx-border-width: 0 1 0 0; }",
                ".sidebar-header { -fx-padding: 12 10 10 14; -fx-spacing: 8; -fx-alignment: center-left; }",
                ".app-name { -fx-font-size: 17px; -fx-font-weight: bold; }",

                // Tabs as a notebook: unselected ones raised and muted, the selected one joined to its page and
                // marked with an accent bar, so the selection never rests on colour alone.
                ".sidebar .tab-pane > .tab-header-area { -fx-padding: 0 6 0 6; }",
                ".sidebar .tab-pane > .tab-header-area > .tab-header-background { -fx-background-color: "
                        + surface + "; }",
                ".sidebar .tab-pane > .tab-header-area > .headers-region > .tab { -fx-background-color: " + raised
                        + "; -fx-background-insets: 0 1 0 1; -fx-background-radius: 4 4 0 0; -fx-padding: 4 10 4 10; }",
                ".sidebar .tab-pane > .tab-header-area > .headers-region > .tab .tab-label { -fx-text-fill: "
                        + muted + "; }",
                ".sidebar .tab-pane > .tab-header-area > .headers-region > .tab:selected { -fx-background-color: "
                        + accent + ", " + surface + "; -fx-background-insets: 0 1 0 1, 3 1 0 1; }",
                ".sidebar .tab-pane > .tab-header-area > .headers-region > .tab:selected .tab-label { "
                        + "-fx-text-fill: " + text + "; -fx-font-weight: bold; }",
                ".sidebar .tab-pane > .tab-header-area > .headers-region > .tab:selected .focus-indicator { "
                        + "-fx-border-color: transparent; }",
                ".sidebar .tab-pane:focused > .tab-header-area > .headers-region > .tab:selected .focus-indicator { "
                        + "-fx-border-color: " + focus + "; -fx-border-width: 2; -fx-border-radius: 3; }",
                ".sidebar .tab-pane > .tab-content-area { -fx-background-color: " + surface + "; }",

                // The toolbar, the Tray's surround and the result strip.
                ".toolbar { -fx-background-color: " + surface + "; -fx-padding: 10 12 10 12; -fx-spacing: 6; "
                        + "-fx-alignment: center-left; }",
                ".die-strip { -fx-spacing: 4; -fx-alignment: center-left; }",
                ".tray-area { -fx-background-color: " + background + "; }",
                ".result-strip { -fx-background-color: " + surface + "; -fx-padding: 12 16 12 16; }",
                ".result-message { -fx-font-size: 15px; }",

                // #50: the Queued Roll's "next: …" chip on the toolbar, outlined in the accent so it reads as a
                // state rather than a button; and the parse-error popup's label, its whole content (a popup is
                // its own scene, so the label carries the roll-ui class too, and this outranks that rule).
                "." + ROOT_CLASS + " .next-chip { -fx-background-color: " + accent + ", " + raised
                        + "; -fx-background-insets: 0, 1; -fx-background-radius: 10, 9; -fx-padding: 3 10 3 10; "
                        + "-fx-text-fill: " + text + "; }",
                "." + ROOT_CLASS + ".error-popup { -fx-background-color: " + hex(Palette.ERROR)
                        + "; -fx-background-radius: 6; -fx-padding: 6 10 6 10; -fx-text-fill: "
                        + hex(Palette.ON_ERROR) + "; -fx-font-size: 13px; }",

                // The Roll Expression box. Modena hides a focused field's prompt; the first-run box has focus
                // and must still show "e.g. 1d20+5", so the prompt keeps its colour when focused.
                "." + ROOT_CLASS + " .text-field { -fx-background-color: " + outline + ", " + field
                        + "; -fx-background-insets: 0, 1; -fx-background-radius: 4, 3; -fx-text-fill: " + text
                        + "; -fx-prompt-text-fill: " + muted + "; -fx-highlight-fill: " + accent
                        + "; -fx-highlight-text-fill: " + onAccent + "; -fx-font-size: 15px; }",
                "." + ROOT_CLASS + " .text-field:focused { -fx-background-color: " + focus + ", " + field
                        + "; -fx-background-insets: 0, 2; -fx-prompt-text-fill: " + muted + "; }",

                // Buttons: raised with an outline; a focus ring with a gap of surface between it and the fill, so
                // the ring stands against the surface whatever the button's own colour.
                "." + ROOT_CLASS + " .button { -fx-background-color: " + outline + ", " + raised
                        + "; -fx-background-insets: 0, 1; -fx-background-radius: 4, 3; -fx-text-fill: " + text
                        + "; -fx-padding: 5 10 5 10; }",
                "." + ROOT_CLASS + " .button:hover { -fx-background-color: " + text + ", " + raised + "; }",
                "." + ROOT_CLASS + " .button:armed { -fx-background-color: " + outline + ", " + field + "; }",
                "." + ROOT_CLASS + " .button:focused { -fx-background-color: " + focus + ", " + surface + ", "
                        + raised + "; -fx-background-insets: 0, 2, 3; -fx-background-radius: 5, 4, 3; }",
                "." + ROOT_CLASS + " .roll-button { -fx-background-color: " + accent + "; -fx-background-insets: 0; "
                        + "-fx-text-fill: " + onAccent + "; -fx-font-weight: bold; -fx-padding: 5 16 5 16; }",
                "." + ROOT_CLASS + " .roll-button:hover { -fx-background-color: " + text + ", " + accent
                        + "; -fx-background-insets: 0, 1; }",
                "." + ROOT_CLASS + " .roll-button:focused { -fx-background-color: " + focus + ", " + surface + ", "
                        + accent + "; -fx-background-insets: 0, 2, 3; }",
                "." + ROOT_CLASS + " .icon-button { -fx-background-color: transparent; -fx-padding: 6; }",
                "." + ROOT_CLASS + " .icon-button:hover { -fx-background-color: " + raised + "; }",
                "." + ROOT_CLASS + " .icon-button:focused { -fx-background-color: " + focus + ", " + surface
                        + "; -fx-background-insets: 0, 2; }",
                "");
    }

    /** A colour as CSS {@code #rrggbb}; the palette is opaque. Also used by stylesheets kept beside a control. */
    static String hex(Color colour) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(colour.getRed() * 255),
                Math.round(colour.getGreen() * 255), Math.round(colour.getBlue() * 255));
    }
}
