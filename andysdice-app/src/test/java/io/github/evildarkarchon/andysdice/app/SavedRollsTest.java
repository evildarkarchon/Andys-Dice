package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.evildarkarchon.andysdice.core.expression.Span;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Saved Rolls as a value and as {@code saved-rolls.txt} keeps them (spec §3.6 "Saved Rolls", ADR-0007): the name
 * rules (trimmed, 1-40 characters, unique ignoring case, no control characters), the expression that must parse when
 * saved, the cap of 100, the Player's own order, and one {@code name⇥expression} line per Saved Roll under the version
 * header. Pure Java, through the real {@link Persistence}.
 */
class SavedRollsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T14:30:12Z"), ZoneOffset.UTC);

    @TempDir
    Path folder;

    private final List<Notice> notices = new CopyOnWriteArrayList<>();
    private Persistence persistence;

    @BeforeEach
    void start() {
        persistence = new Persistence(folder, notices::add, CLOCK);
    }

    @AfterEach
    void stop() {
        persistence.close();
    }

    private static final SavedRolls TWO = SavedRolls.EMPTY
            .with(new SavedRoll("Fireball", "8d6"))
            .with(new SavedRoll("Longsword attack", "1d20+7"));

    // --- the name rules ---

    @Test
    void aNameIsTrimmed() {
        assertThat(SavedRolls.EMPTY.refusal("  Sneak attack \t", "1d6", SavedRolls.NEW)).isEmpty();
        assertThat(SavedRoll.of("  Sneak attack \t", " 3d6 ")).isEqualTo(new SavedRoll("Sneak attack", "3d6"));
    }

    @Test
    void aNameNeedsAtLeastOneCharacterOnceTrimmed() {
        assertThat(nameRefusal(SavedRolls.EMPTY, "", SavedRolls.NEW)).contains("Give the Saved Roll a name.");
        assertThat(nameRefusal(SavedRolls.EMPTY, "   ", SavedRolls.NEW)).contains("Give the Saved Roll a name.");
    }

    @Test
    void aNameHasAtMostFortyCharacters() {
        assertThat(SavedRolls.EMPTY.refusal("x".repeat(40), "1d6", SavedRolls.NEW)).isEmpty();
        assertThat(nameRefusal(SavedRolls.EMPTY, "x".repeat(41), SavedRolls.NEW))
                .contains("A name can be at most 40 characters; this one has 41.");
        // The spaces trimmed off don't count.
        assertThat(SavedRolls.EMPTY.refusal("  " + "x".repeat(40) + "  ", "1d6", SavedRolls.NEW)).isEmpty();
        // Characters as a person counts them: a letter outside the BMP is one, not two UTF-16 units.
        assertThat(SavedRolls.EMPTY.refusal("𝒜".repeat(40), "1d6", SavedRolls.NEW)).isEmpty();
    }

    @Test
    void aNameHasNoControlCharacters() {
        for (String name : List.of("Fire\tball", "Fire\u0000ball", "Fire\u007Fball", "Fire\u0085ball", "Fire\nball")) {
            assertThat(nameRefusal(SavedRolls.EMPTY, name, SavedRolls.NEW)).as(name)
                    .contains("A name can't hold a control character, such as a tab.");
        }
    }

    @Test
    void aNameIsUniqueIgnoringCase() {
        assertThat(nameRefusal(TWO, "fireBALL", SavedRolls.NEW))
                .contains("There's already a Saved Roll called \"Fireball\".");
        assertThat(nameRefusal(TWO, " Fireball ", SavedRolls.NEW))
                .contains("There's already a Saved Roll called \"Fireball\".");
        assertThat(TWO.refusal("Fireball 2", "8d6", SavedRolls.NEW)).isEmpty();
    }

    @Test
    void anEditedSavedRollMayKeepItsOwnName() {
        assertThat(TWO.refusal("FIREBALL", "10d6", 0)).isEmpty();
        assertThat(nameRefusal(TWO, "fireball", 1))
                .contains("There's already a Saved Roll called \"Fireball\".");
    }

    @Test
    void theExpressionMustParseWhenSavedAndItsErrorKeepsItsSpan() {
        Optional<SavedRolls.Refusal> refusal = TWO.refusal("Bad", "2d6+1d7", SavedRolls.NEW);

        assertThat(refusal).containsInstanceOf(SavedRolls.Refusal.BadExpression.class);
        SavedRolls.Refusal.BadExpression bad = (SavedRolls.Refusal.BadExpression) refusal.orElseThrow();
        assertThat(bad.error().span()).isEqualTo(new Span(5, 7));
        assertThat(bad.message()).isEqualTo(bad.error().message()).isNotBlank();
    }

    @Test
    void aBadNameIsReportedBeforeABadExpression() {
        assertThat(TWO.refusal("", "2d6+", SavedRolls.NEW))
                .containsInstanceOf(SavedRolls.Refusal.BadName.class);
    }

    @Test
    void theCapIsOneHundredAndOnlyStopsNewOnes() {
        SavedRolls full = hundred();

        assertThat(full.full()).isTrue();
        assertThat(full.refusal("One more", "1d6", SavedRolls.NEW)).hasValueSatisfying(refusal -> {
            assertThat(refusal).isInstanceOf(SavedRolls.Refusal.Full.class);
            assertThat(refusal.message())
                    .isEqualTo("There are already 100 Saved Rolls, the most kept. Delete one to save another.");
        });
        assertThat(full.refusal("Renamed", "1d6", 50)).isEmpty();
        assertThatThrownBy(() -> full.with(new SavedRoll("One more", "1d6")))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- order ---

    @Test
    void newOnesGoAtTheEndAndTheOrderIsThePlayers() {
        SavedRolls three = TWO.with(new SavedRoll("Stats", "4d6rr1kh3"));

        assertThat(names(three)).containsExactly("Fireball", "Longsword attack", "Stats");
        assertThat(names(three.moved(2, -1))).containsExactly("Fireball", "Stats", "Longsword attack");
        assertThat(names(three.moved(0, 1))).containsExactly("Longsword attack", "Fireball", "Stats");
        // Past either end nothing moves.
        assertThat(three.moved(0, -1)).isEqualTo(three);
        assertThat(three.moved(2, 1)).isEqualTo(three);
        assertThat(names(three.without(1))).containsExactly("Fireball", "Stats");
        assertThat(three.replaced(1, new SavedRoll("Longsword damage", "1d8+4")).rolls().get(1))
                .isEqualTo(new SavedRoll("Longsword damage", "1d8+4"));
    }

    // --- an expression that no longer parses ---

    @Test
    void aSavedRollThatNoLongerParsesSaysWhyAndWontRoll() {
        SavedRoll old = new SavedRoll("Old homebrew", "3d7+2");

        assertThat(old.rolls()).isFalse();
        assertThat(old.problem()).hasValueSatisfying(error -> assertThat(error.span()).isEqualTo(new Span(1, 3)));
        assertThat(new SavedRoll("Fireball", "8d6").rolls()).isTrue();
        assertThat(new SavedRoll("Fireball", "8d6").problem()).isEmpty();
    }

    // --- saved-rolls.txt ---

    @Test
    void eachSavedRollIsOneNameTabExpressionLineInThePlayersOrder() throws IOException {
        persistence.save(SavedRolls.FILE, TWO.moved(1, -1));
        persistence.flush();

        assertThat(Files.readString(folder.resolve("saved-rolls.txt"), StandardCharsets.UTF_8)).isEqualTo(
                "#andysdice saved-rolls.txt 1\n"
                        + "Longsword attack\t1d20+7\n"
                        + "Fireball\t8d6\n");
    }

    @Test
    void theFileRoundTrips() {
        SavedRolls saved = TWO.with(new SavedRoll("Café crème 𝒜", "d% + 999"))
                .with(new SavedRoll("Old homebrew", "3d7+2"));

        persistence.save(SavedRolls.FILE, saved);
        persistence.flush();

        assertThat(persistence.load(SavedRolls.FILE)).isEqualTo(saved);
        assertThat(notices).isEmpty();
    }

    @Test
    void noFileMeansNoSavedRolls() {
        assertThat(persistence.load(SavedRolls.FILE)).isEqualTo(SavedRolls.EMPTY);
        assertThat(SavedRolls.EMPTY.rolls()).isEmpty();
    }

    @Test
    void aSavedRollThatNoLongerParsesIsKept() throws IOException {
        write("#andysdice saved-rolls.txt 1\nOld homebrew\t3d7+2\nFireball\t8d6\n");

        SavedRolls loaded = persistence.load(SavedRolls.FILE);

        assertThat(loaded.rolls()).containsExactly(new SavedRoll("Old homebrew", "3d7+2"),
                new SavedRoll("Fireball", "8d6"));
        assertThat(notices).isEmpty();
    }

    @Test
    void aFileItCantMakeSenseOfIsSetAsideWhole() throws IOException {
        for (String body : List.of("No tab here\n", "\t1d6\n", "Fireball\t8d6\nfireball\t1d6\n",
                "x".repeat(41) + "\t1d6\n")) {
            notices.clear();
            write("#andysdice saved-rolls.txt 1\n" + body);

            assertThat(persistence.load(SavedRolls.FILE)).as(body).isEqualTo(SavedRolls.EMPTY);
            assertThat(notices).as(body).extracting(Notice::title).containsExactly("saved-rolls.txt was set aside");
            try (var files = Files.list(folder)) {
                files.filter(path -> path.getFileName().toString().startsWith("saved-rolls.txt.bad-"))
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void moreThanOneHundredIsSetAsideRatherThanLosingAny() throws IOException {
        StringBuilder body = new StringBuilder("#andysdice saved-rolls.txt 1\n");
        for (int i = 0; i < 101; i++) {
            body.append("Roll ").append(i).append("\t1d6\n");
        }
        write(body.toString());

        assertThat(persistence.load(SavedRolls.FILE)).isEqualTo(SavedRolls.EMPTY);
        assertThat(notices).extracting(Notice::title).containsExactly("saved-rolls.txt was set aside");
    }

    // --- helpers ---

    private static Optional<String> nameRefusal(SavedRolls saved, String name, int except) {
        return saved.refusal(name, "1d6", except)
                .filter(SavedRolls.Refusal.BadName.class::isInstance)
                .map(SavedRolls.Refusal::message);
    }

    private static SavedRolls hundred() {
        List<SavedRoll> rolls = new ArrayList<>();
        for (int i = 0; i < SavedRolls.CAP; i++) {
            rolls.add(new SavedRoll("Roll " + i, "1d6"));
        }
        return new SavedRolls(rolls);
    }

    private static List<String> names(SavedRolls saved) {
        return saved.rolls().stream().map(SavedRoll::name).toList();
    }

    private void write(String content) throws IOException {
        Files.writeString(folder.resolve(SavedRolls.FILE_NAME), content, StandardCharsets.UTF_8);
    }
}
