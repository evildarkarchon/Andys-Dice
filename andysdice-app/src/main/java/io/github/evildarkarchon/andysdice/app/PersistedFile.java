package io.github.evildarkarchon.andysdice.app;

import java.io.Serial;
import java.util.List;

/**
 * The format of one of the app's persisted files (ADR-0007): its name in the data folder, its current version, and
 * how its body is read, migrated forward and written. {@link Persistence} does everything else every file shares:
 * the version header line, UTF-8, the atomic write on the writer thread, and setting aside a file it can't read.
 *
 * <p>The body is the file's lines after the header, without line terminators. A format must never write a line
 * holding {@code \n} or {@code \r}; the data is over closed alphabets with no control characters, so no format
 * needs escaping (ADR-0007).
 *
 * <p>Thread contract: {@link #read} and {@link #migrate} run on the persistence writer thread; {@link #write} runs
 * on whichever thread calls {@link Persistence#save}, so it can take its snapshot of state confined to that thread
 * (the JavaFX thread's, usually). An implementation should be stateless or immutable.
 *
 * @param <T> what the file holds, as an immutable value
 */
interface PersistedFile<T> {

    /**
     * Returns the file's name in the data folder, which its header line repeats.
     *
     * @return the name, e.g. {@code settings.properties}
     */
    String name();

    /**
     * Returns the version this build writes and reads directly; older versions reach it through {@link #migrate}.
     *
     * @return the current version, 1 or more
     */
    int version();

    /**
     * Returns what the file holds when there is none yet, or when the one there was set aside.
     *
     * @return the fresh value
     */
    T fresh();

    /**
     * Reads a body at the {@linkplain #version() current version}.
     *
     * @param body the lines after the header
     * @return the value
     * @throws UnreadableException if the body isn't this format; the file is then set aside and started fresh
     */
    T read(List<String> body) throws UnreadableException;

    /**
     * Migrates a body one version forward, from {@code from} to {@code from + 1}. {@link Persistence} calls it
     * once per version, oldest first, until the body is at the current version, then {@link #read reads} it. The
     * default knows no older version, as befits a format still at version 1: override it when a version is added,
     * keeping every earlier step, so a file from any older build still loads.
     *
     * @param from the body's version, at least 1 and below {@link #version()}
     * @param body the body at {@code from}
     * @return the body at {@code from + 1}
     * @throws UnreadableException if the body can't be migrated; the file is then set aside and started fresh
     */
    default List<String> migrate(int from, List<String> body) throws UnreadableException {
        throw new UnreadableException(name() + " has no version " + from);
    }

    /**
     * Writes a value as a body at the current version.
     *
     * @param value the value
     * @return the lines after the header, none holding a line terminator
     */
    List<String> write(T value);

    /** A file's content isn't this format (or this version of it), so the file must be set aside. */
    final class UnreadableException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Says why, for the log.
         *
         * @param reason what was wrong
         */
        UnreadableException(String reason) {
            super(reason);
        }

        /**
         * Says why, with the exception that showed it, for the log.
         *
         * @param reason what was wrong
         * @param cause what was thrown
         */
        UnreadableException(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
