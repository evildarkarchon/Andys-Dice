package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.diceset.SrgbColour;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.render.LightRig;
import io.github.evildarkarchon.andysdice.render.Look;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * The Look tab's content (spec §3.2 "Look tab"): a swatch grid of the Dice Sets, a list of the Trays and a list of
 * the Light Rigs, each built-in first and then the Player's own, marked "yours", with the Player Files skipped at
 * start-up listed under their section with the reason. Clicking a choice, or Space or Enter on it, picks it; the
 * arrow keys move through a grid or list without picking. A pick goes to the {@code show} callback (in the app, the
 * roll controller, which draws it on the Tray under the mid-Roll Look rule: a Tray picked while Dice move appears
 * once they rest) and is kept by identity in {@code settings.properties} (spec §3.6).
 *
 * <p>Within a section only the chosen entry is reached by Tab, so a section is one stop in the focus order and the
 * arrow keys do the rest (a roving focus, as a radio group has).
 *
 * <p>A stored choice that names nothing this run (a Player File deleted or skipped, an unknown Light Rig) shows the
 * fallback, Onyx, green felt or Studio, without overwriting the stored value: mending the file brings it back. A
 * pick does overwrite it.
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread.
 */
final class LookTab {

    /** The chosen Dice Set's identity; a missing one falls back to Onyx (ADR-0009). */
    static final Setting<String> DICE_SET =
            Setting.text("look.diceSet", DiceSetCatalogue.builtIn().fallback().identity());

    /** The chosen Tray's identity; a missing one falls back to green felt (ADR-0010). */
    static final Setting<String> TRAY = Setting.text("look.tray", BuiltInTrays.DEFAULT_IDENTITY);

    /** The chosen Light Rig, by its constant's name; an unknown one falls back to Studio (ADR-0010). */
    static final Setting<LightRig> LIGHT_RIG = Setting.choice("look.lightRig", LightRig.class, LightRig.DEFAULT);

    /** The pseudo-class of the chosen entry in each section. */
    static final PseudoClass PICKED = PseudoClass.getPseudoClass("picked");

    /** The swatch grid's width in swatches: four fit the 340 px sidebar with the tab's padding and a scroll bar. */
    static final int SWATCH_COLUMNS = 4;

    /** The tab content's root style class. */
    static final String ROOT_CLASS = "look-tab";

    private static final double SWATCH_WIDTH = 70;
    /** Room for the disc, two lines of name and the "yours" line. */
    private static final double SWATCH_HEIGHT = 100;
    private static final double NAME_HEIGHT = 34;
    private static final double DISC_RADIUS = 17;

    private static final String URI = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(css().getBytes(StandardCharsets.UTF_8));

    private final LookCatalogue catalogue;
    private final SettingsStore settings;
    private final Consumer<Look> show;
    private final ScrollPane root;
    private final List<Button> diceSetButtons = new ArrayList<>();
    private final List<Button> trayButtons = new ArrayList<>();
    private final List<Button> lightRigButtons = new ArrayList<>();
    private final List<Label> skippedDiceSetRows = new ArrayList<>();
    private final List<Label> skippedTrayRows = new ArrayList<>();
    private final VBox diceSetSection;
    private final VBox traySection;
    private final VBox lightRigSection;
    private Look look;

