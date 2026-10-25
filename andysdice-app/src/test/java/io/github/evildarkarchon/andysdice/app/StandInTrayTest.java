package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.app.StandInTray.Phase;
import io.github.evildarkarchon.andysdice.app.StandInTray.Report;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The stand-in tray behind the Tray seam (ADR-0011): it reports each Throw's phases and the Roll's rest on the
 * injected playback clock, which only the test advances, and the result strip reveals the total at exactly that
 * rest.
 */
class StandInTrayTest extends HeadlessUiTest {

    @Test
    void aRerollChainIsReportedThrowByThrowOnTheInjectedClock() throws Exception {
        // 4d6rr1kh3: the first Die shows a 1, so a Reroll Throw of it follows, showing a 3.
        faces.then(1, 4, 5, 6, 3);
        advance(2); // the Roll starts wherever the clock has got to, not at zero
        double start = clock.time();

        roll("4d6rr1kh3");
        ChainPlayback chain = tray.showing();
        assertThat(chain.plan().throwChain()).hasSize(2);
        playToRest();

        double rest0 = chain.plan().throwChain().getFirst().recording().restTime();
        double reroll = chain.throwStart(1);
        List<Report> reports = tray.reports();
        assertThat(reports).extracting(Report::phase)
                .containsExactly(Phase.EMPTY, Phase.THROWING, Phase.HOLDING, Phase.THROWING, Phase.AT_REST);
        assertThat(reports).extracting(Report::throwIndex).containsExactly(-1, 0, 0, 1, 1);
        // Each phase is first drawn on the first 60 Hz frame at or after it begins.
        assertThat(reports.get(1).clockTime()).isCloseTo(start, within(1e-9));
        assertThat(reports.get(2).clockTime() - start).isBetween(rest0, rest0 + FRAME + 1e-9);
        assertThat(reports.get(3).clockTime() - start).isBetween(reroll, reroll + FRAME + 1e-9);
        assertThat(reports.get(4).clockTime() - start).isBetween(chain.restTime(), chain.restTime() + FRAME + 1e-9);
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("4d6rr1kh3 = 15");
    }

    @Test
    void theTotalIsRevealedOnTheFrameTheTrayReportsRest() throws Exception {
        faces.then(6);
        roll("1d6+1");
        double rest = tray.showing().restTime();

        // A frame short of rest the Dice are still moving and nothing is revealed.
        onFx(() -> {
            while (clock.time() + FRAME < rest) {
                clock.advance(FRAME);
            }
        });
        assertThat(tray.phase()).isEqualTo(Phase.THROWING);
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("Rolling…");
        assertThat(pulses.running).isTrue();

        advance(FRAME);
        assertThat(tray.phase()).isEqualTo(Phase.AT_REST);
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("1d6+1 = 7");
        // With the Roll over, nothing needs JavaFX's pulse any more.
        assertThat(pulses.running).isFalse();
    }

    @Test
    void whileThePhysicsWorkerHoldsTheRollNothingIsOnTheTray() throws Exception {
        physics.setHolding(true);
        roll("2d8");
        advance(1);

        assertThat(tray.phase()).isEqualTo(Phase.EMPTY);
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("Rolling…");

        onFx(() -> physics.finish());
        onFx(() -> {});
        assertThat(tray.phase()).isEqualTo(Phase.THROWING);
    }
}
