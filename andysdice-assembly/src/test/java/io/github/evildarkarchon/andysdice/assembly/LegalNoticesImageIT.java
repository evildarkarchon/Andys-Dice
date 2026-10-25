package io.github.evildarkarchon.andysdice.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * The image's {@code app\legal\} folder (ADR-0004, spec §6): Andy's Dice's own MIT licence and the sounds'
 * {@code SOURCES.md}, each the very file the repository holds; an index naming a notice for every bundled
 * third-party module and native; and all of it in the zip.
 */
class LegalNoticesImageIT {

    /** The index of app\legal\: one table row per bundled third-party component. */
    private static final String INDEX = "THIRD-PARTY-NOTICES.md";

    private final Path legal = BuiltImage.dir().resolve("app").resolve("legal");
    private final Path repository = Path.of(BuiltImage.property("andysdice.image.repoRoot"));

    @Test
    void theAppsOwnLicenceIsTheRepositorysMitLicence() throws IOException {
        Path licence = legal.resolve("LICENSE");

        assertThat(licence).isRegularFile();
        assertThat(Files.readString(licence)).startsWith("MIT License");
        assertThat(licence).hasSameBinaryContentAs(repository.resolve("LICENSE"));
    }

    /** The sample bank's credits ship beside the other notices, as the sound module holds them (#59). */
    @Test
    void theSoundsSourcesShipBesideTheNotices() {
        Path shipped = legal.resolve("SOURCES.md");

        assertThat(shipped).isRegularFile();
        assertThat(shipped).hasSameBinaryContentAs(repository.resolve("andysdice-sound").resolve("src")
                .resolve("main").resolve("resources").resolve("io").resolve("github").resolve("evildarkarchon")
                .resolve("andysdice").resolve("sound").resolve("sample-bank").resolve("SOURCES.md"));
    }

    /**
     * ADR-0004's rule that no bundled third-party artifact ships without a notice: every module jlink linked that
     * isn't ours or the JDK's, and every native in {@code app\natives}, is named by a row of the index, so a new
     * dependency fails the build until its notice is added.
     */
    @Test
    void everyBundledThirdPartyModuleAndNativeHasANoticeInTheIndex() throws IOException {
        List<NoticeRow> rows = NoticeRow.parse(Files.readString(legal.resolve(INDEX)));
        Set<String> named = new HashSet<>();
        rows.forEach(row -> named.addAll(row.bundledAs()));

        List<String> thirdPartyModules = linkedModules().stream()
                .filter(module -> !module.startsWith("io.github.evildarkarchon.andysdice."))
                .filter(module -> !module.startsWith("java.") && !module.startsWith("jdk."))
                .toList();
        // A guard on the filter itself: these are the modules ADR-0004's notice list is about.
        assertThat(thirdPartyModules).contains("org.lwjgl", "org.lwjgl.opengl", "org.joml",
                "com.github.stephengold.joltjni", "javafx.base", "javafx.graphics", "javafx.controls");
        assertThat(named).as("modules named in " + INDEX).containsAll(thirdPartyModules);

        List<String> natives;
        try (Stream<Path> files = Files.list(BuiltImage.dir().resolve("app").resolve("natives"))) {
            natives = files.map(file -> file.getFileName().toString()).toList();
        }
        assertThat(named).as("natives named in " + INDEX).containsAll(natives);
    }

    /** Every notice the index points at is in app\legal\, and none is empty. */
    @Test
    void everyNoticeTheIndexNamesIsInTheLegalFolder() throws IOException {
        List<NoticeRow> rows = NoticeRow.parse(Files.readString(legal.resolve(INDEX)));

        assertThat(rows).isNotEmpty();
        for (NoticeRow row : rows) {
            assertThat(row.notices()).as(row.component()).isNotEmpty();
            for (String notice : row.notices()) {
                assertThat(legal.resolve(notice)).as(row.component()).isRegularFile();
                assertThat(Files.size(legal.resolve(notice))).as(notice).isPositive();
            }
        }
    }

    /** The index's versions are the build's: the filtering that puts them there ran. */
    @Test
    void theIndexNamesTheBuildsVersions() throws IOException {
        String index = Files.readString(legal.resolve(INDEX));

        assertThat(index).doesNotContain("${");
        assertThat(index).contains(BuiltImage.property("andysdice.image.joltVersion"),
                BuiltImage.property("andysdice.version"));
    }

    /**
     * The JDK's and JavaFX's own notices are where jlink puts them (ADR-0004): one folder per linked module in
     * {@code runtime\legal\}, which app\legal\'s pointer notices refer to.
     */
    @Test
    void theRuntimeKeepsTheJdksAndJavaFxsNotices() throws IOException {
        Path runtimeLegal = BuiltImage.dir().resolve("runtime").resolve("legal");

        for (String module : linkedModules()) {
            if (module.startsWith("java.") || module.startsWith("javafx.")) {
                assertThat(runtimeLegal.resolve(module).resolve("LICENSE")).as(module).isRegularFile();
            }
        }
        assertThat(Files.readString(runtimeLegal.resolve("java.base").resolve("LICENSE")))
                .contains("GNU General Public License", "\"CLASSPATH\" EXCEPTION TO THE GPL");
    }

    @Test
    void theZipCarriesTheLegalFolder() throws IOException {
        try (ZipFile archive = new ZipFile(BuiltImage.property("andysdice.image.zip"))) {
            List<String> names = archive.stream().map(ZipEntry::getName).toList();

            assertThat(names).contains("AndysDice/app/legal/LICENSE", "AndysDice/app/legal/SOURCES.md",
                    "AndysDice/app/legal/" + INDEX);
        }
    }

    /** The modules jlink linked, from the runtime's {@code release} file's {@code MODULES} line. */
    private static List<String> linkedModules() throws IOException {
        for (String line : Files.readAllLines(BuiltImage.dir().resolve("runtime").resolve("release"))) {
            if (line.startsWith("MODULES=")) {
                return List.of(line.substring("MODULES=".length()).replace("\"", "").trim().split("\\s+"));
            }
        }
        throw new AssertionError("runtime\\release has no MODULES line");
    }

    /**
     * One row of the index's table: {@code | Component | Version | Licence | Bundled as | Notice |}, where the
     * last two columns hold back-quoted names (modules or native files, and notice files in app\legal\).
     *
     * @param component the component's name
     * @param bundledAs the modules and natives it ships as
     * @param notices the notice files that cover it
     */
    private record NoticeRow(String component, List<String> bundledAs, List<String> notices) {

        private static final Pattern QUOTED = Pattern.compile("`([^`]+)`");

        /** The rows of the first table in the index, below its header and separator lines. */
        static List<NoticeRow> parse(String index) {
            List<NoticeRow> rows = new ArrayList<>();
            List<String> tableLines = index.lines().filter(line -> line.startsWith("|")).toList();
            for (String line : tableLines.subList(Math.min(2, tableLines.size()), tableLines.size())) {
                String[] cells = line.split("\\|", -1);
                assertThat(cells).as("a five-column row: " + line).hasSize(7);
                rows.add(new NoticeRow(cells[1].trim(), quoted(cells[4]), quoted(cells[5])));
            }
            return rows;
        }

        private static List<String> quoted(String cell) {
            List<String> names = new ArrayList<>();
            Matcher matcher = QUOTED.matcher(cell);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            return names;
        }
    }
}