    /**
     * Builds the tab from the stored choices and hands that look to {@code show} once, at once.
     *
     * @param catalogue the Dice Sets and Trays, built-in and the Player's own, and the files skipped
     * @param settings the live settings, read now and written on each pick
     * @param show sets the look the Tray is drawn in and the next Roll is thrown into; told the stored look now
     *     and each pick's after it
     */
    LookTab(LookCatalogue catalogue, SettingsStore settings, Consumer<Look> show) {
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.show = Objects.requireNonNull(show, "show");
        look = new Look(catalogue.diceSet(settings.get(DICE_SET)), catalogue.tray(settings.get(TRAY)),
                settings.get(LIGHT_RIG));

        GridPane grid = new GridPane();
        grid.getStyleClass().add("swatch-grid");
        List<DiceSet> sets = catalogue.diceSets().diceSets();
        for (int i = 0; i < sets.size(); i++) {
            Button swatch = swatch(sets.get(i));
            diceSetButtons.add(swatch);
            grid.add(swatch, i % SWATCH_COLUMNS, i / SWATCH_COLUMNS);
        }
        moveWithArrows(grid, diceSetButtons, SWATCH_COLUMNS);
        diceSetSection = section("Dice Set", grid, catalogue.skippedDiceSets(), skippedDiceSetRows);

        VBox trayList = list(catalogue.trays(), this::trayRow, trayButtons);
        traySection = section("Tray", trayList, catalogue.skippedTrays(), skippedTrayRows);

        VBox rigList = list(List.of(LightRig.values()), this::lightRigRow, lightRigButtons);
        lightRigSection = section("Light Rig", rigList, List.of(), new ArrayList<>());

        VBox content = new VBox(diceSetSection, traySection, lightRigSection);
        content.getStyleClass().add("look-sections");
        root = new ScrollPane(content);
        root.setFitToWidth(true);
        root.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // The scroll pane is only a frame: the choices inside take the focus.
        root.setFocusTraversable(false);
        root.getStyleClass().add(ROOT_CLASS);
        root.getStylesheets().add(URI);

        markPicked();
        show.accept(look);
    }

    /**
     * Returns the tab's content, for the Look tab of Layout C.
     *
     * @return the scrolling sections
     */
    Node node() {
        return root;
    }

    /**
     * Returns the look chosen now.
     *
     * @return the look last shown
     */
    Look look() {
        return look;
    }

    /**
     * Returns the swatches.
     *
     * @return one per Dice Set, in grid order (row by row)
     */
    List<Button> diceSetButtons() {
        return List.copyOf(diceSetButtons);
    }

    /**
     * Returns the Tray list's entries.
     *
     * @return one per Tray, in order
     */
    List<Button> trayButtons() {
        return List.copyOf(trayButtons);
    }

    /**
     * Returns the Light Rig list's entries.
     *
     * @return Studio, Lamp and Window
     */
    List<Button> lightRigButtons() {
        return List.copyOf(lightRigButtons);
    }

    /**
     * Returns the rows listing skipped Player Dice Sets.
     *
     * @return one per skipped file, reading "file: reason"
     */
    List<Label> skippedDiceSetRows() {
        return List.copyOf(skippedDiceSetRows);
    }

    /**
     * Returns the rows listing skipped Player Trays.
     *
     * @return one per skipped file, reading "file: reason"
     */
    List<Label> skippedTrayRows() {
        return List.copyOf(skippedTrayRows);
    }

    /** @return the Dice Set section: heading, swatch grid, skipped files */
    Node diceSetSection() {
        return diceSetSection;
    }

    /** @return the Tray section: heading, list, skipped files */
    Node traySection() {
        return traySection;
    }

    /** @return the Light Rig section: heading and list */
    Node lightRigSection() {
        return lightRigSection;
    }

