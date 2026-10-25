package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionCaps;
import io.github.evildarkarchon.andysdice.core.simulation.OutlinePoint;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The GPU half of {@code --bench} (ADR-0014, ticket #61) without GL: which scenes it looks for the most expensive
 * among, the 20 Dice they hold, and how a frame's GPU times are summed up and judged against the 0.5 ms budget.
 */
class GpuBenchTest {

    @Test
    void theBudgetIsHalfAMillisecondAt1080pOnMedium() {
        assertThat(GpuBench.BUDGET_MILLIS).isEqualTo(0.5);
        assertThat(GpuBench.WIDTH_PX).isEqualTo(1920);
        assertThat(GpuBench.HEIGHT_PX).isEqualTo(1080);
        assertThat(GpuBench.BUDGETED_PRESET).isEqualTo(QualityPreset.MEDIUM);
    }

    @Test
    void theMostExpensiveSceneIsLookedForAmongEveryTranslucentSetTrayRigAndShot() {
        List<DiceSet> translucent = DiceSetCatalogue.builtIn().diceSets().stream().filter(DiceSet::translucent)
                .toList();
        // Spec Appendix B: Smoke, Ruby, Emerald and Nebula.
        assertThat(translucent).hasSize(4);

        List<GpuBench.Scene> scenes = GpuBench.scenes();

        assertThat(scenes).hasSize(4 * BuiltInTrays.all().size() * LightRig.values().length
                * GpuBench.Shot.values().length);
        assertThat(scenes).doesNotHaveDuplicates();
        assertThat(scenes).extracting(GpuBench.Scene::diceSet).allMatch(DiceSet::translucent)
                .containsAll(translucent);
        assertThat(scenes).extracting(GpuBench.Scene::tray).containsAll(BuiltInTrays.all());
        assertThat(scenes).extracting(GpuBench.Scene::lightRig).contains(LightRig.values());
        assertThat(scenes).extracting(GpuBench.Scene::shot).contains(GpuBench.Shot.values());
    }

    @Test
    void itsDiceAreAsManyAsAThrowMayHoldRestingApartInsideEveryBuiltInTray() {
        List<DiceScene.Die> dice = GpuBench.dice();

        assertThat(dice).hasSize(RollExpressionCaps.MAX_DICE_PER_THROW);
        for (DiceScene.Die die : dice) {
            // Resting on the floor: the centre an inradius up.
            assertThat(die.position().y()).isEqualTo(die.kind().inradius());
            for (Tray tray : BuiltInTrays.all()) {
                assertThat(inside(tray.geometry().outline(), die.position().x(), die.position().z(),
                        die.kind().inradius() * 2)).as(die + " inside " + tray.identity()).isTrue();
            }
        }
        for (int i = 0; i < dice.size(); i++) {
            for (int j = i + 1; j < dice.size(); j++) {
                DiceScene.Die a = dice.get(i);
                DiceScene.Die b = dice.get(j);
                // Apart: no two bounding spheres (about 1.6 inradii round a Die) overlap.
                assertThat(a.position().distance(b.position()))
                        .isGreaterThan(1.6 * (a.kind().inradius() + b.kind().inradius()));
            }
        }
    }

    @Test
    void aScenesTimeIsTheMedianOfItsFrames() {
        GpuBench.Scene scene = GpuBench.scenes().getFirst();

        assertThat(GpuBench.GpuTime.of(scene, QualityPreset.MEDIUM, List.of(0.9, 0.2, 0.4)).millis())
                .isEqualTo(0.4);
        assertThat(GpuBench.GpuTime.of(scene, QualityPreset.MEDIUM, List.of(0.9, 0.2, 0.4, 0.3)).millis())
                .isCloseTo(0.35, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(GpuBench.GpuTime.of(scene, QualityPreset.MEDIUM, List.of(0.9, 0.2, 0.4)).frames()).isEqualTo(3);
        assertThatThrownBy(() -> GpuBench.GpuTime.of(scene, QualityPreset.MEDIUM, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theReportMeetsTheBudgetOnlyWhenMediumDrawsTheWorstSceneInHalfAMillisecond() {
        GpuBench.Scene cheap = GpuBench.scenes().getFirst();
        GpuBench.Scene dear = GpuBench.scenes().getLast();
        GpuBench.GpuTime cheapMedium = new GpuBench.GpuTime(cheap, QualityPreset.MEDIUM, 0.2, 10);
        GpuBench.GpuTime dearMedium = new GpuBench.GpuTime(dear, QualityPreset.MEDIUM, 0.5, 10);
        Map<QualityPreset, GpuBench.GpuTime> byPreset = Map.of(
                QualityPreset.LOW, new GpuBench.GpuTime(dear, QualityPreset.LOW, 0.3, 10),
                QualityPreset.MEDIUM, dearMedium,
                QualityPreset.HIGH, new GpuBench.GpuTime(dear, QualityPreset.HIGH, 1.1, 10));

        GpuBench.Report met = new GpuBench.Report(List.of(cheapMedium, dearMedium), byPreset);
        GpuBench.Report missed = new GpuBench.Report(List.of(cheapMedium, dearMedium), Map.of(
                QualityPreset.LOW, byPreset.get(QualityPreset.LOW),
                QualityPreset.MEDIUM, new GpuBench.GpuTime(dear, QualityPreset.MEDIUM, 0.51, 10),
                QualityPreset.HIGH, byPreset.get(QualityPreset.HIGH)));

        assertThat(met.worst()).isEqualTo(dearMedium);
        // High may cost more than the budget: only the default preset is budgeted.
        assertThat(met.meetsBudget()).isTrue();
        assertThat(missed.meetsBudget()).isFalse();
        assertThat(met.budgeted().millis()).isEqualTo(0.5);
    }

    /** Whether a circle of the given radius (mm) round (x, z) lies inside a convex outline given in cm. */
    private static boolean inside(List<OutlinePoint> outline, double x, double z, double radius) {
        // Either winding: the point must be on the same side of every edge, at least the radius in.
        double sign = 0;
        for (int i = 0; i < outline.size(); i++) {
            OutlinePoint a = outline.get(i);
            OutlinePoint b = outline.get((i + 1) % outline.size());
            double ax = a.x() * 10;
            double az = a.z() * 10;
            double ex = b.x() * 10 - ax;
            double ez = b.z() * 10 - az;
            double cross = (ex * (z - az) - ez * (x - ax)) / Math.hypot(ex, ez);
            if (sign == 0) {
                sign = Math.signum(cross);
            }
            if (cross * sign < radius) {
                return false;
            }
        }
        return true;
    }
}
