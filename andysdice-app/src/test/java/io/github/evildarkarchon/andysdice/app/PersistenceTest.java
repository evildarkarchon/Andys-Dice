package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The persistence machinery every file shares (spec §3.6, ADR-0007), over the test-only {@link NumbersFile}: the
 * version header, migration forward, writes through a temp file on the one writer thread, the set-aside rule for an
 * unreadable or newer file, and an unwritable folder. Pure Java: no toolkit; Notices are collected in a list.
 */
class PersistenceTest {

    /** 2026-10-25 14:30:12 UTC, so set-aside names are known. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T14:30:12Z"), ZoneOffset.UTC);
    private static final String BAD_NAME = NumbersFile.NAME + ".bad-20261025T143012Z";

    @TempDir
    Path folder;

    private final List<Notice> notices = new CopyOnWriteArrayList<>();
    private final NumbersFile numbers = new NumbersFile();
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
    void whatIsSavedLoadsBackInTheNextRun() {
        persistence.save(numbers, List.of(4, 8, 15));
        persistence.close();

        persistence = new Persistence(folder, notices::add, CLOCK);
        assertThat(persistence.load(numbers)).containsExactly(4, 8, 15);
        assertThat(notices).isEmpty();
    }

    @Test
    void theFileIsUtf8TextUnderAVersionHeaderLine() throws IOException {
        persistence.save(numbers, List.of(4, 8));
        persistence.flush();

        assertThat(Files.readString(folder.resolve(NumbersFile.NAME), StandardCharsets.UTF_8))
                .isEqualTo("#andysdice numbers.txt 3\n4\n8\n");
    }

    @Test
    void anAbsentFileStartsFreshWithoutANotice() {
        assertThat(persistence.load(numbers)).isEmpty();
        assertThat(notices).isEmpty();
        assertThat(folder.resolve(NumbersFile.NAME)).doesNotExist();
    }

    @Test
    void anOlderVersionIsMigratedForwardStepByStep() throws IOException {
        write("#andysdice numbers.txt 1\n4, 8,15\n");

        assertThat(persistence.load(numbers)).containsExactly(4, 8, 15);
        assertThat(notices).isEmpty();
    }

    @Test
    void theMiddleVersionMigratesToo() throws IOException {
        write("#andysdice numbers.txt 2\nn 16\nn 23\n");

        assertThat(persistence.load(numbers)).containsExactly(16, 23);
    }

    @Test
    void windowsLineEndingsAndAByteOrderMarkAreRead() throws IOException {
        write("﻿#andysdice numbers.txt 3\r\n42\r\n");

        assertThat(persistence.load(numbers)).containsExactly(42);
        assertThat(notices).isEmpty();
    }

    @Test
    void aNewerVersionIsSetAsideStartedFreshAndReported() throws IOException {
        String newer = "#andysdice numbers.txt 4\nsomething new\n";
        write(newer);

        assertThat(persistence.load(numbers)).isEmpty();

        assertThat(folder.resolve(NumbersFile.NAME)).doesNotExist();
        assertThat(folder.resolve(BAD_NAME)).hasContent(newer);
        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice.title()).contains(NumbersFile.NAME).contains("set aside");
            assertThat(notice.detail()).contains("newer version").contains(BAD_NAME);
        });
    }

    @Test
    void aBodyTheFormatCantReadIsSetAside() throws IOException {
        write("#andysdice numbers.txt 3\n4\nfour\n");

        assertThat(persistence.load(numbers)).isEmpty();

        assertThat(folder.resolve(BAD_NAME)).hasContent("#andysdice numbers.txt 3\n4\nfour\n");
        assertThat(notices).singleElement().satisfies(notice ->
                assertThat(notice.detail()).contains("couldn't be read").contains(BAD_NAME));
    }

    @Test
    void aMissingOrForeignHeaderIsUnreadable() throws IOException {
        for (String text : List.of("", "4\n8\n", "#andysdice history.txt 3\n4\n", "#andysdice numbers.txt x\n",
                "#andysdice numbers.txt 0\n", "#andysdice numbers.txt -1\n")) {
            write(text);
            assertThat(persistence.load(numbers)).as(text).isEmpty();
            assertThat(folder.resolve(NumbersFile.NAME)).as(text).doesNotExist();
        }
        assertThat(notices).hasSize(6);
    }

    @Test
    void invalidUtf8IsUnreadable() throws IOException {
        byte[] bytes = {'#', 'a', 'n', 'd', 'y', 's', 'd', 'i', 'c', 'e', ' ', 'n', 'u', 'm', 'b', 'e', 'r', 's', '.',
            't', 'x', 't', ' ', '3', '\n', (byte) 0xC3, (byte) 0x28, '\n'};
        Files.write(folder.resolve(NumbersFile.NAME), bytes);

        assertThat(persistence.load(numbers)).isEmpty();
        assertThat(folder.resolve(BAD_NAME)).hasBinaryContent(bytes);
        assertThat(notices).hasSize(1);
    }

    @Test
    void aSetAsideFileIsNeverOverwrittenNotEvenByAnotherSetAsideInTheSameSecond() throws IOException {
        write("#andysdice numbers.txt 9\nfirst\n");
        persistence.load(numbers);
        write("#andysdice numbers.txt 9\nsecond\n");
        persistence.load(numbers);

        assertThat(folder.resolve(BAD_NAME)).hasContent("#andysdice numbers.txt 9\nfirst\n");
        assertThat(folder.resolve(BAD_NAME + "-2")).hasContent("#andysdice numbers.txt 9\nsecond\n");
    }

    @Test
    void afterASetAsideTheFreshFileIsWrittenBesideTheOldOne() throws IOException {
        write("#andysdice numbers.txt 9\nnewer\n");
        persistence.load(numbers);

        persistence.save(numbers, List.of(7));
        persistence.flush();

        assertThat(folder.resolve(NumbersFile.NAME)).hasContent("#andysdice numbers.txt 3\n7\n");
        assertThat(folder.resolve(BAD_NAME)).hasContent("#andysdice numbers.txt 9\nnewer\n");
    }

    /** One file set aside costs only that file: another loads as normal. */
    @Test
    void theOtherFilesLoadNormally() throws IOException {
        write("#andysdice numbers.txt 9\n");
        Files.writeString(folder.resolve(OtherNumbers.NAME), "#andysdice other.txt 3\n5\n");

        assertThat(persistence.load(numbers)).isEmpty();
        assertThat(persistence.load(new OtherNumbers())).containsExactly(5);
        assertThat(notices).hasSize(1);
    }

