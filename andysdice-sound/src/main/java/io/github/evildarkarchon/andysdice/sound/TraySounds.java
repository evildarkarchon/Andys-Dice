package io.github.evildarkarchon.andysdice.sound;

import java.util.Objects;

/**
 * Which categories a Tray's floor and walls sound like: its floor kind and wall material (ADR-0010,
 * ADR-0012 "Categories"). The app maps a Tray file's keys to these.
 *
 * @param floor the floor's category
 * @param wall the walls' category
 */
public record TraySounds(SoundCategory floor, SoundCategory wall) {

    /**
     * Checks the floor is a floor and the walls are walls.
     *
     * @throws IllegalArgumentException if either names the wrong kind of category
     */
    public TraySounds {
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(wall, "wall");
        if (!floor.isFloor() || !wall.isWall()) {
            throw new IllegalArgumentException("A Tray sounds like a floor kind and a wall material, not "
                    + floor + " and " + wall);
        }
    }
}
