package io.github.evildarkarchon.andysdice.sound;

import static io.github.evildarkarchon.andysdice.sound.Synthetic.dieHit;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.floorHit;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.recording;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.throwOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The shaping rules of ADR-0012, applied as the mixer plays: gain from the impulse, the cull, the per-pair
 * merge, the window cap and nothing after a Die's rest. Jitter is off so gains read exactly.
 */
class ShapingTest {

    private static final MixerTunables NO_JITTER = MixerTunables.DEFAULTS.withJitter(0, 0);

    private static Rig play(List<ContactEvent> events, int dice, double seconds, double speed) {
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.constant(0.1f, 48)), NO_JITTER);
        rig.mixer.play(throwOf(recording(dice, 2f, events), 0));
        return rig.run(seconds, speed);
    }

    private static ContactEvent wallHit(float time, int die, float impulse) {
        return new ContactEvent(time, ContactBody.die(die), ContactBody.wall(), impulse, Synthetic.ORIGIN);
    }

    @Test
    void gainIsLinearInTheImpulseUpToFullScaleWithASeparateFullScaleForDieOnDie() {
        // Die–tray full scale is 1 kg·cm/s and die–die 0.5 (the defaults); events far apart, so none merge.
        ContactEvent quarter = floorHit(0.1f, 0, 0.25f);
        ContactEvent half = floorHit(0.3f, 0, 0.5f);
        ContactEvent full = wallHit(0.5f, 0, 1f);
        ContactEvent over = floorHit(0.7f, 0, 3f);
        ContactEvent dieOnDie = dieHit(0.9f, 0, 1, 0.25f);

        Rig rig = play(List.of(quarter, half, full, over, dieOnDie), 2, 1.2, 1);

        assertThat(rig.impactOf(quarter).gain()).isCloseTo(0.25, within(1e-6));
        assertThat(rig.impactOf(half).gain()).isCloseTo(0.5, within(1e-6));
        assertThat(rig.impactOf(full).gain()).isCloseTo(1, within(1e-6));
        assertThat(rig.impactOf(over).gain()).isCloseTo(1, within(1e-6));
        assertThat(rig.impactOf(dieOnDie).gain()).isCloseTo(0.5, within(1e-6));
    }

    @Test
    void eventsBelowASixtyFourthOfFullScaleAreCulled() {
        ContactEvent quietFloor = floorHit(0.1f, 0, 0.99f / 64);
        ContactEvent audibleFloor = floorHit(0.3f, 0, 1.01f / 64);
        ContactEvent quietDie = dieHit(0.5f, 0, 1, 0.99f * 0.5f / 64);
        ContactEvent audibleDie = dieHit(0.7f, 0, 1, 1.01f * 0.5f / 64);

        Rig rig = play(List.of(quietFloor, audibleFloor, quietDie, audibleDie), 2, 1, 1);

        assertThat(rig.impacts).extracting(Impact::event).containsExactly(audibleFloor, audibleDie);
        assertThat(rig.drops).extracting(Rig.Drop::event, Rig.Drop::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple(quietFloor, DropReason.CULLED),
                org.assertj.core.groups.Tuple.tuple(quietDie, DropReason.CULLED));
    }

    @Test
    void aBodyPairsEventsWithin30msMergeIntoTheLoudest() {
        ContactEvent first = floorHit(0.100f, 0, 0.3f);
        ContactEvent loudest = floorHit(0.120f, 0, 0.6f);
        ContactEvent third = floorHit(0.140f, 0, 0.4f);
        ContactEvent wellAfter = floorHit(0.200f, 0, 0.2f);
        ContactEvent otherDie = floorHit(0.105f, 1, 0.2f);
        ContactEvent sameDieOnAWall = wallHit(0.110f, 0, 0.2f);
        ContactEvent dieOnDie = dieHit(0.300f, 0, 1, 0.2f);
        ContactEvent sameDiceTheOtherWayRound = dieHit(0.310f, 1, 0, 0.3f);

        Rig rig = play(List.of(first, loudest, third, wellAfter, otherDie, sameDieOnAWall, dieOnDie,
                sameDiceTheOtherWayRound), 2, 0.6, 1);

        assertThat(rig.impacts).extracting(Impact::event).containsExactlyInAnyOrder(
                loudest, wellAfter, otherDie, sameDieOnAWall, sameDiceTheOtherWayRound);
        assertThat(rig.drops).filteredOn(d -> d.reason() == DropReason.MERGED).extracting(Rig.Drop::event)
                .containsExactlyInAnyOrder(first, third, dieOnDie);
    }

    @Test
    void theMergeWindowIsRealTimeSoFastForwardMergesMore() {
        // 80 ms of playback apart: two impacts at ×1, but only 20 ms of real time at ×4.
        ContactEvent first = floorHit(0.10f, 0, 0.5f);
        ContactEvent second = floorHit(0.18f, 0, 0.7f);

        assertThat(play(List.of(first, second), 1, 0.4, 1).impacts).hasSize(2);
        Rig fast = play(List.of(first, second), 1, 0.1, 4);
        assertThat(fast.impacts).extracting(Impact::event).containsExactly(second);
    }

    @Test
    void atMostEightImpactsStartTogetherAndTheQuietestAreDropped() {
        List<ContactEvent> events = new ArrayList<>();
        for (int die = 0; die < 12; die++) {
            events.add(floorHit(0.1f, die, 0.1f * (die + 1)));
        }

        Rig rig = play(events, 12, 0.3, 1);

        assertThat(rig.impacts).extracting(Impact::event).containsExactlyInAnyOrderElementsOf(events.subList(4, 12));
        assertThat(rig.drops).extracting(Rig.Drop::reason).containsOnly(DropReason.CAPPED);
    }

    @Test
    void louderImpactsJustAheadTakeTheirPlacesFromQuieterOnes() {
        List<ContactEvent> quiet = new ArrayList<>();
        List<ContactEvent> loud = new ArrayList<>();
        for (int die = 0; die < 8; die++) {
            quiet.add(floorHit(0.100f, die, 0.1f));
        }
        for (int die = 8; die < 12; die++) {
            loud.add(floorHit(0.120f, die, 0.9f));
        }
        List<ContactEvent> all = new ArrayList<>(quiet);
        all.addAll(loud);

        Rig rig = play(all, 12, 0.3, 1);

        assertThat(rig.impacts).extracting(Impact::event).containsAll(loud);
        assertThat(rig.impacts).hasSize(8);
        Invariants.capHolds(rig.impacts);
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 4})
    void theCapHoldsInRealTimeAtEverySpeed(double speed) {
        // 40 hits of different Dice, 5 ms of playback apart: 10 per 50 ms at ×1, 40 at ×4.
        List<ContactEvent> events = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            events.add(floorHit(0.1f + 0.005f * i, i % 20, 0.05f + 0.02f * (i % 7)));
        }

        Rig rig = play(events, 20, 0.5, speed);

        Invariants.capHolds(rig.impacts);
        Invariants.mergeHolds(rig.impacts);
        assertThat(rig.impacts.size() + rig.drops.size()).isEqualTo(40);
        assertThat(rig.impacts.size()).isGreaterThanOrEqualTo(speed == 1 ? 24 : 8);
    }

    @Test
    void nothingSoundsAfterItsDieIsAtRest() {
        ContactEvent beforeRest = floorHit(0.45f, 0, 0.5f);
        ContactEvent afterRest = floorHit(0.70f, 0, 0.5f);
        ContactEvent otherDieStillMoving = floorHit(0.72f, 1, 0.5f);
        ContactEvent restingDieStruck = dieHit(0.80f, 0, 1, 0.4f);
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.constant(0.1f, 48)), NO_JITTER);
        // Die 0 lies still from 0.5 s, Die 1 from 1 s.
        rig.mixer.play(throwOf(recording(new float[] {0.5f, 1f},
                List.of(beforeRest, afterRest, otherDieStillMoving, restingDieStruck)), 0));

        rig.run(1.2, 1);

        assertThat(rig.impacts).extracting(Impact::event).containsExactly(beforeRest, otherDieStillMoving);
        assertThat(rig.drops).extracting(Rig.Drop::event, Rig.Drop::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple(afterRest, DropReason.AFTER_REST),
                org.assertj.core.groups.Tuple.tuple(restingDieStruck, DropReason.AFTER_REST));
    }
}
