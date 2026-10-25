package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

/**
 * The Queued Roll (spec §3.2 "Entry", "Keyboard"; GLOSSARY), headless on the injected clock (ADR-0011): Enter while
 * Dice move queues one Roll behind a "next: …" chip, a later Enter replaces it, Esc cancels it, and it starts about
 * 0.9 s after the current Roll Result is revealed. No test sleeps: the beat is played on the clock by hand.
 */
class QueuedRollTest extends HeadlessUiTest {

    private static final String WOULDNT_SETTLE =
            "The Dice wouldn't settle. Nothing was rolled. Press Enter to try again.";

    @Test
    void enterDuringAThrowQueuesTheRollBehindANextChip() throws Exception {
        roll("1d6");
        advance(0.1);
        assertThat(tray.phase()).isEqualTo(StandInTray.Phase.THROWING);

        enter("2d6+1");

        assertThat(fromFx(() -> entry().queue().queued())).contains("2d6+1");
        assertThat(fromFx(() -> chip().isVisible())).isTrue();
        assertThat(fromFx(() -> chip().getText())).isEqualTo("next: 2d6+1");
        // The current Roll plays on untouched; the queued one isn't simulated yet.
        assertThat(tray.shows()).isEqualTo(1);
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("Rolling…");
        // Accepted, so the box keeps it all selected.
        assertThat(fromFx(() -> ui().expressionBox().getSelectedText())).isEqualTo("2d6+1");
    }

    @Test
    void theChipFollowsTheRollButtonAndTakesNoRoomWhileNothingIsQueued() throws Exception {
        assertThat(fromFx(() -> chip().isVisible())).isFalse();
        assertThat(fromFx(() -> chip().isManaged())).isFalse();
        assertThat(fromFx(() -> ui().toolbar().getChildren().indexOf(chip())))
                .isGreaterThan(fromFx(() -> ui().toolbar().getChildren().indexOf(ui().rollButton())));

        roll("1d6");
        enter("1d8");
        pulse(stage.getScene());

        double rollRight = fromFx(() -> sceneMaxX(ui().rollButton()));
        assertThat(fromFx(() -> chip().localToScene(chip().getLayoutBounds()).getMinX())).isGreaterThan(rollRight);
        assertThat(fromFx(() -> chip().getTextFill())).isEqualTo(Palette.TEXT);
    }

    @Test
    void aLaterEnterReplacesTheQueuedRoll() throws Exception {
        faces.then(2, 5);
        roll("1d6");
        enter("2d6");
        enter("1d20+3");

        assertThat(fromFx(() -> entry().queue().queued())).contains("1d20+3");
        assertThat(fromFx(() -> chip().getText())).isEqualTo("next: 1d20+3");

        playToRest();
        advance(RollQueue.BEAT_SECONDS);
        onFx(() -> {});
        playToRest();
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("1d20+3 = 8");
        assertThat(tray.shows()).isEqualTo(2);
    }

    @Test
    void theQueuedRollStartsAboutABeatAfterTheReveal() throws Exception {
        faces.then(4, 6);
        roll("1d6");
        enter("1d6+1");
        playToRest();
        double revealed = clock.time();
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("1d6 = 4");
        assertThat(RollQueue.BEAT_SECONDS).isEqualTo(0.9);

        // Through the beat the Roll Result stays up and the Queued Roll waits, with the clock still pulsing.
        advance(0.85);
        assertThat(fromFx(() -> controller().underWay())).isFalse();
        assertThat(fromFx(() -> chip().isVisible())).isTrue();
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("1d6 = 4");
        assertThat(pulses.running).isTrue();

        advance(0.05);
        onFx(() -> {});
        assertThat(clock.time() - revealed).isBetween(0.89, 0.92);
        assertThat(fromFx(() -> controller().underWay())).isTrue();
        assertThat(fromFx(() -> chip().isVisible())).isFalse();
        assertThat(fromFx(() -> entry().queue().queued())).isEmpty();
        assertThat(tray.shows()).isEqualTo(2);

        playToRest();
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("1d6+1 = 7");
    }

    @Test
    void enterWithNothingMovingRollsAtOnce() throws Exception {
        roll("1d6");
        playToRest();

        enter("1d8");
        onFx(() -> {});

        assertThat(fromFx(() -> entry().queue().waiting())).isFalse();
        assertThat(fromFx(() -> chip().isVisible())).isFalse();
        assertThat(fromFx(() -> controller().underWay())).isTrue();
        assertThat(tray.shows()).isEqualTo(2);
    }

    @Test
    void enterWhileTheRollIsStillPreSimulatingQueuesToo() throws Exception {
        physics.setHolding(true);
        roll("1d6");
        assertThat(tray.shows()).isZero();

        enter("1d4");

        assertThat(fromFx(() -> entry().queue().queued())).contains("1d4");
        onFx(() -> physics.finish());
        // The plan comes back through Platform.runLater; this block runs after it.
        onFx(() -> {});
        assertThat(tray.shows()).isEqualTo(1);
        assertThat(fromFx(() -> entry().queue().queued())).contains("1d4");
    }

