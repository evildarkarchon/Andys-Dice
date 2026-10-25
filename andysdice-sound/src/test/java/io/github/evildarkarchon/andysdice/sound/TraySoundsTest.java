package io.github.evildarkarchon.andysdice.sound;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import org.junit.jupiter.api.Test;

/** A Tray sounds like its floor kind and its wall material (ADR-0010, ADR-0012 "Categories"). */
class TraySoundsTest {

    @Test
    void eachBuiltInTraySoundsLikeItsFloorAndWalls() {
        // Spec Appendix C: green felt in walnut, brown leather in a padded hexagon, bare walnut.
        assertThat(TraySounds.of(BuiltInTrays.find("01-green-felt").orElseThrow()))
                .isEqualTo(new TraySounds(SoundCategory.FELT_FLOOR, SoundCategory.WOOD_WALL));
        assertThat(TraySounds.of(BuiltInTrays.find("02-leather-hexagon").orElseThrow()))
                .isEqualTo(new TraySounds(SoundCategory.LEATHER_FLOOR, SoundCategory.LEATHER_WALL));
        assertThat(TraySounds.of(BuiltInTrays.find("03-walnut-board").orElseThrow()))
                .isEqualTo(new TraySounds(SoundCategory.WOOD_FLOOR, SoundCategory.WOOD_WALL));
    }
}
