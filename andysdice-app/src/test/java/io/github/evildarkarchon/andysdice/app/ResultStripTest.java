package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The result strip and its Breakdown (spec §3.2 "Layout", "Breakdown marks", "Failed Roll", "Cocked Rethrow",
 * "Screen readers"), on JavaFX's headless platform with the real pipeline behind it (ADR-0011): "Rolling…" while
 * Dice move, then the large total, a block per term as written with its Dice as chips, the marks for rerolled and
 * unkept Dice, the hover text, the facts on the right (expression, Saved Roll name, time, Throws), the spoken summary,
 * and the Failed Roll messages.
 */
class ResultStripTest extends HeadlessUiTest {

    private static final String WOULDNT_SETTLE =
            "The Dice wouldn't settle. Nothing was rolled. Press Enter to try again.";
    private static final String TOO_MANY_REROLLS = "Too many rerolls. Nothing was rolled.";

    @BeforeEach
    void fixTheTime() throws Exception {
        // 14:05 in the zone the strip reads its clock in.
        onFx(() -> ui().resultStrip().setClock(Clock.fixed(Instant.parse("2026-10-25T14:05:30Z"), ZoneOffset.UTC)));
    }

    @Test
    void theRevealShowsTheTotalLargeAndOneBlockPerTermAsWritten() throws Exception {
        // The first Throw draws every Die, the 4d6 then the d20 pair of the adv (15 and 8); the Reroll Throw then
        // draws the 1's next value, a 3.
        faces.then(1, 4, 5, 6, 15, 8, 3);

        roll("4d6rr1kh3+1d20-2 adv");
        playToRest();

        ResultStrip strip = strip();
        // 4 + 5 + 6 kept, 15 the higher d20, minus 2.
        assertThat(fromFx(() -> strip.total().getText())).isEqualTo("28");
        assertThat(fromFx(() -> strip.total().isVisible())).isTrue();
        assertThat(fromFx(() -> strip.message().isVisible())).isFalse();
        List<TermBlock> blocks = fromFx(strip::terms);
        assertThat(blocks).extracting(block -> block.heading().getText())
                .containsExactly("4d6rr1kh3", "+1d20 adv", "-2");
        assertThat(blocks).extracting(block -> block.subtotal().getText()).containsExactly("= 15", "= 15", "");
        assertThat(blocks.get(0).chips()).extracting(chip -> chip.face().getText())
                .containsExactly("3", "4", "5", "6");
        assertThat(blocks.get(1).chips()).extracting(chip -> chip.face().getText()).containsExactly("15", "8");
        // A Modifier is a signed number with no chips.
        assertThat(blocks.get(2).chips()).isEmpty();
        assertThat(fromFx(() -> strip.expression().getText())).isEqualTo("4d6rr1kh3+1d20-2 adv");
        assertThat(fromFx(() -> strip.details().getText())).isEqualTo("14:05 · 2 Throws");
    }

    @Test
    void aSubtractedGroupAndAPlusModifierAreSigned() throws Exception {
        faces.then(2, 6);

        roll("+1d6 - 1d6 + 05");
        playToRest();

        assertThat(fromFx(() -> strip().terms())).extracting(block -> block.heading().getText())
                .containsExactly("1d6", "-1d6", "+5");
        assertThat(fromFx(() -> strip().total().getText())).isEqualTo("1");
    }

    @Test
    void rollingShowsWhileDiceMoveAndTheTotalAppearsOnlyAtTheReveal() throws Exception {
        physics.setHolding(true);
        faces.then(4);

        roll("1d6+2");
        // Pre-simulating: nothing on the Tray yet, and no total.
        assertRolling();
        onFx(() -> physics.finish());
        onFx(() -> {});
        advance(0.3);
        // Playing back: still no total.
        assertRolling();

        playToRest();
        assertThat(fromFx(() -> strip().total().isVisible())).isTrue();
        assertThat(fromFx(() -> strip().total().getText())).isEqualTo("6");
        assertThat(fromFx(() -> strip().text())).isEqualTo("1d6+2 = 6");

        // The next Roll hides the last one's Breakdown until it too is at rest.
        faces.then(1);
        roll("1d6");
        assertRolling();
    }

