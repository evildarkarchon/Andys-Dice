package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One instance (spec §3.6, ADR-0007): a {@code FileChannel.tryLock} on a lock file in the data folder. Whoever holds
 * it is the running copy; a second launch finds it held. The second copy is checked both in this JVM and as a real
 * second process, since the lock is the operating system's and only another process shows it truly.
 */
class InstanceLockTest {

    @TempDir
    Path folder;

    @Test
    void theFirstLaunchTakesTheLockFileInTheDataFolder() throws IOException {
        try (InstanceLock lock = InstanceLock.tryAcquire(folder).orElseThrow()) {
            assertThat(lock.file()).isEqualTo(folder.resolve(InstanceLock.FILE_NAME));
            assertThat(lock.file()).exists();
        }
    }

    @Test
    void whileItIsHeldASecondLaunchInThisJvmIsRefused() throws IOException {
        try (InstanceLock first = InstanceLock.tryAcquire(folder).orElseThrow()) {
            assertThat(InstanceLock.tryAcquire(folder)).isEmpty();
            assertThat(first.file()).exists();
        }
    }

    @Test
    void whileItIsHeldAnotherProcessCantTakeIt() throws Exception {
        try (InstanceLock lock = InstanceLock.tryAcquire(folder).orElseThrow()) {
            assertThat(otherProcessTries(lock.file())).isEqualTo("held");
        }
    }

    @Test
    void onceReleasedItCanBeTakenAgain() throws Exception {
        InstanceLock.tryAcquire(folder).orElseThrow().close();

        Optional<InstanceLock> again = InstanceLock.tryAcquire(folder);
        assertThat(again).isPresent();
        again.get().close();
        assertThat(otherProcessTries(folder.resolve(InstanceLock.FILE_NAME))).isEqualTo("free");
    }

    @Test
    void aMissingDataFolderIsCreated() throws IOException {
        Path missing = folder.resolve("not-yet");

        try (InstanceLock lock = InstanceLock.tryAcquire(missing).orElseThrow()) {
            assertThat(lock.file()).exists();
        }
    }

    /** A data folder that can't hold the lock file (a file sits where it should be): the caller decides. */
    @Test
    void aFolderThatCantHoldTheLockFileThrows() throws IOException {
        Path notAFolder = Files.writeString(folder.resolve("file"), "x");

        org.assertj.core.api.Assertions.assertThatIOException()
                .isThrownBy(() -> InstanceLock.tryAcquire(notAFolder));
    }

    /**
     * Runs a single-file Java program in a second JVM that tries the same lock and prints "held" or "free".
     *
     * @param lockFile the lock file
     * @return what the other process printed
     */
    private String otherProcessTries(Path lockFile) throws Exception {
        Path source = folder.resolve("TryLock.java");
        Files.writeString(source, String.join("\n",
                "import java.nio.channels.FileChannel;",
                "import java.nio.file.Path;",
                "import java.nio.file.StandardOpenOption;",
                "public class TryLock {",
                "  public static void main(String[] args) throws Exception {",
                "    try (FileChannel c = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE,",
                "        StandardOpenOption.CREATE)) {",
                "      System.out.print(c.tryLock() == null ? \"held\" : \"free\");",
                "    }",
                "  }",
                "}"), StandardCharsets.UTF_8);
        String java = ProcessHandle.current().info().command().orElseThrow();
        ProcessBuilder builder = new ProcessBuilder(java, source.toString(), lockFile.toString())
                .redirectErrorStream(true);
        // The test JVM's own options (JDK_JAVA_OPTIONS from a dev shell, say) have no business in the child.
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = builder.start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the other process finished").isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.exitValue()).as("the other process's exit status (output: %s)", output).isZero();
        return output;
    }
}
