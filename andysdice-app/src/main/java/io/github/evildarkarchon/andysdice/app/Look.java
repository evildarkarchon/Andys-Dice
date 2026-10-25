package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.render.LightRig;
import java.util.Objects;

/**
 * What the Tray looks like, as the Look tab chose it (spec §3.2 "Look tab"): the Dice Set every Die is drawn in, the
 * Tray the Dice are thrown into and drawn in, and the Light Rig over both, each chosen independently (ADR-0009,
 * ADR-0010).
 *
 * @param diceSet the Dice Set
 * @param tray the Tray
 * @param lightRig the Light Rig
 */
record Look(DiceSet diceSet, Tray tray, LightRig lightRig) {

    /** Checks all three are present. */
    Look {
        Objects.requireNonNull(diceSet, "diceSet");
        Objects.requireNonNull(tray, "tray");
        Objects.requireNonNull(lightRig, "lightRig");
    }
}
