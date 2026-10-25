package io.github.evildarkarchon.andysdice.render;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.playback.Interpolation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;

/**
 * One Throw as playback draws it: its Recording, each Die's Die Kind and each Die's Numbering Rotation, read at
 * any playback time into the {@link DiceScene.Die}s to draw.
 *
 * <p>Each read interpolates the Recording at that time (linear positions, slerped orientations; ADR-0014), so
 * playback is smooth at any frame rate and at ×4. Each Die is drawn at {@code pose · turn}: its Numbering
 * Rotation acts first, in the model frame, so the shape is unchanged and the chosen face is the one up at rest
 * (ADR-0001). Before the Release a Die is at its Release pose; after the Recording ends it stays where it
 * stopped.
 *
 * <p>Immutable and safe on any thread.
 */
public final class ThrowPlayback {

    private final Recording recording;
    private final List<DieKind> kinds;
    private final List<Quaterniondc> turns;

    /**
     * Creates the playback of one Throw.
     *
     * @param recording the Throw's Recording, in cm
     * @param kinds each recorded Die's Die Kind, in the Recording's order
     * @param turns each recorded Die's Numbering Rotation, in the Recording's order; identity for none
     * @throws IllegalArgumentException if there isn't one Die Kind and one turn per recorded Die
     */
    public ThrowPlayback(Recording recording, List<DieKind> kinds, List<? extends Quaterniondc> turns) {
        this.recording = Objects.requireNonNull(recording, "recording");
        this.kinds = List.copyOf(kinds);
        List<Quaterniondc> copied = new ArrayList<>(turns.size());
        for (Quaterniondc turn : turns) {
            copied.add(new Quaterniond(turn));
        }
        this.turns = List.copyOf(copied);
        if (this.kinds.size() != recording.dice() || this.turns.size() != recording.dice()) {
            throw new IllegalArgumentException(this.kinds.size() + " Die Kinds and " + this.turns.size()
                    + " turns for a Recording of " + recording.dice() + " Dice");
        }
    }

    /**
     * Returns the Throw's Recording.
     *
     * @return the Recording
     */
    public Recording recording() {
        return recording;
    }

    /**
     * Returns every Die as it is drawn at a moment of the Throw.
     *
     * @param time playback seconds since the Release
     * @return one Die per recorded Die, in the Recording's order, in the renderer's mm
     */
    public List<DiceScene.Die> diceAt(double time) {
        List<DiceScene.Die> dice = new ArrayList<>(recording.dice());
        for (int i = 0; i < recording.dice(); i++) {
            Pose pose = Interpolation.pose(recording, i, time);
            DiceScene.Die atPose = DiceScene.Die.of(kinds.get(i), pose);
            Quaterniond turned = new Quaterniond(atPose.orientation()).mul(turns.get(i));
            dice.add(new DiceScene.Die(atPose.kind(), atPose.position(), turned));
        }
        return dice;
    }
}
