package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.tray.Tray;
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

    /**
     * Returns what a Tray sounds like: its {@code floor.surface} and {@code walls.material} keys.
     *
     * @param tray the Tray
     * @return its floor's and walls' categories
     */
    public static TraySounds of(Tray tray) {
        SoundCategory floor = switch (tray.floor().surface()) {
            case FELT -> SoundCategory.FELT_FLOOR;
            case LEATHER -> SoundCategory.LEATHER_FLOOR;
            case WOOD -> SoundCategory.WOOD_FLOOR;
        };
        SoundCategory wall = switch (tray.walls().material()) {
            case WOOD -> SoundCategory.WOOD_WALL;
            case LEATHER -> SoundCategory.LEATHER_WALL;
        };
        return new TraySounds(floor, wall);
    }
}
