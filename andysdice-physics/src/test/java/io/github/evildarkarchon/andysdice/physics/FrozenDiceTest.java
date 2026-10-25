package io.github.evildarkarchon.andysdice.physics;

import static io.github.evildarkarchon.andysdice.physics.Scenarios.BOX;
import static io.github.evildarkarchon.andysdice.physics.Scenarios.CAP;
import static io.github.evildarkarchon.andysdice.physics.Scenarios.FELT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.BodyKind;
import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.FrozenDie;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Frozen Dice are static bodies that thrown Dice can strike but never move (ADR-0006). */
class FrozenDiceTest {

    private static final DieBody D6 = DieKindBody.of(DieKind.D6);
    private static final DieBody D12 = DieKindBody.of(DieKind.D12);

    @Test
    void aDieDroppedOnAFrozenDieStrikesItWithoutMovingIt() {
        Pose lying = new Pose(new Vector3(0, 0.95f, 0), Scenarios.flat(DieKind.D12, 0));
        AttemptRequest request = Scenarios.given(List.of(D6),
                List.of(Scenarios.still(new Vector3(0.3f, 4, 0.2f), Scenarios.flat(DieKind.D6, 2))),
                List.of(new FrozenDie(D12, lying)));

        JoltSimulator.Run run = Scenarios.run(request);

        assertThat(run.frozenAtEnd()).containsExactly(lying);
        List<ContactEvent> onFrozen = run.attempt().recording().contacts().stream()
                .filter(c -> c.other().equals(ContactBody.frozenDie(0))).toList();
        float rest = run.attempt().recording().restTime();
        assertThat(onFrozen).isNotEmpty().allSatisfy(c -> {
            assertThat(c.impulse()).isPositive();
            assertThat(c.time()).isGreaterThan(0).isLessThanOrEqualTo(rest);
        });
        // It landed on the Frozen Die's top face, a little under 1.9 cm up.
        assertThat(onFrozen.getFirst().point().y()).isBetween(1.7f, 2.0f);
    }

    @Test
    void halfTheCapThrownAmongTheOtherHalfFrozenNeverMovesThem() {
        // The other half come to rest first, as in a Reroll Throw (ADR-0006).
        Attempt first = Scenarios.simulate(new AttemptRequest(CAP, new Release.Drawn(new SplittableRandom(1)),
                List.of(), BOX, FELT));
        assertThat(first.ending()).isIn(Ending.AT_REST, Ending.COCKED);
        List<FrozenDie> frozen = new ArrayList<>();
        for (int die = 10; die < CAP.size(); die++) {
            frozen.add(new FrozenDie(CAP.get(die), first.recording().finalPose(die)));
        }
        List<Pose> lying = frozen.stream().map(FrozenDie::pose).toList();

        int strikes = 0;
        for (long seed = 2; seed <= 4; seed++) {
            JoltSimulator.Run reroll = Scenarios.run(new AttemptRequest(CAP.subList(0, 10),
                    new Release.Drawn(new SplittableRandom(seed)), frozen, BOX, FELT));

            assertThat(reroll.frozenAtEnd()).isEqualTo(lying);
            strikes += (int) reroll.attempt().recording().contacts().stream()
                    .filter(c -> c.other().kind() == BodyKind.FROZEN_DIE).count();
        }
        assertThat(strikes).isPositive();
    }
}
