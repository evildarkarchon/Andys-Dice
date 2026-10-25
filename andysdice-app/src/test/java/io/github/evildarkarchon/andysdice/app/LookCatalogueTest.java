package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the Player Files (spec §3.3, §3.4, ADR-0009, ADR-0010): the {@code dicesets\} and {@code trays\} folders
 * of the data folder are read once, listed after the built-ins in file-name order, and a bad or clashing file is
 * skipped with its reason and a Notice. Pure: real files in a temp folder, no toolkit.
 */
class LookCatalogueTest {

    private static final String DICE_SET = "format = 1\nname = %s\nbase = #102030\n";
    private static final String TRAY = "format = 1\nname = %s\noutline = oval\nfloor.surface = wood\n";

    @TempDir
    Path data;

    private final List<Notice> notices = new ArrayList<>();

    private LookCatalogue load() {
        return LookCatalogue.load(data, notices::add);
    }

    private void diceSetFile(String fileName, String text) throws IOException {
        write(LookCatalogue.DICE_SETS_FOLDER, fileName, text.getBytes(StandardCharsets.UTF_8));
    }

    private void trayFile(String fileName, String text) throws IOException {
        write(LookCatalogue.TRAYS_FOLDER, fileName, text.getBytes(StandardCharsets.UTF_8));
    }

    private void write(String folder, String fileName, byte[] bytes) throws IOException {
        Files.createDirectories(data.resolve(folder));
        Files.write(data.resolve(folder).resolve(fileName), bytes);
    }

    private static List<String> identities(List<DiceSet> sets) {
        return sets.stream().map(DiceSet::identity).toList();
    }

    @Test
    void withNoPlayerFoldersOnlyTheBuiltInsAreListedAndNothingIsSkipped() {
        LookCatalogue look = load();

        assertThat(identities(look.diceSets().diceSets())).containsExactly("01-onyx", "02-ivory", "03-crimson",
                "04-vortex-blue", "05-jade-marble", "06-speckled-stone", "07-pearl-violet", "08-steel", "09-gold",
                "10-gunmetal", "11-smoke", "12-ruby", "13-emerald", "14-nebula");
        assertThat(look.trays()).extracting(Tray::identity)
                .containsExactly("01-green-felt", "02-leather-hexagon", "03-walnut-board");
        assertThat(look.skippedDiceSets()).isEmpty();
        assertThat(look.skippedTrays()).isEmpty();
        assertThat(notices).isEmpty();
        // Reading never makes the folders either: the app writes nothing there.
        assertThat(data.resolve(LookCatalogue.DICE_SETS_FOLDER)).doesNotExist();
        assertThat(data.resolve(LookCatalogue.TRAYS_FOLDER)).doesNotExist();
    }

    @Test
    void playerDiceSetsComeAfterTheBuiltInsInFileNameOrderAndAreMarkedYours() throws IOException {
        diceSetFile("brass.diceset", DICE_SET.formatted("Brass"));
        diceSetFile("Amber.diceset", DICE_SET.formatted("Amber"));
        diceSetFile("cobalt.diceset", DICE_SET.formatted("Cobalt"));

        LookCatalogue look = load();

        List<String> ids = identities(look.diceSets().diceSets());
        assertThat(ids).hasSize(17).endsWith("14-nebula", "Amber", "brass", "cobalt");
        assertThat(look.diceSets().find("brass")).get().extracting(DiceSet::name).isEqualTo("Brass");
        assertThat(look.yours(look.diceSets().find("Amber").orElseThrow())).isTrue();
        assertThat(look.yours(look.diceSets().fallback())).isFalse();
        assertThat(notices).isEmpty();
    }