    private void assertRolling() throws Exception {
        assertThat(fromFx(() -> strip().text())).isEqualTo("Rolling…");
        assertThat(fromFx(() -> strip().message().getText())).isEqualTo("Rolling…");
        assertThat(fromFx(() -> strip().message().isVisible())).isTrue();
        assertThat(fromFx(() -> strip().total().isVisible())).isFalse();
        assertThat(fromFx(() -> strip().terms())).isEmpty();
        assertThat(fromFx(() -> strip().details().isVisible())).isFalse();
    }

    @Test
    void aRerolledDieStacksItsEarlierValuesFadedBehindItWithABadgeAndAnUnkeptDieIsFadedAndSlashed()
            throws Exception {
        // 4d6rr1kh3. First Throw: 1 1 5 6. Round 1 rerolls both 1s: 6 and 1. Round 2 rerolls the second Die: 3.
        // So [1, 6] kept, [1, 1, 3] rerolled twice and not kept (the lowest), 5 and 6 kept and never rerolled.
        faces.then(1, 1, 5, 6, 6, 1, 3);

        roll("4d6rr1kh3");
        playToRest();

        List<DieChip> chips = fromFx(() -> strip().terms().getFirst().chips());
        assertThat(chips).extracting(chip -> chip.face().getText()).containsExactly("6", "3", "5", "6");

        // Rerolled once and kept: one faded chip behind, the badge without a count, no slash, not faded.
        DieChip once = chips.get(0);
        assertThat(fromFx(() -> once.earlier())).extracting(chip -> chip.getText()).containsExactly("1");
        assertThat(fromFx(() -> fill(once.earlier().getFirst()))).isEqualTo(Palette.TEXT_MUTED);
        assertThat(once.rerollBadge()).isPresent();
        assertThat(paths(once.rerollBadge().get())).containsExactly(Icons.REROLL);
        assertThat(fromFx(() -> once.rerollCount().get().isVisible())).isFalse();
        assertThat(once.slash()).isEmpty();
        assertThat(fromFx(() -> fill(once.face()))).isEqualTo(Palette.TEXT);

        // Rerolled twice and not kept: both marks at once, and they differ (a badge beside, a slash across).
        DieChip both = chips.get(1);
        assertThat(fromFx(() -> both.earlier())).extracting(chip -> chip.getText()).containsExactly("1", "1");
        assertThat(fromFx(() -> both.rerollCount().get().getText())).isEqualTo("×2");
        assertThat(fromFx(() -> both.rerollCount().get().isVisible())).isTrue();
        assertThat(fromFx(() -> fill(both.rerollCount().get()))).isEqualTo(Palette.TEXT);
        assertThat(both.slash()).map(SVGPath::getContent).contains(Icons.NOT_KEPT);
        assertThat(fromFx(() -> fill(both.face()))).isEqualTo(Palette.TEXT_MUTED);
        assertThat(Icons.NOT_KEPT).isNotEqualTo(Icons.REROLL);

        // Never rerolled, kept: a plain chip.
        DieChip plain = chips.get(2);
        assertThat(plain.earlier()).isEmpty();
        assertThat(plain.rerollBadge()).isEmpty();
        assertThat(plain.slash()).isEmpty();
        assertThat(fromFx(() -> fill(plain.face()))).isEqualTo(Palette.TEXT);
    }

    @Test
    void anUnkeptDieThatWasNeverRerolledIsFadedAndSlashedWithNoBadge() throws Exception {
        faces.then(15, 8);

        roll("1d20 adv");
        playToRest();

        DieChip lower = fromFx(() -> strip().terms().getFirst().chips().get(1));
        assertThat(lower.slash()).isPresent();
        assertThat(lower.rerollBadge()).isEmpty();
        assertThat(lower.earlier()).isEmpty();
        assertThat(fromFx(() -> fill(lower.face()))).isEqualTo(Palette.TEXT_MUTED);
    }

