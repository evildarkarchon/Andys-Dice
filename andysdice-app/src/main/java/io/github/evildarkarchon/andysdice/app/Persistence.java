package io.github.evildarkarchon.andysdice.app;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's persisted state on disk (spec §3.6, ADR-0007): versioned UTF-8 text files of facts in the data folder,
 * each described by a {@link PersistedFile}, all read and written on the one persistence writer thread.
 *
 * <p><b>Format every file shares.</b> The first line is the version header, {@code #andysdice <name> <version>}
 * (for example {@code #andysdice settings.properties 1}; the {@code #} also makes it a comment to
 * {@link java.util.Properties}), then the format's body lines, each ended by {@code \n}. Reading accepts
 * {@code \r\n} and a leading byte-order mark too, as a text editor may leave them. A file at an older version is
 * migrated forward in code, one version at a time ({@link PersistedFile#migrate}), and rewritten at the current
 * version the next time it is saved.
 *
 * <p><b>Writes.</b> {@link #save} snapshots the value on the caller's thread and returns at once; the writer thread
 * writes it to a temp file beside the target, forces it to disk, and renames it over the target with
 * {@code ATOMIC_MOVE}, so a crash or power cut leaves the old file or the new one, never a torn one. Saves of one
 * file that queue up while the writer is busy collapse into the latest.
 *
 * <p><b>Set-aside.</b> A file that can't be read (not UTF-8, no or a foreign header, a body its format rejects, an
 * I/O error) or that has a newer version is renamed {@code <name>.bad-<UTC timestamp>} (with {@code -2}, {@code -3}
 * ... if that is taken), never overwritten; that file starts {@linkplain PersistedFile#fresh fresh}, it is logged,
 * and a Notice says so. The other files are unaffected. If even the rename fails, the file is left as it is and
 * never written this run, so it still isn't overwritten.
 *
 * <p><b>An unwritable folder.</b> If the data folder can't be written, at start-up or mid-run, every failed write is
 * logged and one Notice is posted for the whole run; the app keeps its state in memory and carries on, and later
 * saves still try, so a folder that comes back is written again.
 *
 * <p>Thread contract: {@link #load}, {@link #save}, {@link #flush} and {@link #close} may be called from any thread
 * but the writer thread itself. Notices are handed to the consumer on the writer thread.
 */
final class Persistence implements AutoCloseable {

    /** The name of the one persistence writer thread (spec §5 "Threads"). */
    static final String WRITER_THREAD_NAME = "andysdice-persistence";

    /** How long {@link #close} waits for queued writes. */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    /** The version header: the file's own name, then a version of at least 1. */
    private static final Pattern HEADER = Pattern.compile("#andysdice (\\S+) ([1-9][0-9]{0,8})");

    /** The set-aside suffix's timestamp, UTC, with no character Windows forbids in a file name. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final System.Logger LOG = System.getLogger(Persistence.class.getName());

    /** Renames a file aside; the real one never replaces an existing file. */
    @FunctionalInterface
    interface Mover {
        /**
         * Renames {@code from} to {@code to}.
         *
         * @param from the file to set aside
         * @param to its new name, which doesn't exist
         * @throws IOException if it can't be renamed, or {@code to} exists after all
         */
        void move(Path from, Path to) throws IOException;
    }

    private final Path folder;
    private final Consumer<Notice> notices;
    private final Clock clock;
    private final Mover setAside;
    private final ExecutorService writer;
    /** The latest unwritten text of each file, by name; filled by any thread, emptied by the writer. */
    private final Map<String, String> pending = new ConcurrentHashMap<>();
    /** Files that couldn't be set aside, so must never be written this run. Writer thread only. */
    private final Set<String> blocked = new HashSet<>();
    /** Whether the unwritable folder's one Notice has been posted. Writer thread only. */
    private boolean unwritableReported;

    /**
     * Starts the persistence writer thread for a data folder, and checks the folder can be written.
     *
     * @param folder the data folder chosen for this run
     * @param notices where Notices go ({@code notices::post} in the app); called on the writer thread
     */
    Persistence(Path folder, Consumer<Notice> notices) {
        this(folder, notices, Clock.systemUTC());
    }

    /**
     * As {@link #Persistence(Path, Consumer)}, with the clock that dates set-aside files.
     *
     * @param folder the data folder
     * @param notices where Notices go
     * @param clock the clock for the {@code .bad-} timestamp
     */
    Persistence(Path folder, Consumer<Notice> notices, Clock clock) {
        this(folder, notices, clock, Files::move);
    }

    /**
     * As {@link #Persistence(Path, Consumer, Clock)}, with what renames a file aside (tests make it fail).
     *
     * @param folder the data folder
     * @param notices where Notices go
     * @param clock the clock for the {@code .bad-} timestamp
     * @param setAside renames a file aside without replacing anything
     */
    Persistence(Path folder, Consumer<Notice> notices, Clock clock, Mover setAside) {
        this.folder = Objects.requireNonNull(folder, "folder");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.setAside = Objects.requireNonNull(setAside, "setAside");
        // A daemon, so it never holds the JVM open; close() drains it, and an atomic rename makes a write cut short
        // by the process ending harmless.
        this.writer = Executors.newSingleThreadExecutor(
                task -> Thread.ofPlatform().name(WRITER_THREAD_NAME).daemon(true).unstarted(task));
        writer.execute(this::checkWritable);
    }

    /**
     * Returns the data folder.
     *
     * @return the folder every file lives in
     */
    Path folder() {
        return folder;
    }

    /**
     * Reads a file on the writer thread and waits for it: migrated forward if it is older, fresh if there is none,
     * and set aside (fresh, logged, one Notice) if it can't be read or is newer.
     *
     * @param file the file's format
     * @param <T> what it holds
     * @return what it holds
     * @throws IllegalStateException if this has been closed
     */
    <T> T load(PersistedFile<T> file) {
        Objects.requireNonNull(file, "file");
        try {
            return CompletableFuture.supplyAsync(() -> loadNow(file), writer).join();
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("persistence is closed", e);
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    /**
     * Saves a value: its body is made now, on the calling thread, and written on the writer thread soon after. A
     * failed write is logged and reported once ({@link Persistence}); it is never thrown here. After {@link #close}
     * the save is dropped and logged.
     *
     * @param file the file's format
     * @param value what it should hold
     * @param <T> what it holds
     * @throws IllegalArgumentException if the format wrote a line holding a line terminator
     */
    <T> void save(PersistedFile<T> file, T value) {
        Objects.requireNonNull(file, "file");
        String text = encode(file, value);
        String name = file.name();
        pending.put(name, text);
        try {
            writer.execute(() -> writePending(name));
        } catch (RejectedExecutionException e) {
            pending.remove(name, text);
            LOG.log(Level.WARNING, "Not saving " + name + ": persistence is already closed");
        }
    }

    /** Waits until every save made so far has been written (or has failed). Returns at once once closed. */
    void flush() {
        try {
            CompletableFuture.runAsync(() -> {}, writer).join();
        } catch (RejectedExecutionException e) {
            // Closed: close() has already waited for every queued write.
        }
    }

    /**
     * Writes whatever is still queued, then stops the writer thread. Waits up to ten seconds; a write still
     * running after that is logged and left to the atomic rename. Calling it again does nothing.
     */
    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(Level.WARNING, "Persistence writes still running after " + CLOSE_TIMEOUT.toSeconds() + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The whole file's text: the header, then each body line ended by {@code \n}. */
    private static <T> String encode(PersistedFile<T> file, T value) {
        StringBuilder text = new StringBuilder(header(file)).append('\n');
        for (String line : file.write(value)) {
            if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(file.name() + " wrote a line holding a line terminator");
            }
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /** The version header line of a file at its current version. */
    private static String header(PersistedFile<?> file) {
        return "#andysdice " + file.name() + " " + file.version();
    }

    /** Checks the folder can be written at all, so an unwritable one is reported at start-up. Writer thread. */
    private void checkWritable() {
        if (!DataFolder.isWritable(folder)) {
            reportUnwritable("Can't write a probe file in " + folder);
        }
    }

    /** Reads a file. Writer thread. */
    private <T> T loadNow(PersistedFile<T> file) {
        Path path = folder.resolve(file.name());
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return file.fresh();
        } catch (IOException e) {
            return setAside(file, path, false, "it can't be opened: " + e);
        }
        try {
            List<String> lines = decode(bytes).lines().toList();
            Matcher header = lines.isEmpty() ? null : HEADER.matcher(lines.getFirst());
            if (header == null || !header.matches() || !header.group(1).equals(file.name())) {
                throw new PersistedFile.UnreadableException("its first line isn't the header \"" + header(file)
                        + "\"" + (lines.isEmpty() ? " (it is empty)" : ""));
            }
            int version = Integer.parseInt(header.group(2));
            if (version > file.version()) {
                return setAside(file, path, true, "its version " + version + " is newer than " + file.version());
            }
            List<String> body = lines.subList(1, lines.size());
            for (int at = version; at < file.version(); at++) {
                body = file.migrate(at, body);
            }
            T value = file.read(body);
            if (version < file.version()) {
                LOG.log(Level.INFO, "Migrated " + path + " from version " + version + " to " + file.version());
            }
            return value;
        } catch (CharacterCodingException e) {
            return setAside(file, path, false, "it isn't UTF-8 text");
        } catch (PersistedFile.UnreadableException e) {
            return setAside(file, path, false, e.getMessage());
        }
    }

    /** Decodes strictly: a byte sequence that isn't UTF-8 is an error, not a replacement character. */
    private static String decode(byte[] bytes) throws CharacterCodingException {
        CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        String text = chars.toString();
        // A byte-order mark is legal UTF-8 but no part of the header; Notepad used to write one.
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /**
     * Renames an unusable file aside, never over another file, reports it, and returns the fresh value. Writer
     * thread.
     *
     * @param newer whether it was set aside for being newer (rather than unreadable)
     * @param why what was wrong, for the log
     */
    private <T> T setAside(PersistedFile<T> file, Path path, boolean newer, String why) {
        String base = file.name() + ".bad-" + STAMP.format(clock.instant());
        for (int n = 1; ; n++) {
            Path target = path.resolveSibling(n == 1 ? base : base + "-" + n);
            if (Files.exists(target)) {
                continue;
            }
            try {
                setAside.move(path, target);
            } catch (FileAlreadyExistsException e) {
                // Taken between the check and the rename; try the next name.
                continue;
            } catch (IOException e) {
                // Left where it is; writing the fresh file would overwrite it, so this file isn't written this run.
                blocked.add(file.name());
                LOG.log(Level.WARNING, "Can't use " + path + " (" + why + ") and can't set it aside either: " + e);
                tell(new Notice(file.name() + " couldn't be read",
                        "It couldn't be set aside either, so the file is left as it is. Andy's Dice started it "
                                + "fresh, and won't save changes to it until it next starts."));
                return file.fresh();
            }
            LOG.log(Level.WARNING, "Set aside " + path + " as " + target.getFileName() + ": " + why);
            String reason = newer ? "It was written by a newer version of Andy's Dice, so this one started it fresh."
                    : "It couldn't be read, so Andy's Dice started it fresh.";
            tell(new Notice(file.name() + " was set aside",
                    reason + " The old file is kept as " + target.getFileName() + "."));
            return file.fresh();
        }
    }

    /** Writes a file's latest pending text, if a later task hasn't already. Writer thread. */
    private void writePending(String name) {
        String text = pending.remove(name);
        if (text == null) {
            return;
        }
        if (blocked.contains(name)) {
            LOG.log(Level.DEBUG, "Not writing " + name + ": it couldn't be set aside");
            return;
        }
        Path target = folder.resolve(name);
        Path temp = null;
        try {
            // The folder may have been deleted mid-run; making it again is all it takes.
            Files.createDirectories(folder);
            temp = Files.createTempFile(folder, name + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                // On disk before the rename, so the rename can never expose a file whose content isn't there yet.
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
        } catch (IOException e) {
            reportUnwritable("Can't write " + target + ": " + e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    // The folder is failing already, and that is reported; a stray temp file is harmless.
                }
            }
        }
    }

    /** Logs a failed write and, the first time this run, posts the one Notice. Writer thread. */
    private void reportUnwritable(String logLine) {
        LOG.log(Level.WARNING, logLine);
        if (!unwritableReported) {
            unwritableReported = true;
            tell(new Notice("Changes can't be saved",
                    "Andy's Dice can't write to its data folder, " + folder + ". It keeps running, but what changes "
                            + "now is kept only until it closes."));
        }
    }

    /** Hands a Notice on; a consumer that throws must not cost the write thread. Writer thread. */
    private void tell(Notice notice) {
        try {
            notices.accept(notice);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Couldn't post the Notice \"" + notice.title() + "\"", e);
        }
    }
}
