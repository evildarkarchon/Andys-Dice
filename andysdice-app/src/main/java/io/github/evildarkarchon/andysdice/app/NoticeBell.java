package io.github.evildarkarchon.andysdice.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import javafx.beans.Observable;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;

/**
 * The bell in the sidebar's header, wired to the {@link Notices} (spec §3.2 "Notices and confirmations"). It shows
 * how many Notices there are, and is highlighted (filled in {@link Palette#NOTICE}, its count bold) while any is
 * unread. Activating it lists them under the bell, with Dismiss all; listing them marks them read. A Notice
 * arriving only changes the bell: nothing pops up and focus stays where it is.
 *
 * <p>The bell's look is a small stylesheet of its own ({@link #css()}), from the palette like {@link Stylesheet}'s,
 * on the bell and on its list. Its selectors are more specific than the shared ones, so they win wherever the
 * stylesheets sit.
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread.
 */
final class NoticeBell {

    /** The bell's pseudo-class while any Notice is unread. */
    static final PseudoClass UNREAD = PseudoClass.getPseudoClass("unread");

    /** The bell's style class, beside the shared {@code icon-button}. */
    static final String BELL_CLASS = "notice-bell";

    /** The list's root style class, beside {@link Stylesheet#ROOT_CLASS}. */
    static final String LIST_CLASS = "notice-list";

    /** The style class of the list's Dismiss all button. */
    static final String DISMISS_CLASS = "notice-dismiss-all";

    /** The list's width, logical px: a little narrower than the sidebar it hangs into. */
    static final double LIST_WIDTH = 320;

    /** The list's tallest, logical px; more Notices than fit scroll. */
    static final double LIST_MAX_HEIGHT = 420;

    private static final String URI = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(css().getBytes(StandardCharsets.UTF_8));

    private final Button bell;
    private final Notices notices;
    private final Popup list = new Popup();
    private final VBox listRoot = new VBox();

    private NoticeBell(Button bell, Notices notices) {
        this.bell = bell;
        this.notices = notices;
    }

    /**
     * Makes a button the Notice bell: its count, its unread highlight and its list.
     *
     * @param bell the header's bell button, its graphic already the bell icon
     * @param notices the Notices it shows
     * @return the bell's wiring
     */
    static NoticeBell attach(Button bell, Notices notices) {
        NoticeBell wired = new NoticeBell(Objects.requireNonNull(bell, "bell"),
                Objects.requireNonNull(notices, "notices"));
        wired.wire();
        return wired;
    }

    /**
     * Returns the bell's stylesheet as a URI for {@code getStylesheets().add(...)}.
     *
     * @return a {@code data:} URI holding {@link #css()}
     */
    static String uri() {
        return URI;
    }

    /**
     * Returns the bell's stylesheet: the unread highlight and the list's look, from {@link Palette}.
     *
     * @return CSS in JavaFX's dialect
     */
    static String css() {
        String root = "." + Stylesheet.ROOT_CLASS;
        String bell = root + " .icon-button." + BELL_CLASS;
        String list = root + "." + LIST_CLASS;
        String surface = Stylesheet.hex(Palette.SURFACE);
        String outline = Stylesheet.hex(Palette.OUTLINE);
        String text = Stylesheet.hex(Palette.TEXT);
        String muted = Stylesheet.hex(Palette.TEXT_MUTED);
        String onAccent = Stylesheet.hex(Palette.ON_ACCENT);
        String focus = Stylesheet.hex(Palette.FOCUS);
        String notice = Stylesheet.hex(Palette.NOTICE);
        return String.join("\n",
                // Unread: filled and bold, so it stands out by more than its hue; the focus ring keeps a gap of
                // surface between it and the fill, as on the other buttons.
                bell + ":unread { -fx-background-color: " + notice + "; -fx-background-radius: 4; "
                        + "-fx-text-fill: " + onAccent + "; -fx-font-weight: bold; }",
                bell + ":unread:hover { -fx-background-color: " + text + ", " + notice
                        + "; -fx-background-insets: 0, 1; }",
                bell + ":unread:focused { -fx-background-color: " + focus + ", " + surface + ", " + notice
                        + "; -fx-background-insets: 0, 2, 3; -fx-background-radius: 5, 4, 3; }",
                bell + ":unread .icon { -fx-fill: " + onAccent + "; }",

                // The list: a surface card with an outline, titles in text, details muted.
                list + " { -fx-background-color: " + outline + ", " + surface + "; -fx-background-insets: 0, 1; "
                        + "-fx-background-radius: 6, 5; -fx-padding: 12; -fx-spacing: 10; }",
                list + " .scroll-pane, " + list + " .scroll-pane > .viewport { -fx-background-color: transparent; }",
                list + " .notice-items { -fx-spacing: 10; }",
                list + " .label.notice-heading { -fx-font-size: 15px; -fx-font-weight: bold; }",
                list + " .label.notice-title { -fx-text-fill: " + text + "; -fx-font-weight: bold; }",
                list + " .label.notice-detail { -fx-text-fill: " + muted + "; }",
                list + " .label.notice-empty { -fx-text-fill: " + muted + "; }",
                "");
    }

