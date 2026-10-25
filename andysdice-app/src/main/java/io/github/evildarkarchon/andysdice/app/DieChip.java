package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.DieResult;
import io.github.evildarkarchon.andysdice.core.expression.GroupKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * One Die of a Dice Group in the Breakdown, as a chip showing the Face Value that counts (spec §3.2 "Breakdown
 * marks"), with its two marks, which never look alike so one Die can carry both:
 * <ul>
 *   <li><b>Rerolled:</b> its earlier Face Values stack behind it as faded chips, peeking out above and to the
 *       right, and a ↻ badge sits beside the stack, with "×N" when it took N rerolls (N &gt; 1).</li>
 *   <li><b>Not kept:</b> the chip itself is faded and a slash is drawn across it.</li>
 * </ul>
 * Hovering the chip shows its Face Value Chain in words ({@link BreakdownText#hoverText}); the same words are its
 * accessible help. Faded chips are drawn in {@link Palette#TEXT_MUTED} on the surface, never by opacity, so the
 * contrast test covers them.
 *
 * <p>Thread contract: a JavaFX node, used on the JavaFX Application Thread.
 */
final class DieChip extends HBox {

    /** A chip's height and least width, logical px. */
    static final double SIZE = 30;
    /** How far each earlier chip peeks out from behind the one in front of it, logical px. */
    static final double STACK_STEP = 5;
    /**
     * The most earlier chips drawn behind the final one. An {@code rr} can reroll many times; past this the stack
     * would outgrow the strip, and the badge's count and the hover text still say how many there were.
     */
    static final int MAX_STACKED = 3;

    private final Label face;
    private final List<Label> earlier = new ArrayList<>();
    private final HBox rerollBadge;
    private final Label rerollCount;
    private final SVGPath slash;
    private final String hoverText;
    private final Tooltip tooltip;

    /**
     * Makes the chip for one Die.
     *
     * @param kind the Die Kind of its Dice Group
     * @param die its result: the Face Value Chain and whether it was kept
     */
    DieChip(GroupKind kind, DieResult die) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(die, "die");
        getStyleClass().add("die-chip-box");
        setAlignment(Pos.TOP_LEFT);
        setSpacing(1);

        List<Integer> values = die.chain().values();
        int rerolls = values.size() - 1;
        int stacked = Math.min(rerolls, MAX_STACKED);
        StackPane stack = new StackPane();
        // The final chip sits at the bottom left; each earlier one is shifted up and right by a step more, its
        // margin making the room it peeks into.
        stack.setAlignment(Pos.BOTTOM_LEFT);
        for (int i = 0; i < stacked; i++) {
            // The latest earlier values, the oldest of them at the back.
            Label old = chip(values.get(rerolls - stacked + i));
            old.getStyleClass().add("faded");
            double offset = (stacked - i) * STACK_STEP;
            StackPane.setMargin(old, new Insets(0, 0, offset, offset));
            earlier.add(old);
        }
        stack.getChildren().addAll(earlier);

        face = chip(die.value());
        // The front chip and its slash share a pane of the chip's own size, so the slash is centred on the chip.
        StackPane front = new StackPane(face);
        front.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        if (die.kept()) {
            slash = null;
        } else {
            face.getStyleClass().add("faded");
            slash = Icons.notKept();
            slash.getStyleClass().add("not-kept-slash");
            // The path spans 20 of its 24-unit grid; layout uses its unscaled 20 px, so it never widens the
            // chip, and the scale (about its centre) stretches it to just inside the chip.
            slash.setScaleX(SIZE * 0.85 / 20);
            slash.setScaleY(SIZE * 0.85 / 20);
            slash.setMouseTransparent(true);
            front.getChildren().add(slash);
        }
        stack.getChildren().add(front);
        getChildren().add(stack);

        if (rerolls > 0) {
            SVGPath arrow = Icons.reroll();
            arrow.setScaleX(0.5);
            arrow.setScaleY(0.5);
            // A Group's bounds include its child's scale, so the badge takes the arrow's drawn size.
            rerollCount = new Label(rerolls > 1 ? "×" + rerolls : "");
            rerollCount.getStyleClass().add("reroll-count");
            rerollCount.setVisible(rerolls > 1);
            rerollCount.setManaged(rerolls > 1);
            rerollBadge = new HBox(1, new Group(arrow), rerollCount);
            rerollBadge.getStyleClass().add("reroll-badge");
            rerollBadge.setAlignment(Pos.CENTER_LEFT);
            getChildren().add(rerollBadge);
        } else {
            rerollBadge = null;
            rerollCount = null;
        }

        hoverText = BreakdownText.hoverText(kind, die);
        tooltip = new Tooltip(hoverText);
        Tooltip.install(this, tooltip);
        setAccessibleHelp(hoverText);
    }

    /** A chip showing one Face Value. */
    private static Label chip(int value) {
        Label chip = new Label(Integer.toString(value));
        chip.getStyleClass().add("die-chip");
        chip.setMinSize(SIZE, SIZE);
        chip.setPrefHeight(SIZE);
        chip.setMaxHeight(SIZE);
        chip.setAlignment(Pos.CENTER);
        return chip;
    }

    /**
     * Returns the chip in front: the Face Value that counts.
     *
     * @return its label, faded when the Die isn't kept
     */
    Label face() {
        return face;
    }

    /**
     * Returns the faded chips stacked behind the front one, oldest first: the Face Values a reroll replaced, at
     * most {@link #MAX_STACKED} of the latest.
     *
     * @return the earlier chips; empty when the Die wasn't rerolled
     */
    List<Label> earlier() {
        return List.copyOf(earlier);
    }

    /**
     * Returns the ↻ badge of a rerolled Die.
     *
     * @return the badge (the code-drawn arrow and, for several rerolls, its count), or empty if it wasn't rerolled
     */
    Optional<HBox> rerollBadge() {
        return Optional.ofNullable(rerollBadge);
    }

    /**
     * Returns the badge's count, such as "×3".
     *
     * @return the count label, empty (and hidden) for a single reroll; empty if the Die wasn't rerolled
     */
    Optional<Label> rerollCount() {
        return Optional.ofNullable(rerollCount);
    }

    /**
     * Returns the slash across a Die that isn't kept.
     *
     * @return the code-drawn slash, or empty for a kept Die
     */
    Optional<SVGPath> slash() {
        return Optional.ofNullable(slash);
    }

    /**
     * Returns the hover text spelling out the Die's Face Value Chain.
     *
     * @return the text, as in "d6: showed [1, 1, 6], the last counts · not kept"
     */
    String hoverText() {
        return hoverText;
    }

    /**
     * Returns the tooltip installed on the chip, which shows the hover text.
     *
     * @return the tooltip
     */
    Tooltip tooltip() {
        return tooltip;
    }
}
