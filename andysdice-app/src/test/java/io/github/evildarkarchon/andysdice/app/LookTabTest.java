package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.render.LightRig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

/**
 * The Look tab (spec §3.2 "Look tab", §3.3, §3.4, §3.6 "Settings"), headless over the stand-in tray: the swatch
 * grid and the two lists, picking by mouse and keyboard, the change reaching the Tray through the Tray seam and the
 * next Throw's physics, the choices kept by identity in {@code settings.properties} with their fallbacks, and the
 * skipped Player Files listed with their reasons.
 */
class LookTabTest extends HeadlessUiTest {

    private static final String DICE_SET = "format = 1\nname = %s\nbase = #405060\n";
    private static final String TRAY = "format = 1\nname = %s\noutline = oval\n";

    private SettingsStore settings;
    private LookTab look;

    /**
     * Reads the Player Files and the settings (over the base class's writer) and fills the Look tab, as start-up
     * does; then shows the tab.
     */
    private void attachLook() throws Exception {
        onFx(() -> {
            settings = new SettingsStore(persistence);
            look = session.attachLook(LookCatalogue.load(dataFolder, session.notices()::post), settings);
            ui().tabs().getSelectionModel().select(ui().lookTab());
        });
        pulse(stage.getScene());
    }

    private void playerFile(String folder, String fileName, String text) throws IOException {
        Files.createDirectories(dataFolder.resolve(folder));
        Files.writeString(dataFolder.resolve(folder).resolve(fileName), text, StandardCharsets.UTF_8);
    }