    @Test
    void escCancelsTheQueuedRoll() throws Exception {
        roll("1d6");
        enter("2d6");

        onFx(() -> robot().keyType(KeyCode.ESCAPE));

        assertThat(fromFx(() -> entry().queue().waiting())).isFalse();
        assertThat(fromFx(() -> chip().isVisible())).isFalse();
        playToRest();
        advance(2);
        onFx(() -> {});
        assertThat(tray.shows()).isEqualTo(1);
        assertThat(fromFx(() -> controller().underWay())).isFalse();
        // With nothing queued, the pulse stops once the Roll is over.
        assertThat(pulses.running).isFalse();
    }

    @Test
    void escClosesTheErrorPopupBeforeItCancelsTheQueuedRoll() throws Exception {
        roll("1d6");
        enter("2d6");
        // An invalid Enter neither rolls, queues nor replaces the Queued Roll.
        enter("1d7");
        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isTrue();
        assertThat(fromFx(() -> entry().queue().queued())).contains("2d6");

        onFx(() -> robot().keyType(KeyCode.ESCAPE));
        assertThat(fromFx(() -> entry().errorPopup().isShowing())).isFalse();
        assertThat(fromFx(() -> entry().queue().queued())).contains("2d6");

        onFx(() -> robot().keyType(KeyCode.ESCAPE));
        assertThat(fromFx(() -> entry().queue().queued())).isEmpty();
    }

    @Test
    void escWithNothingQueuedDoesNothing() throws Exception {
        roll("1d6");

        onFx(() -> robot().keyType(KeyCode.ESCAPE));

        assertThat(fromFx(() -> controller().underWay())).isTrue();
        playToRest();
        assertThat(tray.phase()).isEqualTo(StandInTray.Phase.AT_REST);
    }

    @Test
    void aQueuedRollThatFailsShowsTheFailedRollMessage() throws Exception {
        roll("1d6");
        // The first Roll is already simulated; every Attempt of the Queued Roll's Throw times out.
        simulator.then(24, Ending.TIMED_OUT);
        enter("2d6");
        playToRest();

        advance(RollQueue.BEAT_SECONDS);
        onFx(() -> {});

        assertThat(fromFx(() -> controller().status())).isInstanceOf(RollStatus.Failed.class);
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo(WOULDNT_SETTLE);
        // Nothing moved: the Tray keeps the first Roll's Dice, and the box keeps the expression.
        assertThat(tray.shows()).isEqualTo(1);
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("2d6");
        assertThat(fromFx(() -> entry().queue().waiting())).isFalse();
        assertThat(pulses.running).isFalse();
    }

    @Test
    void aCurrentRollThatFailsStartsTheBeatFromItsFailure() throws Exception {
        physics.setHolding(true);
        simulator.then(24, Ending.TIMED_OUT);
        roll("1d6");
        enter("1d4");

        // The first Roll fails in pre-simulation, before anything moved or asked for the pulse.
        onFx(() -> physics.finish());
        onFx(() -> {});
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo(WOULDNT_SETTLE);
        assertThat(pulses.running).isTrue();

        advance(0.85);
        assertThat(fromFx(() -> controller().underWay())).isFalse();
        advance(0.05);
        assertThat(fromFx(() -> controller().underWay())).isTrue();
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("Rolling…");
    }

    @Test
    void aRollFromTheSessionQueuesTheSameWayAsEnter() throws Exception {
        roll("1d6");

        onFx(() -> session.roll("1d12"));

        assertThat(fromFx(() -> entry().queue().queued())).contains("1d12");
        assertThat(fromFx(() -> chip().getText())).isEqualTo("next: 1d12");
        assertThat(tray.shows()).isEqualTo(1);
    }

    @Test
    void theQueuedRollNeverReplacesWhatThePlayerIsTyping() throws Exception {
        roll("1d6");
        enter("1d8");
        onFx(() -> ui().expressionBox().setText("4d6kh"));
        playToRest();
        advance(RollQueue.BEAT_SECONDS);
        onFx(() -> {});

        assertThat(fromFx(() -> controller().underWay())).isTrue();
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("4d6kh");
        assertThat(fromFx(() -> entry().queue().queued())).isEqualTo(Optional.empty());
    }

    private RollEntry entry() {
        return session.entry();
    }

    private javafx.scene.control.Label chip() {
        return entry().nextChip();
    }

    /** Types an expression into the box and presses Enter, as the Player does mid-Roll. */
    private void enter(String expression) throws Exception {
        onFx(() -> {
            ui().expressionBox().setText(expression);
            pressEnterInBox();
        });
    }

    private static double sceneMaxX(Node node) {
        return node.localToScene(node.getLayoutBounds()).getMaxX();
    }
}