    /**
     * Returns the Look tab's own stylesheet, from the palette like {@link Stylesheet}'s and more specific than its
     * rules, so it wins wherever the two sit.
     *
     * @return CSS in JavaFX's dialect
     */
    static String css() {
        String surface = Stylesheet.hex(Palette.SURFACE);
        String raised = Stylesheet.hex(Palette.RAISED);
        String outline = Stylesheet.hex(Palette.OUTLINE);
        String text = Stylesheet.hex(Palette.TEXT);
        String muted = Stylesheet.hex(Palette.TEXT_MUTED);
        String accent = Stylesheet.hex(Palette.ACCENT);
        String focus = Stylesheet.hex(Palette.FOCUS);
        String scope = "." + Stylesheet.ROOT_CLASS + " ." + ROOT_CLASS;
        return String.join("\n",
                scope + ", " + scope + " > .viewport { -fx-background-color: " + surface + "; }",
                scope + " .look-sections { -fx-padding: 12 12 16 12; -fx-spacing: 18; }",
                scope + " .look-section { -fx-spacing: 8; }",
                scope + " .look-heading { -fx-text-fill: " + text + "; -fx-font-size: 13px; -fx-font-weight: bold; }",
                scope + " .swatch-grid { -fx-hgap: 4; -fx-vgap: 6; }",
                scope + " .look-list { -fx-spacing: 4; }",
                // A swatch is a tile on the surface; the chosen one is ringed in the accent (a shape as well as a
                // colour), and the focus ring sits outside the accent ring when both apply.
                scope + " .swatch { -fx-background-color: transparent; -fx-padding: 5 2 5 2; }",
                scope + " .swatch:hover { -fx-background-color: " + raised + "; }",
                scope + " .swatch:picked { -fx-background-color: " + accent + ", " + surface
                        + "; -fx-background-insets: 0, 2; -fx-background-radius: 6, 4; }",
                scope + " .swatch:focused { -fx-background-color: " + focus + ", " + surface
                        + "; -fx-background-insets: 0, 2; -fx-background-radius: 6, 4; }",
                scope + " .swatch:picked:focused { -fx-background-color: " + focus + ", " + surface + ", " + accent
                        + ", " + surface + "; -fx-background-insets: 0, 2, 3, 5; -fx-background-radius: 6, 4, 4, 3; }",
                scope + " .swatch-disc { -fx-stroke: " + outline + "; -fx-stroke-width: 1; }",
                scope + " .swatch-name { -fx-text-fill: " + text + "; -fx-font-size: 11px; }",
                scope + " .yours { -fx-text-fill: " + muted + "; -fx-font-size: 11px; -fx-font-style: italic; }",
                // A list row is a raised button the sidebar's width; the chosen one has an accent bar down its
                // left edge and bold text, so it never rests on colour alone.
                scope + " .look-row { -fx-alignment: center-left; -fx-padding: 6 10 6 12; }",
                scope + " .look-row:picked { -fx-background-color: " + outline + ", " + accent + ", " + raised
                        + "; -fx-background-insets: 0, 1, 1 1 1 6; -fx-font-weight: bold; }",
                scope + " .look-row:picked:focused { -fx-background-color: " + focus + ", " + surface + ", "
                        + accent + ", " + raised + "; -fx-background-insets: 0, 2, 3, 3 3 3 8; }",
                scope + " .look-row-swatch { -fx-stroke: " + outline + "; -fx-stroke-width: 1; }",
                scope + " .skipped-file { -fx-text-fill: " + muted + "; -fx-font-size: 12px; -fx-graphic-text-gap: 6; }",
                "");
    }

    /** A section: its heading, its choices, and below them the files skipped from it, if any. */
    private static VBox section(String heading, Node choices, List<LookCatalogue.Skipped> skipped,
            List<Label> rows) {
        Label title = new Label(heading);
        title.getStyleClass().add("look-heading");
        VBox section = new VBox(title, choices);
        section.getStyleClass().add("look-section");
        for (LookCatalogue.Skipped file : skipped) {
            Label row = new Label(file.fileName() + ": " + file.reason(), Icons.warning());
            row.getStyleClass().add("skipped-file");
            row.setWrapText(true);
            row.setMaxWidth(Double.MAX_VALUE);
            row.setAccessibleText("Skipped " + file.fileName() + ". " + file.reason());
            rows.add(row);
            section.getChildren().add(row);
        }
        return section;
    }

    /** A one-column list of entries, moved through with the arrow keys. */
    private static <T> VBox list(List<T> items, Function<T, Button> row, List<Button> buttons) {
        VBox list = new VBox();
        list.getStyleClass().add("look-list");
        for (T item : items) {
            Button button = row.apply(item);
            button.getStyleClass().add("look-row");
            button.setMaxWidth(Double.MAX_VALUE);
            buttons.add(button);
        }
        list.getChildren().addAll(buttons);
        moveWithArrows(list, buttons, 1);
        return list;
    }