    private void settingsFile(String... lines) throws IOException {
        Files.writeString(dataFolder.resolve(Settings.FILE_NAME),
                "#andysdice settings.properties 1\n" + String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    /** The settings file's lines once every queued write is done. */
    private List<String> savedSettings() throws Exception {
        persistence.flush();
        return Files.readAllLines(dataFolder.resolve(Settings.FILE_NAME), StandardCharsets.UTF_8);
    }

    private static boolean picked(Button button) {
        return button.getPseudoClassStates().contains(LookTab.PICKED);
    }

    /** The button showing the choice with this accessible-name prefix. */
    private static Button named(List<Button> buttons, String name) {
        return buttons.stream().filter(b -> b.getAccessibleText().startsWith(name)).findFirst().orElseThrow();
    }

    @Test
    void theGridListsTheBuiltInsThenThePlayersOwnMarkedYours() throws Exception {
        playerFile(LookCatalogue.DICE_SETS_FOLDER, "zz-mine.diceset", DICE_SET.formatted("Mine"));
        playerFile(LookCatalogue.TRAYS_FOLDER, "my-oval.tray", TRAY.formatted("My oval"));
        attachLook();

        List<String> swatches = fromFx(() -> look.diceSetButtons().stream().map(Button::getAccessibleText).toList());
        assertThat(swatches).hasSize(15);
        assertThat(swatches.getFirst()).isEqualTo("Dice Set Onyx / white, chosen");
        assertThat(swatches.get(13)).isEqualTo("Dice Set Nebula / white");
        assertThat(swatches.getLast()).isEqualTo("Dice Set Mine, yours");
        assertThat(fromFx(() -> look.trayButtons().stream().map(Button::getAccessibleText).toList()))
                .containsExactly("Tray Green felt, chosen", "Tray Leather hexagon", "Tray Walnut board",
                        "Tray My oval, yours");
        assertThat(fromFx(() -> look.lightRigButtons().stream().map(Button::getText).toList()))
                .containsExactly("Studio", "Lamp", "Window");
        // The "yours" mark is shown, not only spoken.
        assertThat(fromFx(() -> texts(look.diceSetButtons().getLast()))).contains("yours");
        assertThat(fromFx(() -> texts(look.trayButtons().getLast()))).contains("yours");
    }

    @Test
    void aFirstRunShowsOnyxTheDefaultTrayAndStudioOnTheTray() throws Exception {
        attachLook();

        Look shown = fromFx(() -> tray.look());
        assertThat(shown.diceSet().identity()).isEqualTo("01-onyx");
        assertThat(shown.tray().identity()).isEqualTo(BuiltInTrays.DEFAULT_IDENTITY);
        assertThat(shown.lightRig()).isEqualTo(LightRig.STUDIO);
        assertThat(fromFx(() -> picked(look.diceSetButtons().getFirst()))).isTrue();
        assertThat(fromFx(() -> picked(look.trayButtons().getFirst()))).isTrue();
        assertThat(fromFx(() -> picked(look.lightRigButtons().getFirst()))).isTrue();
        // Nothing was chosen, so nothing is written.
        assertThat(dataFolder.resolve(Settings.FILE_NAME)).doesNotExist();
    }

    @Test
    void clickingASwatchPutsThatDiceSetOnTheTrayAndSavesItsIdentity() throws Exception {
        attachLook();

        onFx(() -> named(look.diceSetButtons(), "Dice Set Ruby").fire());

        assertThat(fromFx(() -> tray.look().diceSet().identity())).isEqualTo("12-ruby");
        assertThat(fromFx(() -> picked(named(look.diceSetButtons(), "Dice Set Ruby")))).isTrue();
        assertThat(fromFx(() -> picked(look.diceSetButtons().getFirst()))).isFalse();
        assertThat(fromFx(() -> named(look.diceSetButtons(), "Dice Set Ruby").getAccessibleText()))
                .isEqualTo("Dice Set Ruby / gold, chosen");
        assertThat(savedSettings()).contains("look.diceSet=12-ruby");
    }

    @Test
    void pickingATrayChangesTheTrayDrawnAndTheTrayTheNextRollIsThrownInto() throws Exception {
        attachLook();
        int looksBefore = fromFx(() -> tray.looks().size());

        onFx(() -> named(look.trayButtons(), "Tray Leather hexagon").fire());

        assertThat(fromFx(() -> tray.look().tray().identity())).isEqualTo("02-leather-hexagon");
        assertThat(fromFx(() -> tray.looks().size())).isEqualTo(looksBefore + 1);
        assertThat(savedSettings()).contains("look.tray=02-leather-hexagon");
        faces.then(3);
        roll("1d6");
        playToRest();
        assertThat(simulator.requests.getLast().tray())
                .isEqualTo(BuiltInTrays.find("02-leather-hexagon").orElseThrow().geometry());
    }

    @Test
    void aTrayPickedWhileTheDiceMoveAppearsOnTheTrayOnlyOnceTheyRest() throws Exception {
        attachLook();
        faces.then(3);
        roll("1d6");
        advance(0.1);

        onFx(() -> named(look.trayButtons(), "Tray Leather hexagon").fire());

        // The pick is taken and kept at once, but the Dice in motion stay in the Tray they were thrown into.
        assertThat(fromFx(() -> picked(named(look.trayButtons(), "Tray Leather hexagon")))).isTrue();
        assertThat(savedSettings()).contains("look.tray=02-leather-hexagon");
        onFx(() -> {
            assertThat(rollState().status()).isInstanceOf(RollStatus.Playing.class);
            // Frame by frame to the rest, two minutes at most, as playToRest does.
            for (int frame = 0; rollState().underWay() && frame < 7200; frame++) {
                assertThat(tray.look().tray().identity()).as("at %.3f s", clock.time())
                        .isEqualTo(BuiltInTrays.DEFAULT_IDENTITY);
                clock.advance(1.0 / 60);
            }
        });
        assertThat(fromFx(() -> tray.phase())).isEqualTo(StandInTray.Phase.AT_REST);
        assertThat(fromFx(() -> tray.look().tray().identity())).isEqualTo("02-leather-hexagon");
        // And the next Roll is thrown into it.
        faces.then(5);
        roll("1d6");
        assertThat(simulator.requests.getLast().tray())
                .isEqualTo(BuiltInTrays.find("02-leather-hexagon").orElseThrow().geometry());
    }

    @Test
    void pickingALightRigChangesTheLightAndSavesIt() throws Exception {
        attachLook();

        onFx(() -> named(look.lightRigButtons(), "Light Rig Window").fire());

        assertThat(fromFx(() -> tray.look().lightRig())).isEqualTo(LightRig.WINDOW);
        assertThat(savedSettings()).contains("look.lightRig=WINDOW");
    }

    @Test
    void pickingWhatIsAlreadyChosenChangesNothing() throws Exception {
        attachLook();
        int looksBefore = fromFx(() -> tray.looks().size());

        onFx(() -> look.diceSetButtons().getFirst().fire());

        assertThat(fromFx(() -> tray.looks().size())).isEqualTo(looksBefore);
        assertThat(dataFolder.resolve(Settings.FILE_NAME)).doesNotExist();
    }

    @Test
    void theSavedChoicesAreShownAtStartUpIncludingThePlayersOwn() throws Exception {
        playerFile(LookCatalogue.DICE_SETS_FOLDER, "mine.diceset", DICE_SET.formatted("Mine"));
        playerFile(LookCatalogue.TRAYS_FOLDER, "my-oval.tray", TRAY.formatted("My oval"));
        settingsFile("look.diceSet=mine", "look.lightRig=LAMP", "look.tray=my-oval");

        attachLook();

        Look shown = fromFx(() -> tray.look());
        assertThat(shown.diceSet().name()).isEqualTo("Mine");
        assertThat(shown.tray().name()).isEqualTo("My oval");
        assertThat(shown.lightRig()).isEqualTo(LightRig.LAMP);
        assertThat(fromFx(() -> picked(look.diceSetButtons().getLast()))).isTrue();
        assertThat(fromFx(() -> picked(look.diceSetButtons().getFirst()))).isFalse();
        assertThat(fromFx(() -> picked(named(look.lightRigButtons(), "Light Rig Lamp")))).isTrue();
        // And the next Roll is thrown into it.
        roll("1d6");
        assertThat(simulator.requests).isNotEmpty()
                .allSatisfy(request -> assertThat(request.tray()).isEqualTo(shown.tray().geometry()));
    }

    @Test
    void aMissingChoiceFallsBackToOnyxTheDefaultTrayAndStudioWithoutForgettingIt() throws Exception {
        // A Player Dice Set chosen last run, and a Tray file since deleted; and a rig this build doesn't know.
        playerFile(LookCatalogue.DICE_SETS_FOLDER, "mine.diceset", "name = Mine, now broken\n");
        settingsFile("look.diceSet=mine", "look.lightRig=DISCO", "look.tray=deleted-tray");

        attachLook();

        Look shown = fromFx(() -> tray.look());
        assertThat(shown.diceSet().identity()).isEqualTo("01-onyx");
        assertThat(shown.tray().identity()).isEqualTo(BuiltInTrays.DEFAULT_IDENTITY);
        assertThat(shown.lightRig()).isEqualTo(LightRig.STUDIO);
        assertThat(fromFx(() -> picked(look.diceSetButtons().getFirst()))).isTrue();
        // And the next Roll is thrown into the default Tray.
        roll("1d6");
        assertThat(simulator.requests).isNotEmpty().allSatisfy(request -> assertThat(request.tray())
                .isEqualTo(BuiltInTrays.find(BuiltInTrays.DEFAULT_IDENTITY).orElseThrow().geometry()));
        // The stored choice stays, so mending the file brings it back next run.
        assertThat(savedSettings()).contains("look.diceSet=mine", "look.tray=deleted-tray");
    }

    @Test
    void skippedPlayerFilesStayListedUnderTheirSectionWithTheReasonAndPostANotice() throws Exception {
        playerFile(LookCatalogue.DICE_SETS_FOLDER, "broken.diceset", "name = Broken\n");
        playerFile(LookCatalogue.DICE_SETS_FOLDER, "good.diceset", DICE_SET.formatted("Good"));
        playerFile(LookCatalogue.TRAYS_FOLDER, "01-green-felt.tray", TRAY.formatted("Copy"));

        attachLook();

        assertThat(fromFx(() -> look.skippedDiceSetRows().stream().map(Labeled::getText).toList()))
                .containsExactly("broken.diceset: Dice Set broken has no format.");
        assertThat(fromFx(() -> look.skippedTrayRows().stream().map(Labeled::getText).toList()))
                .containsExactly("01-green-felt.tray: Its name, 01-green-felt, is already a built-in Tray's.");
        assertThat(fromFx(() -> session.notices().all().stream().map(Notice::title).toList()))
                .containsExactly("broken.diceset was skipped", "01-green-felt.tray was skipped");
        assertThat(fromFx(() -> look.diceSetButtons().size())).isEqualTo(15);
        // Each row sits under its own section, inside the tab.
        assertThat(fromFx(() -> isInside(look.skippedDiceSetRows().getFirst(), look.diceSetSection()))).isTrue();
        assertThat(fromFx(() -> isInside(look.skippedTrayRows().getFirst(), look.traySection()))).isTrue();
        assertThat(fromFx(() -> isInside(look.diceSetSection(), ui().lookTab().getContent()))).isTrue();
    }

    @Test
    void theArrowKeysMoveThroughTheGridAndSpacePicks() throws Exception {
        attachLook();
        onFx(() -> look.diceSetButtons().getFirst().requestFocus());

        onFx(() -> robot().keyType(KeyCode.RIGHT));
        assertThat(fromFx(() -> look.diceSetButtons().get(1).isFocused())).isTrue();
        // Moving is not picking.
        assertThat(fromFx(() -> tray.look().diceSet().identity())).isEqualTo("01-onyx");

        onFx(() -> robot().keyType(KeyCode.DOWN));
        int below = 1 + LookTab.SWATCH_COLUMNS;
        assertThat(fromFx(() -> look.diceSetButtons().get(below).isFocused())).isTrue();
        onFx(() -> robot().keyType(KeyCode.LEFT));
        onFx(() -> robot().keyType(KeyCode.UP));
        assertThat(fromFx(() -> look.diceSetButtons().getFirst().isFocused())).isTrue();
        // The grid's edges hold the focus: nothing before the first swatch.
        onFx(() -> robot().keyType(KeyCode.LEFT));
        assertThat(fromFx(() -> look.diceSetButtons().getFirst().isFocused())).isTrue();

        onFx(() -> robot().keyType(KeyCode.RIGHT));
        onFx(() -> robot().keyType(KeyCode.SPACE));
        assertThat(fromFx(() -> tray.look().diceSet().identity())).isEqualTo("02-ivory");
        assertThat(savedSettings()).contains("look.diceSet=02-ivory");
    }

    @Test
    void theArrowKeysMoveThroughTheListsAndEnterPicks() throws Exception {
        attachLook();
        onFx(() -> look.trayButtons().getFirst().requestFocus());

        onFx(() -> robot().keyType(KeyCode.DOWN));
        onFx(() -> robot().keyType(KeyCode.DOWN));
        assertThat(fromFx(() -> look.trayButtons().get(2).isFocused())).isTrue();
        onFx(() -> robot().keyType(KeyCode.ENTER));
        assertThat(fromFx(() -> tray.look().tray().identity())).isEqualTo("03-walnut-board");

        onFx(() -> look.lightRigButtons().getFirst().requestFocus());
        onFx(() -> robot().keyType(KeyCode.DOWN));
        onFx(() -> robot().keyType(KeyCode.UP));
        onFx(() -> robot().keyType(KeyCode.DOWN));
        onFx(() -> robot().keyType(KeyCode.ENTER));
        assertThat(fromFx(() -> tray.look().lightRig())).isEqualTo(LightRig.LAMP);
        // The list's last row holds the focus.
        onFx(() -> robot().keyType(KeyCode.DOWN));
        onFx(() -> robot().keyType(KeyCode.DOWN));
        assertThat(fromFx(() -> look.lightRigButtons().getLast().isFocused())).isTrue();
    }

    @Test
    void tabbingIntoASectionLandsOnItsChoice() throws Exception {
        attachLook();
        onFx(() -> named(look.diceSetButtons(), "Dice Set Steel").fire());

        assertThat(fromFx(() -> look.diceSetButtons().stream().filter(Node::isFocusTraversable)
                .map(Button::getAccessibleText).toList())).containsExactly("Dice Set Steel / black, chosen");
        assertThat(fromFx(() -> look.trayButtons().stream().filter(Node::isFocusTraversable).count()))
                .isEqualTo(1);
    }

    /** Every text shown inside a node, its own and its descendants'. */
    private static List<String> texts(Node node) {
        java.util.ArrayList<String> found = new java.util.ArrayList<>();
        if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isEmpty()) {
            found.add(labeled.getText());
        }
        if (node instanceof Labeled labeled && labeled.getGraphic() != null) {
            found.addAll(texts(labeled.getGraphic()));
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> found.addAll(texts(child)));
        }
        return found;
    }

    /** Whether {@code node} is {@code ancestor} or below it in the scene graph. */
    private static boolean isInside(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }
}