    /** Follows the Notices and makes the bell open its list. */
    private void wire() {
        bell.getStyleClass().add(BELL_CLASS);
        bell.getStylesheets().add(URI);
        notices.all().addListener((Observable change) -> {
            // What arrives while the list is open is seen at once, so it is read.
            if (list.isShowing()) {
                notices.markAllRead();
                fillList();
            }
            refresh();
        });
        notices.unreadProperty().addListener((Observable change) -> refresh());

        listRoot.getStyleClass().addAll(Stylesheet.ROOT_CLASS, LIST_CLASS);
        // A popup is its own scene, so it takes the theme from its root.
        listRoot.getStylesheets().addAll(Stylesheet.uri(), URI);
        listRoot.setPrefWidth(LIST_WIDTH);
        listRoot.setMaxHeight(LIST_MAX_HEIGHT);
        list.getContent().setAll(listRoot);
        list.setAutoHide(true);
        list.setHideOnEscape(true);
        // Hung from the bell's bottom-right corner, so the list opens into the sidebar rather than over the Tray.
        list.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_TOP_RIGHT);

        bell.setOnAction(event -> open());
        refresh();
    }

    /** Lists the Notices under the bell and marks them read; does nothing if the list is already open. */
    private void open() {
        if (list.isShowing()) {
            return;
        }
        fillList();
        Bounds bounds = bell.localToScreen(bell.getBoundsInLocal());
        list.show(bell, bounds.getMaxX(), bounds.getMaxY() + 4);
        notices.markAllRead();
        // Keys go to the open list (JavaFX redirects them from the owner window), so Space or Enter right after
        // opening dismisses; the main window's focus owner stays the bell, for when the list closes.
        listRoot.lookupAll("." + DISMISS_CLASS).forEach(Node::requestFocus);
    }

    /** Rebuilds the list's content from the Notices. */
    private void fillList() {
        Label heading = new Label("Notices");
        heading.getStyleClass().add("notice-heading");
        VBox items = new VBox();
        items.getStyleClass().add("notice-items");
        if (notices.all().isEmpty()) {
            Label empty = new Label("No Notices.");
            empty.getStyleClass().add("notice-empty");
            items.getChildren().add(empty);
        }
        for (Notice notice : notices.all()) {
            Label title = new Label(notice.title());
            title.getStyleClass().add("notice-title");
            title.setWrapText(true);
            VBox item = new VBox(2, title);
            if (!notice.detail().isEmpty()) {
                Label detail = new Label(notice.detail());
                detail.getStyleClass().add("notice-detail");
                detail.setWrapText(true);
                item.getChildren().add(detail);
            }
            items.getChildren().add(item);
        }
        ScrollPane scroll = new ScrollPane(items);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        listRoot.getChildren().setAll(heading, scroll);
        if (!notices.all().isEmpty()) {
            Button dismissAll = new Button("Dismiss all");
            dismissAll.getStyleClass().add(DISMISS_CLASS);
            dismissAll.setAccessibleText("Dismiss all Notices");
            dismissAll.setOnAction(event -> dismissAll());
            listRoot.getChildren().add(dismissAll);
        }
    }

    /** Dismisses every Notice, closes the list and gives focus back to the bell it was opened from. */
    private void dismissAll() {
        notices.dismissAll();
        list.hide();
        bell.requestFocus();
    }

    /** Shows the current count and highlight. */
    private void refresh() {
        int count = notices.all().size();
        bell.setText(count == 0 ? "" : Integer.toString(count));
        bell.pseudoClassStateChanged(UNREAD, notices.unread() > 0);
        bell.setAccessibleText(accessibleText(count, notices.unread()));
    }

    /**
     * The bell's accessible name, which carries its count and how many are unread, since a screen reader can't
     * see the highlight: "Notices, none", "Notices, 3", "Notices, 3, 2 unread".
     *
     * @param count how many Notices there are
     * @param unread how many of them are unread
     * @return the name
     */
    static String accessibleText(int count, int unread) {
        if (count == 0) {
            return "Notices, none";
        }
        return unread == 0 ? "Notices, " + count : "Notices, " + count + ", " + unread + " unread";
    }
}