    /**
     * Moves the focus through a grid of entries with the arrow keys, stopping at its edges. A filter, so it runs
     * before the button's own key handling, whose focus traversal would otherwise leave the grid on an arrow key.
     */
    private static void moveWithArrows(Pane grid, List<Button> buttons, int columns) {
        grid.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            int step = switch (event.getCode()) {
                case LEFT, KP_LEFT -> -1;
                case RIGHT, KP_RIGHT -> 1;
                case UP, KP_UP -> -columns;
                case DOWN, KP_DOWN -> columns;
                default -> 0;
            };
            if (step == 0 || event.isAltDown() || event.isControlDown() || event.isMetaDown()) {
                return;
            }
            int from = buttons.indexOf(event.getTarget());
            if (from < 0) {
                return;
            }
            event.consume();
            int to = from + step;
            // A step off the grid stays put, as does a step down into the last row's gap.
            if (to >= 0 && to < buttons.size()) {
                buttons.get(to).requestFocus();
            }
        });
    }

    /** A Dice Set's swatch: a disc of its colours, its name, and "yours" if it is the Player's. */
    private Button swatch(DiceSet set) {
        // Translucent sets show the colour light takes through them; the body colour alone reads near-black.
        Circle disc = new Circle(DISC_RADIUS,
                colour(set.translucent() ? set.transmission().colour() : set.base()));
        disc.getStyleClass().add("swatch-disc");
        StackPane picture = new StackPane(disc);
        if (set.hasSwirl()) {
            Circle swirl = new Circle(DISC_RADIUS * 0.45, colour(set.swirl().colour()));
            swirl.setTranslateX(DISC_RADIUS * 0.35);
            swirl.setTranslateY(-DISC_RADIUS * 0.3);
            picture.getChildren().add(swirl);
        }
        // A pip of the ink, as a Numeral would show it.
        picture.getChildren().add(new Circle(DISC_RADIUS * 0.22, colour(set.ink().colour())));
        Label name = new Label(set.name());
        name.getStyleClass().add("swatch-name");
        name.setWrapText(true);
        name.setAlignment(Pos.TOP_CENTER);
        name.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        // Two lines at most; a longer name (a Player's) ends in an ellipsis, and the accessible name has it whole.
        // Its height is fixed too: asked with no width, a label wants one line and would ellipsise at once.
        name.setPrefWidth(SWATCH_WIDTH - 6);
        name.setMinHeight(NAME_HEIGHT);
        name.setPrefHeight(NAME_HEIGHT);
        name.setMaxHeight(NAME_HEIGHT);
        VBox tile = new VBox(3, picture, name);
        tile.setAlignment(Pos.TOP_CENTER);
        if (catalogue.yours(set)) {
            tile.getChildren().add(yours());
        }
        Button swatch = new Button();
        swatch.setGraphic(tile);
        swatch.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        swatch.getStyleClass().add("swatch");
        swatch.setPrefWidth(SWATCH_WIDTH);
        swatch.setMinWidth(SWATCH_WIDTH);
        swatch.setMaxWidth(SWATCH_WIDTH);
        // A fixed height: a Button sizes a graphic holding a wrapping label far too tall (192 px here), and equal
        // tiles make an even grid whether or not a swatch has the "yours" line.
        swatch.setMinHeight(SWATCH_HEIGHT);
        swatch.setPrefHeight(SWATCH_HEIGHT);
        swatch.setMaxHeight(SWATCH_HEIGHT);
        swatch.setOnAction(event -> pickDiceSet(set));
        return swatch;
    }

    /** A Tray's row: a chip of its floor in its walls, its name, and "yours" if it is the Player's. */
    private Button trayRow(Tray tray) {
        Rectangle chip = new Rectangle(22, 14, colour(tray.floor().colour()));
        chip.getStyleClass().add("look-row-swatch");
        Rectangle walls = new Rectangle(26, 18, colour(tray.walls().colour()));
        walls.getStyleClass().add("look-row-swatch");
        StackPane picture = new StackPane(walls, chip);
        Label name = new Label(tray.name());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(8, picture, name, gap);
        row.setAlignment(Pos.CENTER_LEFT);
        if (catalogue.yours(tray)) {
            row.getChildren().add(yours());
        }
        Button button = new Button();
        button.setGraphic(row);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setOnAction(event -> pickTray(tray));
        return button;
    }

    /** A Light Rig's row: its name. */
    private Button lightRigRow(LightRig rig) {
        Button button = new Button(displayName(rig));
        button.setOnAction(event -> pickLightRig(rig));
        return button;
    }

    /** The "yours" mark on a Player's own entry. */
    private static Label yours() {
        Label mark = new Label("yours");
        mark.getStyleClass().add("yours");
        return mark;
    }

    /** Picks a Dice Set: shown at once if it changes the look, and kept unless it is what is stored already. */
    private void pickDiceSet(DiceSet set) {
        settle(DICE_SET, set.identity(), new Look(set, look.tray(), look.lightRig()));
    }

    /** Picks a Tray, as {@link #pickDiceSet} does. */
    private void pickTray(Tray tray) {
        settle(TRAY, tray.identity(), new Look(look.diceSet(), tray, look.lightRig()));
    }

    /** Picks a Light Rig, as {@link #pickDiceSet} does. */
    private void pickLightRig(LightRig rig) {
        settle(LIGHT_RIG, rig, new Look(look.diceSet(), look.tray(), rig));
    }

    /**
     * Stores a pick and shows its look. A pick of what is stored writes nothing (a first run's pick of Onyx leaves
     * the file alone); a pick of the fallback shown for a missing choice does write, replacing the missing one.
     */
    private <T> void settle(Setting<T> setting, T value, Look picked) {
        if (!settings.get(setting).equals(value)) {
            settings.set(setting, value);
        }
        if (!picked.equals(look)) {
            look = picked;
            markPicked();
            show.accept(look);
        }
    }

    /** Marks the chosen entry of each section, for the eye, the screen reader and the Tab key. */
    private void markPicked() {
        List<DiceSet> sets = catalogue.diceSets().diceSets();
        for (int i = 0; i < sets.size(); i++) {
            DiceSet set = sets.get(i);
            mark(diceSetButtons.get(i), "Dice Set " + set.name(), catalogue.yours(set),
                    set.identity().equals(look.diceSet().identity()));
        }
        List<Tray> trays = catalogue.trays();
        for (int i = 0; i < trays.size(); i++) {
            Tray tray = trays.get(i);
            mark(trayButtons.get(i), "Tray " + tray.name(), catalogue.yours(tray),
                    tray.identity().equals(look.tray().identity()));
        }
        LightRig[] rigs = LightRig.values();
        for (int i = 0; i < rigs.length; i++) {
            mark(lightRigButtons.get(i), "Light Rig " + displayName(rigs[i]), false, rigs[i] == look.lightRig());
        }
    }

    /** One entry's mark: the pseudo-class, its spoken name, and whether Tab stops on it. */
    private static void mark(Button button, String name, boolean yours, boolean picked) {
        button.pseudoClassStateChanged(PICKED, picked);
        button.setAccessibleText(name + (yours ? ", yours" : "") + (picked ? ", chosen" : ""));
        button.setFocusTraversable(picked);
    }

    /** A rig as the Player reads it: "Studio", "Lamp", "Window". */
    private static String displayName(LightRig rig) {
        String name = rig.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** A file's sRGB colour as JavaFX's. */
    private static Color colour(SrgbColour colour) {
        return Color.rgb(colour.red(), colour.green(), colour.blue());
    }
}