    @Test
    void hoveringAChipSpellsOutItsFaceValueChain() throws Exception {
        // The first Throw draws the 4d6 and both d20s; the reroll rounds draw after it, as in the test above.
        faces.then(1, 1, 5, 6, 15, 8, 6, 1, 3);

        roll("4d6rr1kh3+1d20 adv");
        playToRest();

        List<TermBlock> blocks = fromFx(() -> strip().terms());
        assertThat(blocks.get(0).chips()).extracting(DieChip::hoverText).containsExactly(
                "d6: showed [1, 6], the last counts",
                "d6: showed [1, 1, 3], the last counts · not kept",
                "d6: showed 5",
                "d6: showed 6");
        assertThat(blocks.get(1).chips()).extracting(DieChip::hoverText)
                .containsExactly("d20: showed 15", "d20: showed 8 · not kept");
        // The hover text is what the chip's tooltip shows.
        DieChip chip = blocks.get(0).chips().get(1);
        assertThat(fromFx(() -> chip.tooltip().getText())).isEqualTo("d6: showed [1, 1, 3], the last counts · not kept");
        assertThat(fromFx(() -> chip.getAccessibleHelp())).isEqualTo(chip.hoverText());
    }

    @Test
    void aPercentileRollsChipShowsItsOneToHundredValue() throws Exception {
        faces.then(100);

        roll("d%");
        playToRest();

        DieChip chip = fromFx(() -> strip().terms().getFirst().chips().getFirst());
        assertThat(fromFx(() -> chip.face().getText())).isEqualTo("100");
        assertThat(chip.hoverText()).isEqualTo("d%: showed 100");
    }

    @Test
    void theThrowsCountIncludesCockedRethrowsWhichAddNothingElseToTheBreakdown() throws Exception {
        // Every Attempt of the first Throw leaves Die 0 cocked, so the best one is kept and that Die is thrown again.
        simulator.then(24, Ending.COCKED);
        faces.then(4, 2);

        roll("2d6");
        assertThat(tray.showing().plan().cockedRethrows()).isEqualTo(1);
        playToRest();

        assertThat(fromFx(() -> strip().details().getText())).isEqualTo("14:05 · 2 Throws");
        List<DieChip> chips = fromFx(() -> strip().terms().getFirst().chips());
        assertThat(chips).extracting(chip -> chip.face().getText()).containsExactly("4", "2");
        for (DieChip chip : chips) {
            assertThat(chip.earlier()).isEmpty();
            assertThat(chip.rerollBadge()).isEmpty();
            assertThat(chip.slash()).isEmpty();
        }
        assertThat(chips).extracting(DieChip::hoverText).containsExactly("d6: showed 4", "d6: showed 2");
        assertThat(fromFx(() -> strip().getAccessibleText())).isEqualTo("Total 6. 2d6: 4, 2.");

        // The same Roll without a Cocked Rethrow is one Throw.
        faces.then(4, 2);
        roll("2d6");
        playToRest();
        assertThat(fromFx(() -> strip().details().getText())).isEqualTo("14:05 · 1 Throw");
    }

    @Test
    void theFactsNameTheSavedRollARollCameFrom() throws Exception {
        faces.then(3);
        roll("1d8+1");
        playToRest();
        RollStatus.Rested rested = (RollStatus.Rested) fromFx(() -> rollState().status());

        onFx(() -> ui().showStatus(new RollStatus.Rested(rested.expression(), rested.result(), rested.throwCount(),
                Optional.of("Fireball"))));

        assertThat(fromFx(() -> strip().details().getText())).isEqualTo("Fireball · 14:05 · 1 Throw");
        assertThat(fromFx(() -> strip().expression().getText())).isEqualTo("1d8+1");
    }