    @Test
    void aBadDiceSetIsSkippedWithItsReasonAndANoticeWhileTheGoodOnesLoad() throws IOException {
        diceSetFile("good.diceset", DICE_SET.formatted("Good"));
        diceSetFile("no-format.diceset", "name = Lost\nbase = #ffffff\n");
        diceSetFile("future.diceset", "format = 2\nname = Future\n");

        LookCatalogue look = load();

        assertThat(identities(look.diceSets().diceSets())).contains("good").doesNotContain("no-format", "future");
        assertThat(look.skippedDiceSets()).extracting(LookCatalogue.Skipped::fileName)
                .containsExactly("future.diceset", "no-format.diceset");
        assertThat(look.skippedDiceSets().get(0).reason()).contains("newer");
        assertThat(look.skippedDiceSets().get(1).reason()).contains("no format");
        assertThat(notices).extracting(Notice::title)
                .containsExactly("future.diceset was skipped", "no-format.diceset was skipped");
        assertThat(notices.get(1).detail()).isEqualTo(look.skippedDiceSets().get(1).reason());
    }

    @Test
    void aFileThatIsNotUtf8IsSkippedAsUnreadable() throws IOException {
        // "name = caf" then a lone Latin-1 e-acute: not UTF-8.
        write(LookCatalogue.DICE_SETS_FOLDER, "latin1.diceset",
                new byte[] {'f', 'o', 'r', 'm', 'a', 't', '=', '1', '\n', 'n', 'a', 'm', 'e', '=', 'c', 'a', 'f',
                    (byte) 0xe9, '\n'});

        LookCatalogue look = load();

        assertThat(look.skippedDiceSets()).singleElement().satisfies(skipped -> {
            assertThat(skipped.fileName()).isEqualTo("latin1.diceset");
            assertThat(skipped.reason()).isEqualTo("It isn't UTF-8 text.");
        });
    }

    @Test
    void aPlayerFileReusingABuiltInIdentityIsSkippedAndTheBuiltInStays() throws IOException {
        diceSetFile("01-onyx.diceset", DICE_SET.formatted("Fake onyx"));
        // On Windows these name the same file as the built-in would; a clash ignores case.
        diceSetFile("02-IVORY.diceset", DICE_SET.formatted("Fake ivory"));

        LookCatalogue look = load();

        assertThat(look.diceSets().diceSets()).hasSize(14);
        assertThat(look.diceSets().find("01-onyx")).get().extracting(DiceSet::name).isEqualTo("Onyx / white");
        assertThat(look.yours(look.diceSets().find("01-onyx").orElseThrow())).isFalse();
        assertThat(look.skippedDiceSets()).extracting(LookCatalogue.Skipped::fileName)
                .containsExactly("01-onyx.diceset", "02-IVORY.diceset");
        assertThat(look.skippedDiceSets().getFirst().reason())
                .isEqualTo("Its name, 01-onyx, is already a built-in Dice Set's.");
        assertThat(notices).hasSize(2);
    }

    @Test
    void twoPlayerFilesWhoseNamesDifferOnlyInCaseClashAndTheLaterIsSkipped() throws IOException {
        diceSetFile("Opal.diceset", DICE_SET.formatted("Opal one"));
        diceSetFile("opal.diceset", DICE_SET.formatted("Opal two"));
        // A case-insensitive file system holds only one of them; then there is nothing to clash.
        long files;
        try (Stream<Path> listed = Files.list(data.resolve(LookCatalogue.DICE_SETS_FOLDER))) {
            files = listed.count();
        }

        LookCatalogue look = load();

        if (files == 2) {
            assertThat(identities(look.diceSets().diceSets())).endsWith("Opal").doesNotContain("opal");
            assertThat(look.skippedDiceSets()).singleElement().satisfies(skipped -> {
                assertThat(skipped.fileName()).isEqualTo("opal.diceset");
                assertThat(skipped.reason()).isEqualTo("Its name, opal, is already taken by Opal.diceset.");
            });
        } else {
            assertThat(look.diceSets().diceSets()).hasSize(15);
            assertThat(look.skippedDiceSets()).isEmpty();
        }
    }