    @Test
    void loadsAndWritesRunOnTheOneWriterThreadAndSaveDoesNotWait() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reading = new CountDownLatch(1);
        numbers.duringRead = () -> {
            reading.countDown();
            await(release);
        };
        write("#andysdice numbers.txt 3\n1\n");
        Thread loader = Thread.ofPlatform().start(() -> persistence.load(numbers));
        assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();

        // The writer thread is busy reading: the save returns at once and its write waits its turn.
        persistence.save(numbers, List.of(2));
        assertThat(folder.resolve(NumbersFile.NAME)).hasContent("#andysdice numbers.txt 3\n1\n");

        release.countDown();
        loader.join(10_000);
        persistence.flush();
        assertThat(folder.resolve(NumbersFile.NAME)).hasContent("#andysdice numbers.txt 3\n2\n");
        assertThat(numbers.readThreads).containsExactly(Persistence.WRITER_THREAD_NAME);
    }

    @Test
    void theLatestSaveWinsAndNoTempFileIsLeftBehind() throws IOException {
        for (int i = 0; i < 50; i++) {
            persistence.save(numbers, List.of(i));
        }
        persistence.flush();

        assertThat(folder.resolve(NumbersFile.NAME)).hasContent("#andysdice numbers.txt 3\n49\n");
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly(NumbersFile.NAME);
        }
    }

    @Test
    void aFolderThatBecomesUnwritableMidRunPostsOneNoticeAndTheAppCarriesOn() throws IOException {
        persistence.save(numbers, List.of(1));
        persistence.flush();
        assertThat(notices).isEmpty();
        // The data folder goes away and a plain file takes its place: nothing can be written there now.
        Files.delete(folder.resolve(NumbersFile.NAME));
        Files.delete(folder);
        Files.writeString(folder, "not a folder");

        persistence.save(numbers, List.of(2));
        persistence.save(new OtherNumbers(), List.of(3));
        persistence.flush();
        persistence.save(numbers, List.of(4));
        persistence.flush();

        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice.title()).contains("can't be saved");
            assertThat(notice.detail()).contains(folder.toString());
        });
        // Back as a folder, it is written to again (still without a second Notice).
        Files.delete(folder);
        Files.createDirectory(folder);
        persistence.save(numbers, List.of(5));
        persistence.flush();
        assertThat(folder.resolve(NumbersFile.NAME)).hasContent("#andysdice numbers.txt 3\n5\n");
        assertThat(notices).hasSize(1);
    }

    @Test
    void aFolderThatIsUnwritableFromTheStartPostsTheSameOneNotice() throws IOException {
        persistence.close();
        Path notAFolder = Files.writeString(folder.resolve("file"), "x");

        persistence = new Persistence(notAFolder, notices::add, CLOCK);
        persistence.flush();
        persistence.save(numbers, List.of(1));
        persistence.flush();

        assertThat(notices).singleElement().satisfies(notice -> assertThat(notice.title()).contains("can't be saved"));
    }

    @Test
    void aFileThatCouldntBeSetAsideIsNeverWritten() throws IOException {
        persistence.close();
        persistence = new Persistence(folder, notices::add, CLOCK, (from, to) -> {
            throw new IOException("denied");
        });
        write("#andysdice numbers.txt 9\nkeep me\n");

        assertThat(persistence.load(numbers)).isEmpty();
        persistence.save(numbers, List.of(1));
        persistence.flush();

        assertThat(folder.resolve(NumbersFile.NAME)).hasContent("#andysdice numbers.txt 9\nkeep me\n");
        assertThat(notices).singleElement().satisfies(notice -> assertThat(notice.detail()).contains("left as it is"));
    }

    @Test
    void saveAfterCloseIsIgnored() {
        persistence.close();

        persistence.save(numbers, List.of(1));

        assertThat(folder.resolve(NumbersFile.NAME)).doesNotExist();
    }

    private void write(String text) throws IOException {
        Files.writeString(folder.resolve(NumbersFile.NAME), text, StandardCharsets.UTF_8);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A second file of the same shape under another name. */
    private static final class OtherNumbers implements PersistedFile<List<Integer>> {
        static final String NAME = "other.txt";
        private final NumbersFile shape = new NumbersFile();

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public int version() {
            return 3;
        }

        @Override
        public List<Integer> fresh() {
            return List.of();
        }

        @Override
        public List<Integer> read(List<String> body) throws UnreadableException {
            return shape.read(body);
        }

        @Override
        public List<String> write(List<Integer> value) {
            return shape.write(value);
        }
    }
}
