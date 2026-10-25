package io.github.evildarkarchon.andysdice.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * A {@code java.util.logging} handler writing to one log file with size-based rotation (spec §6), in UTF-8.
 *
 * <p>The live file always keeps its own name ({@code andysdice.log}), which {@code FileHandler} can't do: with a
 * count above one it numbers the live file too, and it leaves {@code .lck} files beside it. When a record would
 * take the live file past the limit, the file is renamed to {@code <name>.1}, older ones shift up to
 * {@code <name>.<keep>}, and the oldest beyond that is deleted. A new handler appends to the existing file, so
 * the last run's log survives a restart.
 *
 * <p>Thread contract: {@link #publish} may be called from any thread; it is synchronized.
 */
final class RotatingLogHandler extends Handler implements AutoCloseable {

    private final Path file;
    private final long limitBytes;
    private final int keep;
    private OutputStream out;
    private long size;

    /**
     * Opens (or creates) the live log file for appending.
     *
     * @param file the live log file; its folder must exist
     * @param limitBytes the size the live file may grow to before a record rotates it
     * @param keep how many rotated files to keep, at least 1
     * @throws IOException if the file can't be opened
     */
    RotatingLogHandler(Path file, long limitBytes, int keep) throws IOException {
        if (limitBytes < 1 || keep < 1) {
            throw new IllegalArgumentException("limit " + limitBytes + " and keep " + keep + " must be positive");
        }
        this.file = Objects.requireNonNull(file, "file");
        this.limitBytes = limitBytes;
        this.keep = keep;
        setFormatter(new SimpleFormatter());
        open();
    }

    /**
     * The live log file.
     *
     * @return its path
     */
    Path file() {
        return file;
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (out == null || !isLoggable(record)) {
            return;
        }
        byte[] bytes;
        try {
            bytes = getFormatter().format(record).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            reportError("formatting a log record failed", e, ErrorManager.FORMAT_FAILURE);
            return;
        }
        try {
            // An empty file always takes the record, so one bigger than the limit is still written, alone.
            if (size > 0 && size + bytes.length > limitBytes) {
                rotate();
            }
            out.write(bytes);
            out.flush();
            size += bytes.length;
        } catch (IOException e) {
            reportError("writing " + file + " failed", e, ErrorManager.WRITE_FAILURE);
        }
    }

    @Override
    public synchronized void flush() {
        if (out == null) {
            return;
        }
        try {
            out.flush();
        } catch (IOException e) {
            reportError("flushing " + file + " failed", e, ErrorManager.FLUSH_FAILURE);
        }
    }

    @Override
    public synchronized void close() {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException e) {
            reportError("closing " + file + " failed", e, ErrorManager.CLOSE_FAILURE);
        }
        out = null;
    }

    private void open() throws IOException {
        out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        size = Files.size(file);
    }

    /** Closes the live file, shifts the rotated ones up by one (dropping the oldest), and opens a fresh one. */
    private void rotate() throws IOException {
        out.close();
        out = null;
        Files.deleteIfExists(rotated(keep));
        for (int i = keep - 1; i >= 1; i--) {
            if (Files.exists(rotated(i))) {
                Files.move(rotated(i), rotated(i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, rotated(1), StandardCopyOption.REPLACE_EXISTING);
        open();
    }

    private Path rotated(int generation) {
        return file.resolveSibling(file.getFileName() + "." + generation);
    }
}
