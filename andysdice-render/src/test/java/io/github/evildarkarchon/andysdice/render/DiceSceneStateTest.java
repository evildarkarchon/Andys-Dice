package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.util.List;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * What each of the {@link DiceScene}'s setters hands over for the next frame (#80): every setter replaces the one
 * state a frame reads once, so what one call sets together is drawn together. These read the state the setters
 * leave without drawing it; a scene makes no GL calls until {@code init()}, so this is pure Java, no GL.
 */
class DiceSceneStateTest {

    private static final DiceSet RUBY = DiceSetCatalogue.builtIn().find("12-ruby").orElseThrow();
    private static final DiceSet SMOKE = DiceSetCatalogue.builtIn().find("11-smoke").orElseThrow();
    private static final Tray FELT = BuiltInTrays.find("01-green-felt").orElseThrow();
    private static final Tray WALNUT = BuiltInTrays.find("03-walnut-board").orElseThrow();
    private static final DiceScene.Camera ABOVE = new DiceScene.Camera(new Vector3d(0, 60, 0.01), new Vector3d(), 32);
    private static final List<DiceScene.Die> ONE_D20 =
            List.of(new DiceScene.Die(DieKind.D20, new Vector3d(), new Quaterniond()));

    private final DiceScene scene = new DiceScene();

    @Test
    void aNewSceneDrawsNoTrayAtFullQuality() {
        DiceScene.SceneState state = scene.state();

        assertThat(state.dice()).isEmpty();
        assertThat(state.tray()).isNull();
        assertThat(state.diceSet()).isEqualTo(DiceSetCatalogue.builtIn().fallback());
        assertThat(state.lightRig()).isEqualTo(LightRig.DEFAULT);
        assertThat(state.qualityPreset()).isEqualTo(QualityPreset.HIGH);
        assertThat(state.volumeSteps()).isEqualTo(DiceScene.DEFAULT_VOLUME_STEPS);
        assertThat(state.clearColour()).isEqualTo(DiceScene.BACKGROUND_ARGB);
    }

    @Test
    void aLookIsHandedOverWhole() {
        scene.setDiceAndCamera(ONE_D20, ABOVE);

        scene.setLook(new Look(RUBY, WALNUT, LightRig.LAMP));

        DiceScene.SceneState state = scene.state();
        assertThat(new Look(state.diceSet(), state.tray(), state.lightRig()))
                .isEqualTo(new Look(RUBY, WALNUT, LightRig.LAMP));
        // The Dice and the camera are not the Look's: they stay as they were.
        assertThat(state.dice()).isEqualTo(ONE_D20);
        assertThat(state.camera()).isEqualTo(ABOVE);
    }

    @Test
    void aLookAndItsCameraAreHandedOverTogetherAndTheDiceStay() {
        scene.setDice(ONE_D20);

        scene.setLookAndCamera(new Look(SMOKE, FELT, LightRig.WINDOW), ABOVE);

        DiceScene.SceneState state = scene.state();
        assertThat(new Look(state.diceSet(), state.tray(), state.lightRig()))
                .isEqualTo(new Look(SMOKE, FELT, LightRig.WINDOW));
        assertThat(state.camera()).isEqualTo(ABOVE);
        assertThat(state.dice()).isEqualTo(ONE_D20);
    }

    @Test
    void eachPartOfTheLookCanStillBeSetAloneLeavingTheOthers() {
        scene.setLook(new Look(RUBY, WALNUT, LightRig.LAMP));

        scene.setTray(FELT);
        assertThat(scene.state().tray()).isEqualTo(FELT);
        assertThat(scene.state().diceSet()).isEqualTo(RUBY);
        assertThat(scene.state().lightRig()).isEqualTo(LightRig.LAMP);

        scene.setDiceSet(SMOKE);
        assertThat(scene.state().diceSet()).isEqualTo(SMOKE);
        assertThat(scene.state().tray()).isEqualTo(FELT);

        scene.setLightRig(LightRig.STUDIO);
        assertThat(scene.state().lightRig()).isEqualTo(LightRig.STUDIO);
        assertThat(scene.state().diceSet()).isEqualTo(SMOKE);
    }

    @Test
    void theDiceAndTheCameraCanEachBeSetAloneLeavingTheOther() {
        scene.setDiceAndCamera(ONE_D20, ABOVE);

        DiceScene.Camera side = new DiceScene.Camera(new Vector3d(0, 0, 150), new Vector3d(), 32);
        scene.setCamera(side);
        assertThat(scene.state().dice()).isEqualTo(ONE_D20);

        scene.setDice(List.of());
        assertThat(scene.state().camera()).isEqualTo(side);
    }

    @Test
    void aQualityPresetBringsItsOwnVolumeMarch() {
        scene.setVolumeSteps(1);

        scene.setQualityPreset(QualityPreset.LOW);

        assertThat(scene.state().qualityPreset()).isEqualTo(QualityPreset.LOW);
        assertThat(scene.state().volumeSteps()).isEqualTo(QualityPreset.LOW.volumeSteps());
    }

    @Test
    void aTestsVolumeMarchKeepsThePreset() {
        scene.setQualityPreset(QualityPreset.MEDIUM);

        scene.setVolumeSteps(3);

        assertThat(scene.state().volumeSteps()).isEqualTo(3);
        assertThat(scene.state().qualityPreset()).isEqualTo(QualityPreset.MEDIUM);
    }

    @Test
    void theSampleCountIsBounded() {
        assertThat(DiceScene.DEFAULT_VOLUME_STEPS).as("ADR-0009's 8 steps").isEqualTo(8);
        assertThatThrownBy(() -> scene.setVolumeSteps(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.setVolumeSteps(DiceScene.MAX_VOLUME_STEPS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRefusedHandOverChangesNothing() {
        scene.setLook(new Look(RUBY, WALNUT, LightRig.LAMP));
        DiceScene.SceneState before = scene.state();

        assertThatThrownBy(() -> scene.setVolumeSteps(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.setLookAndCamera(new Look(SMOKE, FELT, LightRig.WINDOW), null))
                .isInstanceOf(NullPointerException.class);

        assertThat(scene.state()).isSameAs(before);
    }
}
