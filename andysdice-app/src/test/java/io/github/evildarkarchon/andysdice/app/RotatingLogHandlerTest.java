package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The log file's size-based rotation (spec §6): the live file is always {@code andysdice.log}; once it would
 * pass the size limit it becomes {@code andysdice.log.1}, older files shift up, and the oldest past the kept
 * count is deleted.
 */
class RotatingLogHandlerTest {

    /** Each record is its message plus a newline, so sizes are easy to count. */
    private static final Formatter MESSAGE_ONLY = new Formatter() {
        @Override
        public String format(LogRecord record) {
            return record.getMessage() + "\n";
        }
    };

    @TempDir
    Path logs;

    @Test
    void recordsAreAppendedToTheLiveFile() throws IOException {
        try (RotatingLogHandler handler = handler(1_000, 2)) {
            handler.publish(record("first"));
            handler.publish(record("second"));
        }

        assertThat(logs.resolve("andysdice.log")).content(StandardCharsets.UTF_8).isEqualTo("first\nsecond\n");
    }

    /** A new run appends to the last run's log rather than wiping it. */
    @Test
    void aNewHandlerAppendsToTheExistingFile() throws IOException {
        Files.writeString(logs.resolve("andysdice.log"), "earlier run\n");

        try (RotatingLogHandler handler = handler(1_000, 2)) {
            handler.publish(record("this run"));
        }

        assertThat(logs.resolve("andysdice.log")).content(StandardCharsets.UTF_8)
                .isEqualTo("earlier run\nthis run\n");
    }

    @Test
    void aRecordThatWouldPassTheLimitStartsAFreshFile() throws IOException {
        try (RotatingLogHandler handler = handler(12, 2)) {
            handler.publish(record("aaaaa")); // 6 bytes
            handler.publish(record("bbbbb")); // 12: exactly at the limit, still fits
            handler.publish(record("ccccc")); // would make 18: rotates first
        }

        assertThat(logs.resolve("andysdice.log")).content(StandardCharsets.UTF_8).isEqualTo("ccccc\n");
        assertThat(logs.resolve("andysdice.log.1")).content(StandardCharsets.UTF_8).isEqualTo("aaaaa\nbbbbb\n");
    }

    @Test
    void onlyTheKeptCountOfOldFilesSurvives() throws IOException {
        try (RotatingLogHandler handler = handler(6, 2)) {
            for (String message : new String[] {"one..", "two..", "three", "four.", "five."}) {
                handler.publish(record(message));
            }
        }

        assertThat(logs.resolve("andysdice.log")).content(StandardCharsets.UTF_8).isEqualTo("five.\n");
        assertThat(logs.resolve("andysdice.log.1")).content(StandardCharsets.UTF_8).isEqualTo("four.\n");
        assertThat(logs.resolve("andysdice.log.2")).content(StandardCharsets.UTF_8).isEqualTo("three\n");
        assertThat(logs.resolve("andysdice.log.3")).doesNotExist();
    }

    /** A record bigger than the limit on its own still gets written, alone in its file. */
    @Test
    void anOversizedRecordIsWrittenWhole() throws IOException {
        try (RotatingLogHandler handler = handler(4, 1)) {
            handler.publish(record("a long line"));
        }

        assertThat(logs.resolve("andysdice.log")).content(StandardCharsets.UTF_8).isEqualTo("a long line\n");
    }

    @Test
    void recordsBelowTheHandlersLevelAreDropped() throws IOException {
        try (RotatingLogHandler handler = handler(1_000, 1)) {
            handler.setLevel(Level.INFO);
            handler.publish(new LogRecord(Level.FINE, "chatter"));
            handler.publish(record("kept"));
        }

        assertThat(logs.resolve("andysdice.log")).content(StandardCharsets.UTF_8).isEqualTo("kept\n");
    }

    private RotatingLogHandler handler(long limitBytes, int keep) throws IOException {
        RotatingLogHandler handler = new RotatingLogHandler(logs.resolve("andysdice.log"), limitBytes, keep);
        handler.setFormatter(MESSAGE_ONLY);
        return handler;
    }

    private static LogRecord record(String message) {
        return new LogRecord(Level.INFO, message);
    }
}
