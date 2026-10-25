package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How the command line is checked before any window opens: an empty one or one of the app's own flags picks the
 * launch mode; anything else is rejected, naming each rejected argument and the accepted flags, and the process
 * exits with its own status.
 */
class CommandLineTest {

    @Test
    void anEmptyCommandLineIsThePlayersApp() {
        assertThat(CommandLine.parse(List.of())).isEqualTo(new CommandLine.Accepted(LaunchMode.PLAYER));
    }

    @Test
    void eachOfTheAppsOwnFlagsPicksItsMode() {
        assertThat(CommandLine.parse(List.of("--smoke-test")))
                .isEqualTo(new CommandLine.Accepted(LaunchMode.SMOKE_TEST));
        assertThat(CommandLine.parse(List.of("--bench"))).isEqualTo(new CommandLine.Accepted(LaunchMode.BENCH));
    }

    @Test
    void theAcceptedFlagsAreExactlyTheSmokeTestAndTheBench() {
        // The gallery's --gallery isn't among them: the app doesn't route it (it runs only from render's dev build).
        assertThat(CommandLine.FLAGS).containsExactly("--smoke-test", "--bench");
    }

    @Test
    void aRejectedCommandLineHasAStatusOfItsOwn() {
        assertThat(CommandLine.REJECTED_STATUS).isEqualTo(7)
                .isNotIn(0, AndysDice.STARTUP_FAILED, SmokeTest.FAILED, AndysDice.RUNTIME_FAILED,
                        AlreadyRunning.EXIT_STATUS, Bench.MISSED, Bench.FAILED);
    }

    @Test
    void aMistypedFlagIsRejectedNamingItAndTheAcceptedFlags() {
        CommandLine line = CommandLine.parse(List.of("--smoke"));

        assertThat(line).isInstanceOf(CommandLine.Rejected.class);
        CommandLine.Rejected rejected = (CommandLine.Rejected) line;
        assertThat(rejected.arguments()).containsExactly("--smoke");
        assertThat(rejected.message()).contains("\"--smoke\"").contains("--smoke-test, --bench");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--gallery", "--SMOKE-TEST", "-smoke-test", "smoke-test", "--smoke-test=1", "",
        "C:\\Users\\someone\\roll.txt"})
    void anythingButTheExactFlagIsRejected(String argument) {
        CommandLine line = CommandLine.parse(List.of(argument));

        assertThat(line).isInstanceOf(CommandLine.Rejected.class);
        assertThat(((CommandLine.Rejected) line).arguments()).containsExactly(argument);
        assertThat(((CommandLine.Rejected) line).message()).contains("\"" + argument + "\"");
    }

    @Test
    void everyRejectedArgumentIsNamedInOrder() {
        CommandLine line = CommandLine.parse(List.of("one", "--smoke-test", "two words"));

        CommandLine.Rejected rejected = (CommandLine.Rejected) line;
        assertThat(rejected.arguments()).containsExactly("one", "two words");
        assertThat(rejected.message()).contains("\"one\"").contains("\"two words\"");
    }

    @Test
    void twoModesAreRejectedRatherThanOnePickedSilently() {
        CommandLine line = CommandLine.parse(List.of("--bench", "--smoke-test"));

        assertThat(line).isInstanceOf(CommandLine.Rejected.class);
        CommandLine.Rejected rejected = (CommandLine.Rejected) line;
        // The first flag would pick the mode; the second is the one that can't be honoured.
        assertThat(rejected.arguments()).containsExactly("--smoke-test");
        assertThat(rejected.message()).contains("\"--smoke-test\"").contains("\"--bench\"");
    }

    @Test
    void aRepeatedFlagIsRejectedToo() {
        CommandLine line = CommandLine.parse(List.of("--smoke-test", "--smoke-test"));

        assertThat(line).isInstanceOf(CommandLine.Rejected.class);
        assertThat(((CommandLine.Rejected) line).arguments()).containsExactly("--smoke-test");
    }
}
