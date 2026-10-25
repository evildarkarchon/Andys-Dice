package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The app's log (spec §6): {@code System.Logger} over {@code java.util.logging}, into
 * {@code logs\andysdice.log} in the data folder. The packaged launcher has no console, so what libraries print
 * to {@code System.out} and {@code System.err} (Prism's pipeline report, LWJGL's loader, an uncaught exception's
 * trace) goes into the log as well, still reaching the console in a dev run.
 *
 * <p>Each test installs the log into a temporary data folder and closes it again, which restores the JVM's
 * logging and standard streams.
 */
@SuppressWarnings("try") // a test's log is installed for its try block and often never referenced inside it
class AppLogTest {

    @TempDir
    Path data;

    @Test
    void systemLoggerRecordsGoToTheLogFileInTheDataFolder() throws IOException {
        try (AppLog log = AppLog.install(data)) {
            System.getLogger("io.github.evildarkarchon.andysdice.test").log(System.Logger.Level.INFO,
                    "a record for the log");

            assertThat(log.file()).isEqualTo(data.resolve("logs").resolve("andysdice.log"));
        }

        assertThat(read()).contains("INFO").contains("io.github.evildarkarchon.andysdice.test")
                .contains("a record for the log");
    }

    @Test
    void debugRecordsAreLeftOut() throws IOException {
        try (AppLog ignored = AppLog.install(data)) {
            System.getLogger("io.github.evildarkarchon.andysdice.test").log(System.Logger.Level.DEBUG, "chatter");
        }

        assertThat(read()).doesNotContain("chatter");
    }

    @Test
    void anExceptionsTraceIsLogged() throws IOException {
        try (AppLog ignored = AppLog.install(data)) {
            System.getLogger("io.github.evildarkarchon.andysdice.test").log(System.Logger.Level.ERROR, "it broke",
                    new IllegalStateException("the cause"));
        }

        assertThat(read()).contains("it broke").contains("java.lang.IllegalStateException: the cause")
                .contains("AppLogTest.anExceptionsTraceIsLogged");
    }

    @Test
    void standardOutputAndErrorLinesAreLoggedAndStillPrinted() throws IOException {
        PrintStream console = System.out;
        try (AppLog ignored = AppLog.install(data)) {
            System.out.println("Prism pipeline name = com.sun.prism.sw.SWPipeline");
            System.err.print("a line printed ");
            System.err.println("in two parts");

            assertThat(System.out).isNotSameAs(console);
        }

        assertThat(System.out).isSameAs(console);
        assertThat(read()).contains("System.out: Prism pipeline name = com.sun.prism.sw.SWPipeline")
                .contains("System.err: a line printed in two parts");
    }

    @Test
    void closingRestoresTheLoggingItReplaced() throws IOException {
        try (AppLog ignored = AppLog.install(data)) {
            System.getLogger("io.github.evildarkarchon.andysdice.test").log(System.Logger.Level.INFO, "inside");
        }
        System.getLogger("io.github.evildarkarchon.andysdice.test").log(System.Logger.Level.INFO, "after close");

        assertThat(read()).contains("inside").doesNotContain("after close");
    }

    private String read() throws IOException {
        return Files.readString(data.resolve("logs").resolve("andysdice.log"), StandardCharsets.UTF_8);
    }
}
