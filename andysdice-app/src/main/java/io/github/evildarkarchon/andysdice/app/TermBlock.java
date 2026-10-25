package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.DiceGroup;
import io.github.evildarkarchon.andysdice.core.expression.DieResult;
import io.github.evildarkarchon.andysdice.core.expression.Modifier;
import io.github.evildarkarchon.andysdice.core.expression.TermResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * One term of the Breakdown in the result strip (spec §3.2 "Layout"): the term exactly as written
 * ({@code 4d6rr1kh3}, {@code 1d20 adv}), and for a Dice Group its subtotal and a {@link DieChip} per Die in the
 * group's order. A Modifier is just its signed number ({@code +2}).
 *
 * <p>Thread contract: a JavaFX node, used on the JavaFX Application Thread.
 */
final class TermBlock extends VBox {

    private final Label heading;
    private final Label subtotal;
    private final List<DieChip> chips = new ArrayList<>();

    /**
     * Makes the block for one term.
     *
     * @param term the term's result
     * @param first whether it is the expression's first term, whose plus isn't written
     */
    TermBlock(TermResult term, boolean first) {
        Objects.requireNonNull(term, "term");
        getStyleClass().add("term-block");
        setSpacing(4);
        setAlignment(Pos.TOP_LEFT);
        heading = new Label(BreakdownText.asWritten(term.term(), first));
        heading.getStyleClass().add("term-heading");
        switch (term.term()) {
            case DiceGroup group -> {
                subtotal = new Label("= " + term.subtotal());
                subtotal.getStyleClass().add("term-subtotal");
                HBox row = new HBox(6);
                row.setAlignment(Pos.BOTTOM_LEFT);
                for (DieResult die : term.dice()) {
                    DieChip chip = new DieChip(group.kind(), die);
                    chips.add(chip);
                    row.getChildren().add(chip);
                }
                HBox head = new HBox(8, heading, subtotal);
                head.setAlignment(Pos.BASELINE_LEFT);
                getChildren().addAll(head, row);
            }
            case Modifier modifier -> {
                // A Modifier's number is its own subtotal, so it has no second one.
                subtotal = new Label("");
                heading.getStyleClass().add("term-modifier");
                getChildren().add(heading);
            }
        }
    }

    /**
     * Returns the term as written.
     *
     * @return the heading, such as "4d6rr1kh3", "+1d20 adv" or "-2"
     */
    Label heading() {
        return heading;
    }

    /**
     * Returns a Dice Group's subtotal, the sum of its kept Dice before its sign.
     *
     * @return the label, such as "= 15"; empty text, and not shown, for a Modifier
     */
    Label subtotal() {
        return subtotal;
    }

    /**
     * Returns a Dice Group's chips.
     *
     * @return one per Die, in the group's order; empty for a Modifier
     */
    List<DieChip> chips() {
        return List.copyOf(chips);
    }
}
