package io.github.evildarkarchon.andysdice.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The Roll UI's window content: Layout C, "Notebook sidebar" (spec §3.2 "Layout").
 * <ul>
 *   <li>A left sidebar, {@value #SIDEBAR_WIDTH} px wide: a header (the app name and the Notice bell) above four
 *       tabs, Saved, History (selected at start), Look and Settings.</li>
 *   <li>A toolbar across the top of the main area: the Roll Expression box, the die strip (d4 d6 d8 d10 d12 d20
 *       d%) and the Roll button.</li>
 *   <li>The Tray, filling the rest of the main area, with the result strip under it.</li>
 * </ul>
 * Enter in the box and the Roll button both hand the box's text to {@link #setOnRoll the roll handler}; a die-strip
 * button appends its Die to the box ({@link DieStrip}), even to a selected expression. Every icon is code-drawn
 * ({@link Icons}) and every colour comes from the {@link Palette} through the {@link Stylesheet}.
 *
 * <p>Tab runs in spec §3.2's focus order: the box, the die strip, the Roll button, the tabs, the open tab's content,
 * the bell, and round to the box. JavaFX takes that order from the order nodes were added, so the layout adds them in
 * it, whatever order they are drawn in. The window-wide shortcuts are {@link KeyboardMap}'s.
 *
 * <p>This is the shell later tickets fill: the Breakdown in the result strip (#49), the error popup and the
 * "next: …" chip (#50), the bell's Notices (#52), and the tabs' contents (#55–#57, Settings). Each tab's content
 * starts as an empty {@link StackPane} to put that content in.
 *
 * <p>Thread contract: a JavaFX node, used on the JavaFX Application Thread.
 */
final class RollUi extends BorderPane {

    /** The sidebar's fixed width, logical px; with the 620 px Tray minimum it makes the 960 px window minimum. */
    static final double SIDEBAR_WIDTH = 340;
    /** The result strip's height, logical px: room for #49's large total and a row of chips. */
    static final double RESULT_STRIP_HEIGHT = 118;
    /** The empty box's prompt text (spec §3.2 "First run"). */
    static final String PROMPT = "e.g. 1d20+5";

    private final TextField expressionBox = new TextField();
    private final List<Button> dieButtons = new ArrayList<>();
    private final Button rollButton = new Button("Roll");
    private final HBox toolbar;
    private final Label appName = new Label(MainWindow.TITLE);
    private final Button bell = new Button();
    private final TabPane tabs = new TabPane();
    private final Tab savedTab = tab("Saved");
    private final Tab historyTab = tab("History");
    private final Tab lookTab = tab("Look");
    private final Tab settingsTab = tab("Settings");
    private final BorderPane sidebar;
    private final ResultStrip resultStrip = new ResultStrip();
    private final Node tray;
    private Consumer<String> onRoll = expression -> {};
    private final List<Runnable> failureListeners = new ArrayList<>();
    private boolean failed;

    /**
     * Lays out Layout C around a Tray.
     *
     * @param tray the node the Tray is drawn in (the GL view in the app, a stand-in in tests)
     */
    RollUi(Node tray) {
        this.tray = Objects.requireNonNull(tray, "tray");
        getStyleClass().add(Stylesheet.ROOT_CLASS);
        getStylesheets().add(Stylesheet.uri());

        // The sidebar: the header (name, then the bell at the far right) over the tabs.
        appName.getStyleClass().add("app-name");
        bell.setGraphic(Icons.bell());
        bell.getStyleClass().add("icon-button");
        bell.setAccessibleText("Notices");
        Region headerGap = new Region();
        HBox.setHgrow(headerGap, Priority.ALWAYS);
        HBox header = new HBox(appName, headerGap, bell);
        header.getStyleClass().add("sidebar-header");
        tabs.getTabs().addAll(List.of(savedTab, historyTab, lookTab, settingsTab));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().select(historyTab);
        tabs.setAccessibleText("Sidebar tabs");
        // Tab follows the order children were added, not where they are drawn (spec §3.2 "Keyboard": the tabs and
        // the open tab's content come before the bell). So the header goes in after the tabs, drawn on top all the
        // same.
        sidebar = new BorderPane();
        sidebar.setCenter(tabs);
        sidebar.setTop(header);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setMinWidth(SIDEBAR_WIDTH);
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);

        // The toolbar: the box takes the spare width, then the die strip, then Roll.
        expressionBox.setPromptText(PROMPT);
        expressionBox.setAccessibleText("Roll Expression");
        expressionBox.getStyleClass().add("expression-box");
        expressionBox.setOnAction(event -> roll());
        HBox.setHgrow(expressionBox, Priority.ALWAYS);
        HBox dieStrip = new HBox();
        dieStrip.getStyleClass().add("die-strip");
        for (String die : DieStrip.DICE) {
            Button button = new Button(die);
            button.getStyleClass().add("die-button");
            button.setAccessibleText("Add a " + die);
            button.setOnAction(event -> appendDie(die));
            dieButtons.add(button);
        }
        dieStrip.getChildren().addAll(dieButtons);
        rollButton.getStyleClass().add("roll-button");
        rollButton.setAccessibleText("Roll");
        rollButton.setOnAction(event -> roll());
        toolbar = new HBox(expressionBox, dieStrip, rollButton);
        toolbar.getStyleClass().add("toolbar");

        // The main area: toolbar, the Tray filling the rest, the result strip under it.
        StackPane trayArea = new StackPane(tray);
        trayArea.getStyleClass().add("tray-area");
        // The Tray may be as small as the window allows; its render target follows whatever size it gets.
        trayArea.setMinSize(0, 0);
        // Added in focus order (the toolbar first), as with the sidebar: the Tray and the result strip take no Tab
        // stop, but the toolbar must come before them all the same.
        BorderPane main = new BorderPane();
        main.setTop(toolbar);
        main.setCenter(trayArea);
        main.setBottom(resultStrip);

        // The main area before the sidebar, so Tab runs from the Roll button on into the tabs.
        setCenter(main);
        setLeft(sidebar);
    }

    /**
     * Sets what Enter in the box and the Roll button do with the box's text.
     *
     * @param handler told the box's text at each Roll; it replaces any earlier one
     */
    void setOnRoll(Consumer<String> handler) {
        onRoll = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Shows where the Roll stands in the result strip.
     *
     * @param status the current status
     */
    void showStatus(RollStatus status) {
        resultStrip.show(status);
    }

    /** Gives the box keyboard focus, as at start and after every Roll. */
    void focusBox() {
        expressionBox.requestFocus();
    }

    /**
     * Rolls what the box holds, with focus put back in it first (a Roll button press would otherwise keep it).
     * Focus comes first because a text field gaining focus may select all of itself; the handler then sets the
     * selection it wants (an error's span, or the accepted expression) and that is what stays.
     */
    private void roll() {
        focusBox();
        onRoll.accept(expressionBox.getText());
    }

    /**
     * Appends a Die to the box, even over a selection (the strip adds to the expression, never replaces it), and
     * leaves the caret at the end with focus in the box, so a click on d20 then Enter rolls it.
     */
    private void appendDie(String die) {
        focusBox();
        String appended = DieStrip.append(expressionBox.getText(), die);
        expressionBox.setText(appended);
        expressionBox.positionCaret(appended.length());
    }

    /**
     * Returns the Roll Expression box.
     *
     * @return the text field Enter rolls from
     */
    TextField expressionBox() {
        return expressionBox;
    }

    /**
     * Returns the die strip's buttons.
     *
     * @return one button per {@link DieStrip#DICE} entry, in that order
     */
    List<Button> dieButtons() {
        return List.copyOf(dieButtons);
    }

    /**
     * Returns the Roll button.
     *
     * @return the button that rolls the box's text
     */
    Button rollButton() {
        return rollButton;
    }

    /**
     * Returns the toolbar.
     *
     * @return the row holding the box, the die strip and the Roll button
     */
    HBox toolbar() {
        return toolbar;
    }

    /**
     * Returns the sidebar.
     *
     * @return the column holding the header and the tabs
     */
    BorderPane sidebar() {
        return sidebar;
    }

    /**
     * Returns the app name in the sidebar's header.
     *
     * @return the label
     */
    Label appName() {
        return appName;
    }

    /**
     * Returns the Notice bell. #52 gives it its count, highlight and list.
     *
     * @return the bell button, its graphic a code-drawn icon
     */
    Button bell() {
        return bell;
    }

    /**
     * Returns the sidebar's tabs.
     *
     * @return the tab pane holding Saved, History, Look and Settings, in that order
     */
    TabPane tabs() {
        return tabs;
    }

    /**
     * Returns the Saved tab (#56 fills it).
     *
     * @return the tab
     */
    Tab savedTab() {
        return savedTab;
    }

    /**
     * Returns the History tab, selected at start (#55 fills it).
     *
     * @return the tab
     */
    Tab historyTab() {
        return historyTab;
    }

    /**
     * Returns the Look tab (#57 fills it).
     *
     * @return the tab
     */
    Tab lookTab() {
        return lookTab;
    }

    /**
     * Returns the Settings tab.
     *
     * @return the tab
     */
    Tab settingsTab() {
        return settingsTab;
    }

    /**
     * Returns the result strip.
     *
     * @return the strip under the Tray
     */
    ResultStrip resultStrip() {
        return resultStrip;
    }

    /**
     * Returns the Tray's node.
     *
     * @return the node given at construction
     */
    Node tray() {
        return tray;
    }

    /**
     * Ends the session on screen (spec §3.2 "Runtime failure", #53): the Tray area holds only the failure panel,
     * the Tray's own node taken out of the window, and the sidebar and the toolbar are disabled behind it, so
     * nothing can be rolled, chosen or changed once the session has failed. Any popup or dialog open over the window
     * is closed first, and the {@link #addFailureListener failure listeners} are told last.
     *
     * @param panel the failure panel
     */
    void showFailure(Node panel) {
        Objects.requireNonNull(panel, "panel");
        failed = true;
        // A popup (the Notice list, the error popup, a menu) or a dialog open at the moment of failure would
        // otherwise stay over the panel, and a modal one would keep the Player from reaching Quit.
        hideWindowsOver();
        // The main area's centre is the Tray area the constructor made around the Tray.
        StackPane trayArea = (StackPane) ((BorderPane) getCenter()).getCenter();
        trayArea.getChildren().setAll(panel);
        sidebar.setDisable(true);
        toolbar.setDisable(true);
        for (Runnable listener : List.copyOf(failureListeners)) {
            listener.run();
        }
    }

    /**
     * Tells whether the session has ended in the failure panel ({@link #showFailure}). From then on nothing may roll,
     * open or change: the window-wide shortcuts check this, since a filter on this root still hears keys while the
     * sidebar and toolbar are disabled.
     *
     * @return {@code true} once the failure panel is shown
     */
    boolean failed() {
        return failed;
    }

    /**
     * Adds a listener told once the failure panel is shown, after the sidebar and toolbar are disabled: the session
     * drops its Queued Roll there, so nothing starts behind the panel.
     *
     * @param listener run on the JavaFX thread, in the order added
     */
    void addFailureListener(Runnable listener) {
        failureListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Hides every popup and dialog owned, directly or through another, by this UI's window. */
    private void hideWindowsOver() {
        Window own = getScene() == null ? null : getScene().getWindow();
        if (own == null) {
            return;
        }
        // A copy: hiding a window takes it out of the live list.
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window != own && window.isShowing() && ownedBy(window, own)) {
                window.hide();
            }
        }
    }

    private static boolean ownedBy(Window window, Window owner) {
        for (Window at = ownerOf(window); at != null; at = ownerOf(at)) {
            if (at == owner) {
                return true;
            }
        }
        return false;
    }

    private static Window ownerOf(Window window) {
        return switch (window) {
            case PopupWindow popup -> popup.getOwnerWindow();
            case Stage stage -> stage.getOwner();
            default -> null;
        };
    }

    /** A tab whose content is an empty pane for its ticket to fill. */
    private static Tab tab(String title) {
        Tab tab = new Tab(title, new StackPane());
        tab.setClosable(false);
        return tab;
    }
}
