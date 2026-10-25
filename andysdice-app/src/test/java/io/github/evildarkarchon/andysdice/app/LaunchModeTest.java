package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the command line picks the app's mode (the Player's app, the hidden smoke test, the dev-only bench), and
 * where each mode keeps its persisted state: the bench's Rolls must never reach the Player's Roll History.
 */
class LaunchModeTest {

    @TempDir
    Path dataFolder;

    @Test
    void theCommandLinePicksTheModeAndTheSmokeTestWinsOverTheBench() {
        assertThat(LaunchMode.of(List.of())).isEqualTo(LaunchMode.PLAYER);
        assertThat(LaunchMode.of(List.of("--something-else"))).isEqualTo(LaunchMode.PLAYER);
        assertThat(LaunchMode.of(List.of(SmokeTest.FLAG))).isEqualTo(LaunchMode.SMOKE_TEST);
        assertThat(LaunchMode.of(List.of(Bench.FLAG))).isEqualTo(LaunchMode.BENCH);
        assertThat(LaunchMode.of(List.of(Bench.FLAG, SmokeTest.FLAG))).isEqualTo(LaunchMode.SMOKE_TEST);
    }

    @Test
    void onlyTheHiddenModesRunUnattendedAndFasterThanRealTime() {
        assertThat(LaunchMode.PLAYER.unattended()).isFalse();
        assertThat(LaunchMode.PLAYER.speed()).isEqualTo(1);
        assertThat(LaunchMode.SMOKE_TEST.unattended()).isTrue();
        assertThat(LaunchMode.SMOKE_TEST.speed()).isEqualTo(SmokeTest.SPEED);
        assertThat(LaunchMode.BENCH.unattended()).isTrue();
        assertThat(LaunchMode.BENCH.speed()).isEqualTo(Bench.SPEED);
    }

    @Test
    void thePlayersAppKeepsItsStateInTheDataFolder() throws Exception {
        assertThat(LaunchMode.PLAYER.openState(dataFolder)).isEqualTo(dataFolder);
        assertThat(LaunchMode.SMOKE_TEST.openState(dataFolder)).isEqualTo(dataFolder);

        LaunchMode.PLAYER.closeState(dataFolder);

        assertThat(dataFolder).isDirectory();
    }

    @Test
    void theBenchKeepsItsRollsOutOfThePlayersRollHistory() throws Exception {
        Path state = LaunchMode.BENCH.openState(dataFolder);
        try {
            assertThat(state).isDirectory().isEmptyDirectory();
            assertThat(state.toAbsolutePath().normalize().startsWith(dataFolder.toAbsolutePath().normalize()))
                    .as("the bench's folder is outside the data folder").isFalse();

            // What the bench's Rolls write goes into its own folder, not the Player's history.txt.
            try (Persistence persistence = new Persistence(state, notice -> {})) {
                persistence.save(RollHistory.FILE, RollHistory.EMPTY);
            }
            assertThat(state.resolve(RollHistory.FILE_NAME)).exists();
            assertThat(dataFolder.resolve(RollHistory.FILE_NAME)).doesNotExist();
        } finally {
            LaunchMode.BENCH.closeState(state);
        }

        // Nothing of the bench's run is left behind.
        assertThat(state).doesNotExist();
    }

    @Test
    void eachBenchRunStartsFromAFreshState() throws Exception {
        Path first = LaunchMode.BENCH.openState(dataFolder);
        Files.writeString(first.resolve(RollHistory.FILE_NAME), "left over");
        Path second = LaunchMode.BENCH.openState(dataFolder);
        try {
            assertThat(second).isNotEqualTo(first).isEmptyDirectory();
        } finally {
            LaunchMode.BENCH.closeState(first);
            LaunchMode.BENCH.closeState(second);
        }
    }
}
