package io.github.evildarkarchon.andysdice.sound;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.planner.PlannedThrow;
import io.github.evildarkarchon.andysdice.core.planner.RollPlan;
import io.github.evildarkarchon.andysdice.core.planner.ThrowPlanner;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The mixer fed by the real Throw planner (ADR-0012 "Testing"): every impact comes from an accepted Attempt,
 * the shaping holds at ×1 and ×4 over whole Roll chains, and sound never shifts a Face Value.
 */
class PlannedRollTest {

    private static final TrayGeometry TRAY = TrayGeometry.rectangle(15, 10, 3);
    private static final TrayMaterials MATERIALS = TrayMaterials.of(
            new ContactMaterial(0.447f, 0.15f), new ContactMaterial(0.447f, 0.5f), 0.5f);
    private static final List<String> ROLLS = List.of("4d6kh3", "1d20 adv", "6d6rr1", "2d8+1d4+3", "1d%",
            "20d6");

    /** The Throws of a planned Roll as the app would queue them: one after another on the clock. */
    private static List<SoundThrow> throwsOf(RollPlan.Planned plan, double start) {
        List<SoundThrow> throwsOf = new ArrayList<>();
        double at = start;
        for (PlannedThrow planned : plan.throwChain()) {
            List<DieKind> kinds = planned.thrown().stream().map(i -> plan.dice().get(i).kind()).toList();
            throwsOf.add(new SoundThrow(planned.recording(), kinds, at, Synthetic.FELT_AND_WOOD));
            at += planned.recording().duration() + 0.3;
        }
        return throwsOf;
    }

    private static RollPlan.Planned plan(ThrowPlanner planner, String roll, FaceValueGenerator faces,
            SplittableRandom releases) {
        return (RollPlan.Planned) planner.plan(roll, faces, releases);
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 4})
    void everyImpactComesFromAnAcceptedAttemptAndTheShapingHolds(double speed) {
        ClatterSimulator simulator = new ClatterSimulator(3); // every 3rd Attempt escapes and is discarded
        ThrowPlanner planner = new ThrowPlanner(simulator, TRAY, MATERIALS);
        FaceValueGenerator faces = new FaceValueGenerator(new SplittableRandom(5));
        SplittableRandom releases = new SplittableRandom(6);
        Rig rig = new Rig(Synthetic.distinctBank(8), 7, MixerTunables.DEFAULTS);
        List<SoundThrow> queued = new ArrayList<>();
        double start = 0.1;
        for (String roll : ROLLS) {
            List<SoundThrow> throwsOf = throwsOf(plan(planner, roll, faces, releases), start);
            throwsOf.forEach(rig.mixer::play);
            queued.addAll(throwsOf);
            SoundThrow last = throwsOf.getLast();
            start = last.start() + last.recording().duration() + 0.5;
        }

        rig.run(start / speed + 0.5, speed);

        Set<Recording> accepted = Collections.newSetFromMap(new IdentityHashMap<>());
        queued.forEach(t -> accepted.add(t.recording()));
        Set<ContactEvent> discarded = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Attempt attempt : simulator.attempts()) {
            if (!accepted.contains(attempt.recording())) {
                assertThat(attempt.ending()).isEqualTo(Ending.ESCAPED);
                discarded.addAll(attempt.recording().contacts());
            }
        }
        assertThat(discarded).isNotEmpty();
        assertThat(rig.impacts).hasSizeGreaterThan(50);
        for (Impact impact : rig.impacts) {
            assertThat(accepted).contains(impact.soundThrow().recording());
            assertThat(impact.soundThrow().recording().contacts()).anyMatch(e -> e == impact.event());
            assertThat(discarded).doesNotContain(impact.event());
            float rest = simulator.rests(impact.soundThrow().recording())[impact.event().die().index()];
            assertThat(impact.event().time()).as("before its Die's rest").isLessThanOrEqualTo(rest);
        }
        // Every queued event was either heard or dropped, exactly once.
        long events = queued.stream().mapToLong(t -> t.recording().contacts().size()).sum();
        assertThat(rig.impacts.size() + rig.drops.size()).isEqualTo((int) events);
        assertThat(rig.drops).extracting(Rig.Drop::reason).contains(DropReason.CULLED, DropReason.AFTER_REST,
                DropReason.MERGED);
        Invariants.cullHolds(rig.impacts, MixerTunables.DEFAULTS);
        Invariants.mergeHolds(rig.impacts);
        Invariants.capHolds(rig.impacts);
        assertThat(rig.mixer.idle()).isTrue();
    }

    @Test
    void aFixedSeedRollSequenceGivesTheSameFaceValuesWithSoundOnAndOff() {
        List<RollPlan.Planned> silent = rolls(null);
        List<RollPlan.Planned> heard = rolls(new Rig(Synthetic.distinctBank(8), 11, MixerTunables.DEFAULTS));
        List<RollPlan.Planned> heardOtherwise = rolls(new Rig(Synthetic.distinctBank(8), 12,
                MixerTunables.DEFAULTS));

        assertThat(heard).extracting(RollPlan.Planned::chains)
                .isEqualTo(silent.stream().map(RollPlan.Planned::chains).toList());
        assertThat(heard).extracting(RollPlan.Planned::result)
                .isEqualTo(silent.stream().map(RollPlan.Planned::result).toList());
        assertThat(heardOtherwise).extracting(RollPlan.Planned::chains)
                .isEqualTo(silent.stream().map(RollPlan.Planned::chains).toList());
    }

    /** Plans the Roll sequence from fixed seeds, playing each Roll's sound through {@code rig} if there is one. */
    private static List<RollPlan.Planned> rolls(Rig rig) {
        ThrowPlanner planner = new ThrowPlanner(new ClatterSimulator(4), TRAY, MATERIALS);
        FaceValueGenerator faces = new FaceValueGenerator(new SplittableRandom(2026));
        SplittableRandom releases = new SplittableRandom(10);
        List<RollPlan.Planned> plans = new ArrayList<>();
        for (String roll : ROLLS) {
            RollPlan.Planned plan = plan(planner, roll, faces, releases);
            plans.add(plan);
            if (rig != null) {
                List<SoundThrow> throwsOf = throwsOf(plan, rig.time());
                throwsOf.forEach(rig.mixer::play);
                SoundThrow last = throwsOf.getLast();
                rig.run(last.start() + last.recording().duration() + 0.2 - rig.time(), 1);
            }
        }
        if (rig != null) {
            assertThat(rig.impacts).isNotEmpty();
        }
        return plans;
    }
}
