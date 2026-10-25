package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.app.StandInTray.Phase;
import io.github.evildarkarchon.andysdice.app.StandInTray.Report;
import java.util.List;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fast-forward with the Shift-alone hold (spec §3.2 "Fast-forward", #51), driven through the real window with the
 * public robot's key presses and timed on the injected playback clock only.
 */
class FastForwardTest extends HeadlessUiTest {

    @Test
    void holdingShiftAloneForTheHoldTimeRunsTheClockAtFourTimes() throws Exception {
        assertThat(fromFx(() -> clock.speed())).isEqualTo(1.0);

        press(KeyCode.SHIFT);
        advance(0.15);
        assertThat(fromFx(() -> clock.speed())).as("still within the hold").isEqualTo(1.0);
        advance(0.05);

        assertThat(fromFx(() -> clock.speed())).isEqualTo(4.0);
        double before = fromFx(() -> clock.time());
        advance(0.5);
        assertThat(fromFx(() -> clock.time()) - before).as("half a real second of playback at x4")
                .isCloseTo(2.0, within(1e-9));
    }

    @Test
    void anotherKeyDuringTheHoldCancelsThatHoldForGood() throws Exception {
        press(KeyCode.SHIFT);
        advance(0.1);
        // Shift+= types "+" on a US layout: the Player is typing, not fast-forwarding.
        press(KeyCode.EQUALS);
        release(KeyCode.EQUALS);
        advance(2);

        assertThat(fromFx(() -> clock.speed())).isEqualTo(1.0);
        assertThat(fromFx(() -> ui().expressionBox().getText())).as("the key still reached the box").isEqualTo("+");
    }

    @Test
    void aKeyPressedOnceFastForwardIsOnEndsItForTheRestOfTheHold() throws Exception {
        press(KeyCode.SHIFT);
        advance(0.5);
        assertThat(fromFx(() -> clock.speed())).isEqualTo(4.0);

        press(KeyCode.A);
        release(KeyCode.A);
        assertThat(fromFx(() -> clock.speed())).isEqualTo(1.0);
        advance(1);
        assertThat(fromFx(() -> clock.speed())).isEqualTo(1.0);
    }

    @Test
    void releasingShiftReturnsToNormalSpeedAtOnce() throws Exception {
        press(KeyCode.SHIFT);
        advance(0.5);
        assertThat(fromFx(() -> clock.speed())).isEqualTo(4.0);

        release(KeyCode.SHIFT);

        // No advance in between: the very next frame already plays at x1.
        assertThat(fromFx(() -> clock.speed())).isEqualTo(1.0);
        double before = fromFx(() -> clock.time());
        advance(0.5);
        assertThat(fromFx(() -> clock.time()) - before).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void aNewHoldAfterACancelledOneFastForwardsAgain() throws Exception {
        press(KeyCode.SHIFT);
        press(KeyCode.A);
        release(KeyCode.A);
        release(KeyCode.SHIFT);
        advance(0.5);

        press(KeyCode.SHIFT);
        advance(FastForward.HOLD_SECONDS);

        assertThat(fromFx(() -> clock.speed())).isEqualTo(4.0);
    }

    @Test
    void shiftsAutoRepeatDoesNotRestartTheHold() throws Exception {
        press(KeyCode.SHIFT);
        advance(0.1);
        // A held key repeats its press; the hold still counts from the first one.
        press(KeyCode.SHIFT);
        advance(0.1);

        assertThat(fromFx(() -> clock.speed())).isEqualTo(4.0);
    }

    @Test
    void fastForwardPlaysTheWholeChainAtFourTimesAndSkipsNothing() throws Exception {
        // 4d6rr1kh3: the first Die shows a 1, so the pickup and a Reroll Throw of it follow, showing a 3.
        faces.then(1, 4, 5, 6, 3);
        press(KeyCode.SHIFT);
        advance(FastForward.HOLD_SECONDS);
        assertThat(fromFx(() -> clock.speed())).isEqualTo(4.0);
        double start = fromFx(() -> clock.time());

        // A click on Roll, not Enter: Enter is another key, which would end this hold.
        onFx(() -> {
            ui().expressionBox().setText("4d6rr1kh3");
            ui().rollButton().fire();
        });
        onFx(() -> {});
        ChainPlayback chain = tray.showing();
        assertThat(chain.plan().throwChain()).hasSize(2);
        int frames = fromFx(() -> {
            int n = 0;
            while (controller().underWay()) {
                clock.advance(FRAME);
                n++;
            }
            return n;
        });

        // A quarter of the real frames the chain's playback time would take at x1, rounded up to a whole frame.
        assertThat(frames).isEqualTo((int) Math.ceil(chain.restTime() / (FRAME * 4) - 1e-9));
        // Every Throw, the hold between them and the rest are each drawn: nothing was jumped over.
        List<Report> reports = tray.reports();
        assertThat(reports).extracting(Report::phase)
                .containsExactly(Phase.EMPTY, Phase.THROWING, Phase.HOLDING, Phase.THROWING, Phase.AT_REST);
        assertThat(reports).extracting(Report::throwIndex).containsExactly(-1, 0, 0, 1, 1);
        assertThat(reports.get(4).clockTime() - start)
                .as("the whole chain's playback time went by")
                .isBetween(chain.restTime(), chain.restTime() + 4 * FRAME + 1e-9);
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("4d6rr1kh3 = 15");
    }

    /**
     * Lets go of Shift after every test, before the base class closes the window: the headless platform's
     * keyboard is shared by the whole test JVM, so a Shift left down would hold it down for the next test.
     */
    @AfterEach
    void letGoOfShift() throws Exception {
        release(KeyCode.SHIFT);
    }

    /** Presses a key with the robot, as the Player would, and waits until the window has handled it. */
    private void press(KeyCode key) throws Exception {
        onFx(() -> robot().keyPress(key));
        onFx(() -> {});
    }

    /** Releases a key with the robot and waits until the window has handled it. */
    private void release(KeyCode key) throws Exception {
        onFx(() -> robot().keyRelease(key));
        onFx(() -> {});
    }
}
