package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The three Light Rigs as spec Appendix C and ADR-0010 describe them: Studio (the default) a directional key from
 * two softboxes in a neutral room, Lamp one warm spot overhead in a dark room, Window low, cool daylight from the
 * side with a blue sky fill. Pure Java, no GL.
 */
class LightRigTest {

    @Test
    void studioIsTheDefault() {
        assertThat(LightRig.DEFAULT).isEqualTo(LightRig.STUDIO);
    }

    @Test
    void threeRigsShip() {
        assertThat(LightRig.values()).containsExactly(LightRig.STUDIO, LightRig.LAMP, LightRig.WINDOW);
    }

    @Test
    void studioAndWindowHaveADirectionalKeyAndLampASpot() {
        assertThat(LightRig.STUDIO.spot()).isFalse();
        assertThat(LightRig.WINDOW.spot()).isFalse();
        assertThat(LightRig.LAMP.spot()).isTrue();
    }

    @Test
    void theLampHangsOverheadAndAimsAtTheTraysMiddle() {
        Vector3dc lamp = LightRig.LAMP.keyPosition();
        Vector3dc aim = LightRig.LAMP.spotAim();
        assertThat(lamp.y()).as("well above the 30 mm walls").isGreaterThan(300);
        assertThat(aim.y()).as("pointing down").isLessThan(-0.9);
        // Where the spot's axis meets the floor (y = 0).
        Vector3d floor = new Vector3d(aim).mul(-lamp.y() / aim.y()).add(lamp);
        assertThat(Math.hypot(floor.x(), floor.z())).as("within a few cm of the Tray's middle").isLessThan(30);
        assertThat(LightRig.LAMP.spotInnerDegrees()).isPositive()
                .isLessThan(LightRig.LAMP.spotOuterDegrees());
    }

    @Test
    void theWindowsKeyIsLowAndFromTheSide() {
        Vector3dc key = LightRig.WINDOW.keyPosition();
        double elevation = Math.toDegrees(Math.asin(key.y() / key.length()));
        assertThat(elevation).isBetween(10.0, 35.0);
        // The Studio key, by contrast, is high: softboxes over the Tray.
        Vector3dc studio = LightRig.STUDIO.keyPosition();
        assertThat(Math.toDegrees(Math.asin(studio.y() / studio.length()))).isGreaterThan(45);
    }

    @Test
    void theLampIsWarmTheWindowCoolAndItsFillSkyBlue() {
        assertThat(LightRig.LAMP.keyColour().x()).isGreaterThan(LightRig.LAMP.keyColour().z() * 1.5);
        assertThat(LightRig.WINDOW.keyColour().z()).isGreaterThan(LightRig.WINDOW.keyColour().x());
        Vector3dc sky = LightRig.WINDOW.fillColour();
        assertThat(sky.z()).isGreaterThan(sky.y()).isGreaterThan(sky.x());
        assertThat(LightRig.WINDOW.fillDirection().y()).as("the sky is overhead").isGreaterThan(0.9);
    }

    @Test
    void theLampsRoomIsDark() {
        // One spot in a dark room: next to no fill, unlike the other two rooms.
        assertThat(luminance(LightRig.LAMP.fillColour())).isLessThan(0.05 * luminance(LightRig.LAMP.keyColour()));
        assertThat(luminance(LightRig.STUDIO.fillColour())).isGreaterThan(0.1 * luminance(LightRig.STUDIO.keyColour()));
    }

    @ParameterizedTest
    @EnumSource(LightRig.class)
    void everyRigHasALightOfSomeSizeForSoftShadows(LightRig rig) {
        assertThat(rig.lightSize()).isPositive();
        assertThat(rig.fillDirection().length()).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-9));
        if (!rig.spot()) {
            assertThat(rig.keyPosition().length()).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void everyRigHasAReflectionRoomOfItsOwn() {
        assertThat(java.util.Arrays.stream(LightRig.values()).mapToInt(LightRig::room).distinct().count())
                .isEqualTo(LightRig.values().length);
    }

    private static double luminance(Vector3dc c) {
        return 0.2126 * c.x() + 0.7152 * c.y() + 0.0722 * c.z();
    }
}
