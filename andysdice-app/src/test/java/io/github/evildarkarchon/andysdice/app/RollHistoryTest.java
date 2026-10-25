package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.expression.FaceValueChain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Roll History as {@code history.txt} keeps it (spec §3.6, ADR-0007): one tab-separated line of facts per Roll,
 * newest last, under the version header; the total recomputed by the evaluator from the stored Face Value Chains;
 * at most {@value RollHistory#CAP} Rolls. Pure Java, through the real {@link Persistence}.
 */
class RollHistoryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T14:30:12Z"), ZoneOffset.UTC);
    private static final Instant AT = Instant.parse("2026-10-21T13:05:09.250Z");

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

    @Test
    void eachRollIsOneTabSeparatedLineOfFactsNewestLast() throws IOException {
        RollHistory history = RollHistory.EMPTY
                .with(new PastRoll(AT, "4d6rr1kh3", Optional.empty(), List.of(FaceValueChain.of(1, 5),
                        FaceValueChain.of(3), FaceValueChain.of(6), FaceValueChain.of(2))))
                .with(new PastRoll(AT.plusSeconds(60), "1d20 + 5", Optional.of("Fireball"),
                        List.of(FaceValueChain.of(17))));

        persistence.save(RollHistory.FILE, history);
        persistence.flush();

        assertThat(Files.readString(folder.resolve("history.txt"), StandardCharsets.UTF_8)).isEqualTo(
                "#andysdice history.txt 1\n"
                        + "2026-10-21T13:05:09.250Z\t4d6rr1kh3\t\t1>5,3,6,2\n"
                        + "2026-10-21T13:06:09.250Z\t1d20 + 5\tFireball\t17\n");
    }

    @Test
    void whatIsSavedLoadsBackInTheNextRun() {
        RollHistory history = RollHistory.EMPTY
                .with(new PastRoll(AT, "d%-2+1d20 adv", Optional.empty(),
                        List.of(FaceValueChain.of(100), FaceValueChain.of(4), FaceValueChain.of(19))))
                .with(new PastRoll(AT.plusNanos(1), "2d6ro<3", Optional.of("Sneak attack"),
                        List.of(FaceValueChain.of(2, 6), FaceValueChain.of(5))));
        persistence.save(RollHistory.FILE, history);
        persistence.close();

        persistence = new Persistence(folder, notices::add, CLOCK);
        assertThat(persistence.load(RollHistory.FILE)).isEqualTo(history);
        assertThat(notices).isEmpty();
    }

    @Test
    void theTotalIsRecomputedFromTheStoredChains() throws IOException {
        // 4d6rr1kh3: the 1 was rerolled to 5, then 5, 3, 6 kept and 2 not: 14, plus 2.
        write("#andysdice history.txt 1\n2026-10-21T13:05:09Z\t4d6rr1kh3+2\t\t1>5,3,6,2\n");

        PastRoll roll = persistence.load(RollHistory.FILE).rolls().getFirst();

        assertThat(roll.total()).isEqualTo(16);
        assertThat(roll.result().breakdown().terms()).hasSize(2);
    }

    @Test
    void aLineThatMakesNoRollSetsTheFileAsideAndKeepsIt() throws IOException {
        // 1d6 can't show a 7, so the evaluator refuses the chain; the file is kept aside, not overwritten.
        String bad = "#andysdice history.txt 1\n2026-10-21T13:05:09Z\t1d6\t\t7\n";
        write(bad);

        assertThat(persistence.load(RollHistory.FILE)).isEqualTo(RollHistory.EMPTY);
        assertThat(Files.readString(folder.resolve("history.txt.bad-20261025T143012Z"))).isEqualTo(bad);
        assertThat(notices).extracting(Notice::title).containsExactly("history.txt was set aside");
    }

    @Test
    void malformedLinesAreUnreadable() throws IOException {
        for (String line : List.of("2026-10-21T13:05:09Z\t1d6\t3", "yesterday\t1d6\t\t3",
                "2026-10-21T13:05:09Z\t1d6\t\t+3", "2026-10-21T13:05:09Z\t1d6\t\t3,4",
                "2026-10-21T13:05:09Z\t1d7\t\t3", "2026-10-21T13:05:09Z\t1d6\t\t")) {
            assertThat(readable(line)).as(line).isFalse();
        }
        assertThat(readable("2026-10-21T13:05:09Z\t1d6\t\t3")).isTrue();
    }

    @Test
    void atMostOneHundredRollsAreKeptTheOldestDroppedFirst() {
        RollHistory history = RollHistory.EMPTY;
        for (int i = 0; i < 105; i++) {
            history = history.with(roll(i));
        }

        assertThat(history.rolls()).hasSize(RollHistory.CAP);
        assertThat(history.rolls().getFirst()).isEqualTo(roll(5));
        assertThat(history.newestFirst().getFirst()).isEqualTo(roll(104));
    }

    @Test
    void aFileWithMoreThanOneHundredLinesKeepsTheNewest() throws IOException {
        StringBuilder text = new StringBuilder("#andysdice history.txt 1\n");
        for (int i = 0; i < 101; i++) {
            text.append(AT.plusSeconds(i)).append("\t1d6\t\t").append(i % 6 + 1).append('\n');
        }
        write(text.toString());

        RollHistory history = persistence.load(RollHistory.FILE);

        assertThat(history.rolls()).hasSize(100);
        assertThat(history.rolls().getFirst().at()).isEqualTo(AT.plusSeconds(1));
    }

    /** A 1d20 Roll, its Face Value and time told apart by {@code i}. */
    private static PastRoll roll(int i) {
        return new PastRoll(AT.plusSeconds(i), "1d20", Optional.empty(), List.of(FaceValueChain.of(i % 20 + 1)));
    }

    private void write(String text) throws IOException {
        Files.writeString(folder.resolve("history.txt"), text, StandardCharsets.UTF_8);
    }

    /** Reads one line through the real format, as a fresh run would, and says whether it made a Roll. */
    private static boolean readable(String line) {
        try {
            RollHistory.FILE.read(List.of(line));
            return true;
        } catch (PersistedFile.UnreadableException e) {
            return false;
        }
    }
}
