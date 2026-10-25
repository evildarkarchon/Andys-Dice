package io.github.evildarkarchon.andysdice.core.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A Tray's physics materials, with ADR-0013's die-on-die friction. */
class TrayMaterialsTest {

    @Test
    void dieOnDieFrictionIsTwoTenths() {
        assertThat(TrayMaterials.DIE_ON_DIE_FRICTION).isEqualTo(0.2f);
    }

    @Test
    void aTraysMaterialsGiveDieOnDieContactsThatFrictionAndTheFloorsBounce() {
        ContactMaterial felt = new ContactMaterial(0.45f, 0.15f);
        ContactMaterial walnutWalls = new ContactMaterial(0.45f, 0.5f);

        TrayMaterials materials = TrayMaterials.of(felt, walnutWalls, 0.6f);

        assertThat(materials.floor()).isEqualTo(felt);
        assertThat(materials.walls()).isEqualTo(walnutWalls);
        assertThat(materials.dieOnDie()).isEqualTo(new ContactMaterial(0.2f, 0.15f));
        assertThat(materials.dieAngularDamping()).isEqualTo(0.6f);
    }
}
