package io.github.evildarkarchon.andysdice.app;

import java.util.List;
import javafx.scene.paint.Color;

/**
 * The Roll UI's one fixed dark theme, as code constants (spec §3.2 "Accessibility"). v1 ignores the system's
 * light/dark scheme and has no high-contrast mode, so these are the only UI colours; {@link Stylesheet} turns them
 * into the CSS every control is drawn with. The rendered Tray and Dice are outside the palette.
 *
 * <p>Every pair the UI draws is listed in {@link #PAIRS}, and {@code ContrastTest} holds each to WCAG 2.2 AA (4.5:1
 * for text, 3:1 for graphics). A ticket that draws a new pairing adds it there.
 */
final class Palette {

    /** Behind everything: the window and the area round the Tray. */
    static final Color BACKGROUND = Color.web("#121418");
    /** The sidebar, the open tab, the toolbar and the result strip. */
    static final Color SURFACE = Color.web("#1b1e24");
    /** Buttons and the unselected tabs: one step up from the surface. */
    static final Color RAISED = Color.web("#2a2f38");
    /** Inside the Roll Expression box and other text fields. */
    static final Color FIELD = Color.web("#0d0f12");
    /** A control's outline, where it has to be told from what it sits on. */
    static final Color OUTLINE = Color.web("#7a8394");

    /** Text, everywhere. */
    static final Color TEXT = Color.web("#e8eaed");
    /** Secondary text: prompt text, hints, unselected tabs and (#49) the faded chips' Face Values. */
    static final Color TEXT_MUTED = Color.web("#a7afbc");
    /** Every code-drawn icon. */
    static final Color ICON = TEXT;

    /** The Roll button, the selected tab's marker and selected text's highlight. */
    static final Color ACCENT = Color.web("#e8a94f");
    /** Text on {@link #ACCENT}. */
    static final Color ON_ACCENT = Color.web("#1a1206");
    /** The keyboard focus ring. */
    static final Color FOCUS = Color.web("#8ab4f8");
    /** The bell while a Notice is unread (#52). */
    static final Color NOTICE = Color.web("#f6c768");
    /** The parse-error popup (#50). */
    static final Color ERROR = Color.web("#b3261e");
    /** Text on {@link #ERROR}. */
    static final Color ON_ERROR = Color.WHITE;

    /** Every foreground the UI draws on a background, with the AA minimum it must meet. */
    static final List<Contrast.Pair> PAIRS = List.of(
            text("text on the background", TEXT, BACKGROUND),
            text("text on the sidebar, toolbar and result strip", TEXT, SURFACE),
            text("button and tab text", TEXT, RAISED),
            text("typed text in the box", TEXT, FIELD),
            text("muted text on the surface", TEXT_MUTED, SURFACE),
            text("an unselected tab's title", TEXT_MUTED, RAISED),
            text("the box's prompt text", TEXT_MUTED, FIELD),
            text("muted text on the background", TEXT_MUTED, BACKGROUND),
            text("the result strip's total, expression and Modifiers", TEXT, SURFACE),
            text("a kept Die's chip", TEXT, RAISED),
            text("a faded chip's Face Value (an earlier value, or a Die not kept)", TEXT_MUTED, SURFACE),
            text("a term's heading and subtotal, and the facts line", TEXT_MUTED, SURFACE),
            text("the reroll badge's count", TEXT, SURFACE),
            graphic("the reroll badge on the result strip", ICON, SURFACE),
            graphic("the not-kept slash across a faded chip", ICON, SURFACE),
            graphic("a chip's outline on the result strip", OUTLINE, SURFACE),
            graphic("the result strip's focus ring", FOCUS, SURFACE),
            text("the Roll button's label", ON_ACCENT, ACCENT),
            text("selected text in the box", ON_ACCENT, ACCENT),
            text("the bell's count while unread", ON_ACCENT, NOTICE),
            graphic("the bell's icon while unread", ON_ACCENT, NOTICE),
            graphic("the Notice list's outline over the window", OUTLINE, BACKGROUND),
            text("the parse-error popup", ON_ERROR, ERROR),
            text("the \"next:\" chip's text (#50)", TEXT, RAISED),
            graphic("the \"next:\" chip's outline on the toolbar (#50)", ACCENT, SURFACE),
            graphic("icons on the surface", ICON, SURFACE),
            graphic("icons on a button", ICON, RAISED),
            graphic("the focus ring on the surface", FOCUS, SURFACE),
            graphic("the focus ring on the background", FOCUS, BACKGROUND),
            graphic("the focus ring inside the box", FOCUS, FIELD),
            graphic("a control's outline on the surface", OUTLINE, SURFACE),
            graphic("the box's outline on its field", OUTLINE, FIELD),
            graphic("the Roll button on the toolbar", ACCENT, SURFACE),
            graphic("the selected tab's marker", ACCENT, SURFACE),
            graphic("the unread bell on the sidebar", NOTICE, SURFACE));

    private Palette() {}

    private static Contrast.Pair text(String what, Color foreground, Color background) {
        return new Contrast.Pair(what, foreground, background, Contrast.Kind.TEXT);
    }

    private static Contrast.Pair graphic(String what, Color foreground, Color background) {
        return new Contrast.Pair(what, foreground, background, Contrast.Kind.GRAPHIC);
    }
}
