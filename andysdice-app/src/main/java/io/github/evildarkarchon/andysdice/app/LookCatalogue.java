package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetFile;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.core.tray.TrayFile;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Everything the Look tab offers (spec §3.2 "Look tab", §3.3, §3.4): the built-in Dice Sets and Trays, then the
 * Player's own, read once at start-up from the data folder's {@value #DICE_SETS_FOLDER} and {@value #TRAYS_FOLDER}
 * folders, and the Player Files that were skipped, each with its reason.
 *
 * <p>A Player File is a {@code .diceset} or {@code .tray} file directly in its folder (the extension in any case).
 * Player Files are listed after the built-ins in file-name order. One is skipped, with a Notice, when it is
 * unreadable, isn't UTF-8, has a missing, malformed or newer {@code format} (ADR-0009, ADR-0010), or clashes: its
 * identity (the file name less the extension) is a built-in's or an earlier Player File's, ignoring case, since
 * the two would be one file on Windows and one choice in {@code settings.properties} would be ambiguous. The app
 * only ever reads these folders: it never writes, renames or creates anything in them.
 *
 * <p>Immutable once loaded, so any thread may share one.
 */
final class LookCatalogue {

    /** The data folder's folder of Player Dice Sets. */
    static final String DICE_SETS_FOLDER = "dicesets";

    /** The data folder's folder of Player Trays. */
    static final String TRAYS_FOLDER = "trays";

    private static final System.Logger LOG = System.getLogger(LookCatalogue.class.getName());

    /**
     * Windows Explorer's order (case ignored), with an exact comparison breaking ties so the order is total on a
     * case-sensitive file system too.
     */
    private static final Comparator<String> FILE_NAME_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    /**
     * A Player File that was skipped, listed under its section for as long as the app runs.
     *
     * @param fileName the file's name, such as {@code opal.diceset}
     * @param reason why, as a sentence, such as "It isn't UTF-8 text."
     */
    record Skipped(String fileName, String reason) {

        /** Checks both are present. */
        Skipped {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Parses one Player File's text, as {@link DiceSetFile#parse} and {@link TrayFile#parse} do. */
    @FunctionalInterface
    private interface Parser<T> {
        T parse(String identity, String text) throws IOException;
    }

    /** What one folder held: the files read, in file-name order, and the ones skipped, in the same order. */
    private record Folder<T>(List<T> read, List<Skipped> skipped) {}

    private final DiceSetCatalogue diceSets;
    private final Set<String> playerDiceSets;
    private final List<Tray> trays;
    private final Set<String> playerTrays;
    private final List<Skipped> skippedDiceSets;
    private final List<Skipped> skippedTrays;

    private LookCatalogue(Folder<DiceSet> playerSets, Folder<Tray> playerTrayFiles) {
        List<DiceSet> allSets = new ArrayList<>(DiceSetCatalogue.builtIn().diceSets());
        allSets.addAll(playerSets.read());
        // Clashes were skipped while reading, so the catalogue's no-duplicates check can't fail here.
        diceSets = new DiceSetCatalogue(allSets);
        playerDiceSets = playerSets.read().stream().map(DiceSet::identity).collect(Collectors.toUnmodifiableSet());
        List<Tray> allTrays = new ArrayList<>(BuiltInTrays.all());
        allTrays.addAll(playerTrayFiles.read());
        trays = List.copyOf(allTrays);
        playerTrays = playerTrayFiles.read().stream().map(Tray::identity).collect(Collectors.toUnmodifiableSet());
        skippedDiceSets = List.copyOf(playerSets.skipped());
        skippedTrays = List.copyOf(playerTrayFiles.skipped());
    }

    /**
     * Reads the Player Files once, at start-up, posting a Notice for each one skipped.
     *
     * @param dataFolder the data folder ({@link DataFolder}); a missing player folder simply holds no files
     * @param notices where each skipped file's Notice goes ({@code notices::post} in the app)
     * @return the catalogue: built-ins first, then the Player's own
     */
    static LookCatalogue load(Path dataFolder, Consumer<Notice> notices) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(notices, "notices");
        Folder<DiceSet> sets = read(dataFolder.resolve(DICE_SETS_FOLDER), DiceSetFile.EXTENSION, "Dice Set",
                DiceSetFile::parse, DiceSetCatalogue.builtIn().diceSets().stream().map(DiceSet::identity).toList(),
                notices);
        Folder<Tray> trays = read(dataFolder.resolve(TRAYS_FOLDER), TrayFile.EXTENSION, "Tray", TrayFile::parse,
                BuiltInTrays.all().stream().map(Tray::identity).toList(), notices);
        LOG.log(Level.INFO, "Player Files: " + sets.read().size() + " Dice Sets and " + trays.read().size()
                + " Trays read, " + (sets.skipped().size() + trays.skipped().size()) + " skipped, from "
                + dataFolder);
        return new LookCatalogue(sets, trays);
    }

    /**
     * Returns every Dice Set, for the swatch grid.
     *
     * @return the built-ins in Appendix B order, then the Player's own; Onyx is the fallback
     */
    DiceSetCatalogue diceSets() {
        return diceSets;
    }

    /**
     * Returns every Tray, for the Tray list.
     *
     * @return the built-ins in Appendix C order, the default first, then the Player's own
     */
    List<Tray> trays() {
        return trays;
    }

    /**
     * Tells whether a Dice Set is one of the Player's own, marked "yours" in the grid.
     *
     * @param diceSet a Dice Set of this catalogue
     * @return {@code true} if it came from a Player File
     */
    boolean yours(DiceSet diceSet) {
        return playerDiceSets.contains(diceSet.identity());
    }

    /**
     * Tells whether a Tray is one of the Player's own.
     *
     * @param tray a Tray of this catalogue
     * @return {@code true} if it came from a Player File
     */
    boolean yours(Tray tray) {
        return playerTrays.contains(tray.identity());
    }

    /**
     * Returns the Player Dice Set files that were skipped.
     *
     * @return each with its reason, in file-name order
     */
    List<Skipped> skippedDiceSets() {
        return skippedDiceSets;
    }

    /**
     * Returns the Player Tray files that were skipped.
     *
     * @return each with its reason, in file-name order
     */
    List<Skipped> skippedTrays() {
        return skippedTrays;
    }

    /**
     * Chooses the Dice Set a stored identity names (ADR-0009).
     *
     * @param identity the identity {@code settings.properties} holds
     * @return that Dice Set, or Onyx if there is none by that identity this run
     */
    DiceSet diceSet(String identity) {
        return diceSets.choose(identity);
    }

    /**
     * Chooses the Tray a stored identity names (ADR-0010).
     *
     * @param identity the identity {@code settings.properties} holds
     * @return that Tray, or the default (green felt) if there is none by that identity this run
     */
    Tray tray(String identity) {
        return trays.stream().filter(tray -> tray.identity().equals(identity)).findFirst()
                .orElseGet(BuiltInTrays::defaultTray);
    }

    /**
     * Reads one folder of Player Files.
     *
     * @param folder the folder; missing means no Player Files
     * @param extension the files' extension, matched ignoring case
     * @param kind what a file holds, for the reasons ("Dice Set")
     * @param parser parses a file's text
     * @param builtIns the built-ins' identities, which a Player File may not reuse
     * @param notices told of each file skipped
     */
    private static <T> Folder<T> read(Path folder, String extension, String kind, Parser<T> parser,
            List<String> builtIns, Consumer<Notice> notices) {
        List<T> read = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return new Folder<>(read, skipped);
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(folder)) {
            files = listed.filter(file -> isPlayerFile(file, extension))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString(), FILE_NAME_ORDER))
                    .toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "The folder " + folder + " couldn't be listed", e);
            notices.accept(new Notice("The " + folder.getFileName() + " folder couldn't be read",
                    "None of its files were loaded: " + e.getMessage()));
            return new Folder<>(read, skipped);
        }
        Map<String, String> builtInsByKey = builtIns.stream()
                .collect(Collectors.toMap(LookCatalogue::key, Function.identity()));
        // The file names already loaded, by their identity's key, so a later case variant names what it clashes with.
        Map<String, String> loaded = new HashMap<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String identity = fileName.substring(0, fileName.length() - extension.length());
            String reason;
            if (builtInsByKey.containsKey(key(identity))) {
                reason = "Its name, " + identity + ", is already a built-in " + kind + "'s.";
            } else if (loaded.containsKey(key(identity))) {
                reason = "Its name, " + identity + ", is already taken by " + loaded.get(key(identity)) + ".";
            } else {
                reason = null;
                try {
                    read.add(parser.parse(identity, readText(file)));
                    loaded.put(key(identity), fileName);
                } catch (ReadFailed e) {
                    reason = e.getMessage();
                } catch (IOException e) {
                    // The parser's format exception: its message says what is wrong with the format.
                    reason = sentence(e.getMessage());
                } catch (IllegalArgumentException e) {
                    // A value the parser let through but the record refused (a blank identity, say).
                    reason = "It can't be read as a " + kind + ": " + sentence(e.getMessage());
                }
            }
            if (reason != null) {
                Skipped skip = new Skipped(fileName, reason);
                skipped.add(skip);
                notices.accept(new Notice(fileName + " was skipped", reason));
            }
        }
        return new Folder<>(read, skipped);
    }

    /** A regular file directly in the folder with the extension (in any case) and a name before it. */
    private static boolean isPlayerFile(Path file, String extension) {
        String name = file.getFileName().toString();
        return name.length() > extension.length()
                && name.regionMatches(true, name.length() - extension.length(), extension, 0, extension.length())
                && Files.isRegularFile(file);
    }

    /** Reads a file as strict UTF-8, turning a failure into the sentence the Look tab lists. */
    private static String readText(Path file) throws ReadFailed {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            throw new ReadFailed("It isn't UTF-8 text.");
        } catch (IOException e) {
            throw new ReadFailed("It couldn't be read: " + sentence(String.valueOf(e.getMessage())));
        }
    }

    /** An identity as clashes compare it: case ignored, as Windows file names are. */
    private static String key(String identity) {
        return identity.toLowerCase(Locale.ROOT);
    }

    /** Ends a message as a sentence. */
    private static String sentence(String message) {
        String text = message == null || message.isBlank() ? "it can't be read" : message.strip();
        return text.endsWith(".") ? text : text + ".";
    }

    /** A Player File that couldn't be read at all, with the reason the Look tab lists. */
    private static final class ReadFailed extends IOException {

        private static final long serialVersionUID = 1L;

        ReadFailed(String reason) {
            super(reason);
        }
    }
}
