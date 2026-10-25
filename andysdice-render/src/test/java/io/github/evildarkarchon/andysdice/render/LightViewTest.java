package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import io.github.evildarkarchon.andysdice.render.camera.CameraPose;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The key light's view for its shadow map (ADR-0010): it must hold the whole Tray, walls included, so every Die and
 * wall can cast, and look along the key light. Pure Java, no GL.
 */
class LightViewTest {

    /** The whole-Tray view: the prototype's default 50° pitch, far enough back to hold the Tray. */
    private static final DiceScene.Camera WHOLE_TRAY = new CameraPose(new Vector3d(0, 5, 0), 0, 50, 800).camera();

    /** A close-up on Dice at rest off the Tray's middle, as the settle-in camera eases in. */
    private static final DiceScene.Camera CLOSE_UP = new CameraPose(new Vector3d(60, 0, 30), 0, 62, 150).camera();

    @ParameterizedTest
    @EnumSource(LightRig.class)
    void theWholeTrayAndItsWallTopsLieInTheShadowMap(LightRig rig) {
        LightView view = LightView.of(rig, WHOLE_TRAY);

        for (Vector3dc corner : trayCorners()) {
            Vector3d clip = project(view, corner);
            assertThat(clip.x()).as("%s x of %s", rig, corner).isBetween(-1.0, 1.0);
            assertThat(clip.y()).as("%s y of %s", rig, corner).isBetween(-1.0, 1.0);
            assertThat(clip.z()).as("%s depth of %s", rig, corner).isBetween(-1.0, 1.0);
        }
    }

    @ParameterizedTest
    @EnumSource(value = LightRig.class, names = {"STUDIO", "WINDOW"})
    void aDirectionalKeysMapLooksAlongTheLight(LightRig rig) {
        LightView view = LightView.of(rig, WHOLE_TRAY);
        Vector3d floor = new Vector3d(40, 0, -20);
        Vector3d above = new Vector3d(rig.keyPosition()).mul(60).add(floor);

        Vector3d a = project(view, floor);
        Vector3d b = project(view, above);
        assertThat(view.perspective()).isFalse();
        assertThat(b.x()).isCloseTo(a.x(), offset(1e-9));
        assertThat(b.y()).isCloseTo(a.y(), offset(1e-9));
        assertThat(b.z()).as("nearer the light").isLessThan(a.z());
    }

    @Test
    void theLampsMapIsCentredWhereTheSpotPoints() {
        LightView view = LightView.of(LightRig.LAMP, WHOLE_TRAY);
        Vector3dc lamp = LightRig.LAMP.keyPosition();
        Vector3dc aim = LightRig.LAMP.spotAim();
        Vector3d onFloor = new Vector3d(aim).mul(-lamp.y() / aim.y()).add(lamp);

        Vector3d clip = project(view, onFloor);
        assertThat(view.perspective()).isTrue();
        assertThat(clip.x()).isCloseTo(0, offset(1e-6));
        assertThat(clip.y()).isCloseTo(0, offset(1e-6));
    }

    @ParameterizedTest
    @EnumSource(value = LightRig.class, names = {"STUDIO", "WINDOW"})
    void aCloseUpGetsFinerShadowTexelsAndStillHoldsWhatItSees(LightRig rig) {
        LightView whole = LightView.of(rig, WHOLE_TRAY);
        LightView close = LightView.of(rig, CLOSE_UP);

        assertThat(close.uvPerMm()).isGreaterThan(whole.uvPerMm() * 2);
        // Everything within 40 mm of the close-up's target, up to a Die's height, is still in the map.
        for (int dx = -40; dx <= 40; dx += 80) {
            for (int dz = -40; dz <= 40; dz += 80) {
                for (int y = 0; y <= 25; y += 25) {
                    Vector3d clip = project(close, new Vector3d(60 + dx, y, 30 + dz));
                    assertThat(Math.max(Math.abs(clip.x()), Math.abs(clip.y()))).isLessThan(1);
                }
            }
        }
    }

    /** The outside corners of the footprint's walls (150 + 14 by 100 + 14 mm), at the floor and the wall tops. */
    private static List<Vector3dc> trayCorners() {
        List<Vector3dc> corners = new ArrayList<>();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                corners.add(new Vector3d(sx * 164, 0, sz * 114));
                corners.add(new Vector3d(sx * 164, 30, sz * 114));
            }
        }
        return corners;
    }

    private static Vector3d project(LightView view, Vector3dc world) {
        Vector4d clip = new Vector4d(world, 1).mul(view.viewProjection());
        return new Vector3d(clip.x() / clip.w(), clip.y() / clip.w(), clip.z() / clip.w());
    }
}