    @Test
    void otherFilesAndFoldersInThePlayerFolderAreIgnored() throws IOException {
        diceSetFile("notes.txt", "format = 1\n");
        diceSetFile("backup.diceset.old", DICE_SET.formatted("Old"));
        Files.createDirectories(data.resolve(LookCatalogue.DICE_SETS_FOLDER).resolve("folder.diceset"));
        diceSetFile("Loud.DICESET", DICE_SET.formatted("Loud"));

        LookCatalogue look = load();

        // An upper-case extension is still a Dice Set file; its identity is the name before it.
        assertThat(identities(look.diceSets().diceSets())).hasSize(15).endsWith("Loud");
        assertThat(look.skippedDiceSets()).isEmpty();
        assertThat(notices).isEmpty();
    }

    @Test
    void playerTraysComeAfterTheBuiltInsAndABadOrClashingOneIsSkipped() throws IOException {
        trayFile("zz-oval.tray", TRAY.formatted("Oval"));
        trayFile("a-oval.tray", TRAY.formatted("A oval"));
        trayFile("01-green-felt.tray", TRAY.formatted("Fake felt"));
        trayFile("broken.tray", "name = Broken\n");

        LookCatalogue look = load();

        assertThat(look.trays()).extracting(Tray::identity).containsExactly("01-green-felt", "02-leather-hexagon",
                "03-walnut-board", "a-oval", "zz-oval");
        assertThat(look.trays().getFirst().name()).isEqualTo("Green felt");
        assertThat(look.yours(look.trays().get(3))).isTrue();
        assertThat(look.yours(look.trays().getFirst())).isFalse();
        assertThat(look.skippedTrays()).extracting(LookCatalogue.Skipped::fileName)
                .containsExactly("01-green-felt.tray", "broken.tray");
        assertThat(look.skippedTrays().getFirst().reason())
                .isEqualTo("Its name, 01-green-felt, is already a built-in Tray's.");
        assertThat(look.skippedDiceSets()).isEmpty();
        assertThat(notices).extracting(Notice::title)
                .containsExactly("01-green-felt.tray was skipped", "broken.tray was skipped");
    }

    @Test
    void aMissingChoiceFallsBackToOnyxAndTheDefaultTray() throws IOException {
        diceSetFile("mine.diceset", DICE_SET.formatted("Mine"));
        trayFile("mine.tray", TRAY.formatted("Mine"));

        LookCatalogue look = load();

        assertThat(look.diceSet("gone").identity()).isEqualTo("01-onyx");
        assertThat(look.tray("gone").identity()).isEqualTo("01-green-felt");
        assertThat(look.diceSet("mine").name()).isEqualTo("Mine");
        assertThat(look.tray("mine").name()).isEqualTo("Mine");
        assertThat(look.diceSet("12-ruby").identity()).isEqualTo("12-ruby");
    }

    @Test
    void readingNeverWritesTouchesOrAddsAPlayerFile() throws IOException {
        diceSetFile("good.diceset", DICE_SET.formatted("Good"));
        diceSetFile("bad.diceset", "nothing here\n");
        trayFile("good.tray", TRAY.formatted("Good"));
        FileTime old = FileTime.fromMillis(1_000_000_000_000L);
        for (String folder : List.of(LookCatalogue.DICE_SETS_FOLDER, LookCatalogue.TRAYS_FOLDER)) {
            try (Stream<Path> files = Files.list(data.resolve(folder))) {
                for (Path file : files.toList()) {
                    Files.setLastModifiedTime(file, old);
                }
            }
        }
        Map<Path, String> before = snapshot();

        load();

        assertThat(snapshot()).isEqualTo(before);
    }

    /** Every file under the data folder, with its modification time and bytes. */
    private Map<Path, String> snapshot() throws IOException {
        Map<Path, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(data)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(data.relativize(file), Files.getLastModifiedTime(file) + " "
                        + new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            }
        }
        return files;
    }
}
