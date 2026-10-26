package io.github.evildarkarchon.andysdice.render;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.util.Objects;

/**
 * What the Dice and the Tray are drawn as (spec §3.2 "Look tab"): the Dice Set every Die is drawn in, the Tray the
 * Dice are thrown into and drawn in, and the Light Rig over both, each chosen independently (ADR-0009, ADR-0010).
 * The {@link DiceScene} takes one whole, so a frame never draws one part of a Look with another's (#80).
 *
 * @param diceSet the Dice Set
 * @param tray the Tray
 * @param lightRig the Light Rig
 */
public record Look(DiceSet diceSet, Tray tray, LightRig lightRig) {

    /**
     * Checks all three are present.
     *
     * @throws NullPointerException if any part is {@code null}
     */
    public Look {
        Objects.requireNonNull(diceSet, "diceSet");
        Objects.requireNonNull(tray, "tray");
        Objects.requireNonNull(lightRig, "lightRig");
    }
}