    @Test
    void theStripIsFocusableAndAfterARevealItsAccessibleTextIsTheSpokenSummary() throws Exception {
        faces.then(6, 5, 4, 2);

        roll("4d6rr1kh3+2");
        assertThat(fromFx(() -> strip().getAccessibleText())).isEqualTo("Rolling…");
        playToRest();

        assertThat(fromFx(() -> strip().getAccessibleText()))
                .isEqualTo("Total 17. 4d6 reroll 1s keep highest 3: 6, 5, 4, 2 not kept. Plus 2.");
        onFx(() -> strip().requestFocus());
        assertThat(fromFx(() -> strip().isFocused())).isTrue();
        // Focusable on request (Ctrl+R, a click), but not a Tab stop in the spec's focus order.
        assertThat(fromFx(() -> strip().isFocusTraversable())).isFalse();
    }

    @Test
    void theSpokenSummarySaysHowManyRerollsADieTook() throws Exception {
        faces.then(1, 1, 5, 6, 6, 1, 3);

        roll("4d6rr1kh3");
        playToRest();

        assertThat(fromFx(() -> strip().getAccessibleText())).isEqualTo(
                "Total 17. 4d6 reroll 1s keep highest 3: 6 after a reroll, 3 after 2 rerolls and not kept, 5, 6.");
    }

    @Test
    void aRollWhoseDiceWouldntSettleShowsItsMessageWhereTheTotalGoesAndIsLogged() throws Exception {
        faces.then(5);
        roll("1d6");
        playToRest();
        ChainPlayback previous = tray.showing();

        // Every Attempt of the next Roll's first Throw times out: nothing comes to rest.
        simulator.then(24, Ending.TIMED_OUT);
        List<LogRecord> logged = capturingTheControllersLog(() -> {
            roll("2d6");
            advance(0.5);
        });

        assertFailedWith(WOULDNT_SETTLE, "2d6");
        // The box keeps the expression, and the Tray the previous Roll's Dice.
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("2d6");
        assertThat(tray.showing()).isSameAs(previous);
        assertThat(tray.shows()).isEqualTo(1);
        assertThat(logged).extracting(LogRecord::getMessage).anySatisfy(message -> assertThat(message)
                .contains("2d6").contains(WOULDNT_SETTLE));
    }

    @Test
    void aRollPastTheRerollBackstopSaysTooManyRerollsAndIsLogged() throws Exception {
        faces.thenRepeat(101, 1);

        List<LogRecord> logged = capturingTheControllersLog(() -> roll("1d6rr1"));

        assertFailedWith(TOO_MANY_REROLLS, "1d6rr1");
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("1d6rr1");
        assertThat(tray.shows()).isZero();
        assertThat(logged).extracting(LogRecord::getMessage).anySatisfy(message -> assertThat(message)
                .contains(TOO_MANY_REROLLS));
    }

    private void assertFailedWith(String message, String expression) throws Exception {
        assertThat(fromFx(() -> strip().text())).isEqualTo(message);
        assertThat(fromFx(() -> strip().message().getText())).isEqualTo(message);
        assertThat(fromFx(() -> strip().message().isVisible())).isTrue();
        assertThat(fromFx(() -> strip().total().isVisible())).isFalse();
        assertThat(fromFx(() -> strip().terms())).isEmpty();
        assertThat(fromFx(() -> strip().expression().getText())).isEqualTo(expression);
        assertThat(fromFx(() -> strip().getAccessibleText())).isEqualTo(message);
    }

    /** Runs a step with a handler on the controller's logger (System.Logger goes to java.util.logging here). */
    private static List<LogRecord> capturingTheControllersLog(FxAction step) throws Exception {
        Logger logger = Logger.getLogger(RollController.class.getName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // Nothing is buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        logger.addHandler(handler);
        try {
            step.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    private ResultStrip strip() {
        return ui().resultStrip();
    }

    /** A label's text colour once the stylesheet has been applied. */
    private static Paint fill(javafx.scene.control.Label label) {
        label.applyCss();
        return label.getTextFill();
    }

    /** The path data of every SVG path under a node. */
    private static List<String> paths(Node node) throws Exception {
        return fromFx(() -> {
            List<String> found = new java.util.ArrayList<>();
            collectPaths(node, found);
            return found;
        });
    }

    private static void collectPaths(Node node, List<String> into) {
        if (node instanceof SVGPath path) {
            into.add(path.getContent());
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectPaths(child, into));
        }
    }
}
