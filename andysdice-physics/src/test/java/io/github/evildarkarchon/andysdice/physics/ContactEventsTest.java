package io.github.evildarkarchon.andysdice.physics;

import static io.github.evildarkarchon.andysdice.physics.Scenarios.BOX;
import static io.github.evildarkarchon.andysdice.physics.Scenarios.CAP;
import static io.github.evildarkarchon.andysdice.physics.Scenarios.FELT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.BodyKind;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieStart;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Contact events for sound (ADR-0003 as amended by ADR-0012): floor, wall and die contacts, each timed between
 * the Release and the rest with a positive impulse; and the persisted-contact behaviour logged in ticket #37
 * (see {@link ContactRecorder}): a Die lying still makes no stream of events, while a Die tumbling on a contact
 * that never breaks still clatters.
 */
class ContactEventsTest {

    private static final DieBody D6 = DieKindBody.of(DieKind.D6);

    @Test
    void theCapsContactsStrikeTheFloorWallsAndDiceBetweenTheReleaseAndTheRest() {
        Set<BodyKind> struck = EnumSet.noneOf(BodyKind.class);
        for (long seed = 1; seed <= 4; seed++) {
            Recording recording = Scenarios.simulate(new AttemptRequest(CAP,
                    new Release.Drawn(new SplittableRandom(seed)), List.of(), BOX, FELT)).recording();

            assertThat(recording.contacts()).isNotEmpty();
            for (ContactEvent contact : recording.contacts()) {
                assertThat(contact.time()).isGreaterThan(0).isLessThanOrEqualTo(recording.restTime());
                assertThat(contact.impulse()).isPositive();
                assertThat(contact.die().kind()).isEqualTo(BodyKind.DIE);
                assertThat(contact.die().index()).isBetween(0, CAP.size() - 1);
                if (contact.other().kind() == BodyKind.DIE) {
                    assertThat(contact.other().index()).isBetween(0, CAP.size() - 1)
                            .isNotEqualTo(contact.die().index());
                }
                assertThat(BOX.depthInside(contact.point().x(), contact.point().z())).isGreaterThan(-0.2f);
                struck.add(contact.other().kind());
            }
        }
        assertThat(struck).contains(BodyKind.FLOOR, BodyKind.WALL, BodyKind.DIE);
    }

    @Test
    void aDieSetDownStillMakesNoStreamOfContactsWhileJoltKeepsItAwake() {
        // Lying still but awake, a Die gets a persisted contact every step with a step of gravity's approach
        // speed and a positive impulse estimate; none of that is an impact.
        Recording recording = Scenarios.simulate(Scenarios.given(List.of(D6),
                List.of(Scenarios.still(new Vector3(0, 0.801f, 0), Scenarios.flat(DieKind.D6, 5))), List.of()))
                .recording();

        // It stayed awake for Jolt's 0.35 s before sleeping: about 84 steps of persisted contacts.
        assertThat(recording.restTime()).isGreaterThan(0.3f);
        assertThat(recording.contacts()).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void aDieTippingFromItsEdgeOntoAFaceIsHeardThoughItsContactNeverBreaks() {
        // A d6 on one edge, rolling over it: the edge stays on the floor while the face slaps down, so Jolt
        // reports the slap only as a persisted contact. Recording new contacts alone, the slap (the loudest
        // impact of this Throw) went unheard in ticket #37's logging.
        float onEdge = (float) (0.075 + (0.8 - 0.075) * Math.sqrt(2));
        DieStart tipping = new DieStart(new Pose(new Vector3(-8, onEdge, 0),
                Orientation.about(new Vector3(0, 0, 1), (float) Math.toRadians(45)).times(Scenarios.flat(DieKind.D6, 5))),
                new Vector3(22, 0, 0), new Vector3(0, 0, -20));
        Recording recording = Scenarios.simulate(Scenarios.given(List.of(D6), List.of(tipping), List.of()))
                .recording();

        List<ContactEvent> slaps = recording.contacts().stream()
                .filter(c -> c.other().kind() == BodyKind.FLOOR && c.time() > 0.02f).toList();
        assertThat(slaps).isNotEmpty();
        ContactEvent slap = slaps.getFirst();
        assertThat(slap.impulse()).isGreaterThan(0.05f);
        // Struck on the floor, in front of where it started (it rolls towards +x).
        assertThat(slap.point().y()).isBetween(-0.1f, 0.2f);
        assertThat(slap.point().x()).isGreaterThan(-8.5f);
    }
}
