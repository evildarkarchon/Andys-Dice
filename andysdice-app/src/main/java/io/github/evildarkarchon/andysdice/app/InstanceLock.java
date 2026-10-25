package io.github.evildarkarchon.andysdice.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * The one-instance rule (spec §3.6, ADR-0007): a {@link FileChannel#tryLock} on {@value #FILE_NAME} in the data
 * folder. The copy that holds it is the running one; a second launch finds it held, says "Andy's Dice is already
 * running" ({@link AlreadyRunning}) and exits. The two copies never talk to each other.
 *
 * <p>The lock is the operating system's, so it goes when the process does, however it ends; the file itself is
 * left behind on purpose (deleting it on exit would race a launch that has just opened it).
 *
 * <p>Thread contract: taken and released on one thread (the main thread, around the whole run).
 */
final class InstanceLock implements AutoCloseable {

    /** The lock file's name in the data folder. */
    static final String FILE_NAME = "andysdice.lock";

    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(Path file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Tries to become the running copy for a data folder, creating the folder and the lock file if needed.
     *
     * <p>Keep the returned lock reachable for the whole run and {@link #close} it at the end: a channel that is
     * garbage-collected is closed by its cleaner, and that would release the lock early.
     *
     * @param dataFolder the data folder chosen for this run
     * @return the held lock, or empty if another copy (in this JVM or another process) holds it
     * @throws IOException if the folder or the lock file can't be created or opened (an unwritable folder); the
     *     caller may then run without the rule
     */
    static Optional<InstanceLock> tryAcquire(Path dataFolder) throws IOException {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Files.createDirectories(dataFolder);
        Path file = dataFolder.resolve(FILE_NAME);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // Held by this same JVM: the JDK refuses before asking the OS. Only tests run two copies in one JVM.
            lock = null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            return Optional.empty();
        }
        return Optional.of(new InstanceLock(file, channel, lock));
    }

    /**
     * Returns the lock file.
     *
     * @return its path in the data folder
     */
    Path file() {
        return file;
    }

    /** Releases the lock and closes the file, so the next launch can run. */
    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
