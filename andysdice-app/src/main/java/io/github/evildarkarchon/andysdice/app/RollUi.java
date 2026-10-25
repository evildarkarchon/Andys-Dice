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
import javafx.scene.layout.VBox;

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
    private final VBox sidebar;
    private final ResultStrip resultStrip = new ResultStrip();
    private final Node tray;
    private Consumer<String> onRoll = expression -> {};

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
        VBox.setVgrow(tabs, Priority.ALWAYS);
        sidebar = new VBox(header, tabs);
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
        BorderPane main = new BorderPane(trayArea);
        main.setTop(toolbar);
        main.setBottom(resultStrip);

        setLeft(sidebar);
        setCenter(main);
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
    VBox sidebar() {
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

    /** A tab whose content is an empty pane for its ticket to fill. */
    private static Tab tab(String title) {
        Tab tab = new Tab(title, new StackPane());
        tab.setClosable(false);
        return tab;
    }
}
