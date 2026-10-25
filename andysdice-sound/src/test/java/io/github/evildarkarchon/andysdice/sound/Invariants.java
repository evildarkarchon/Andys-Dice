package io.github.evildarkarchon.andysdice.sound;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.simulation.BodyKind;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import java.util.List;
import java.util.Set;

/**
 * The shaping rules as invariants over the impacts a mixer started (ADR-0012), worked out from the started
 * frames alone, so they hold whatever the mixer did internally.
 */
final class Invariants {

    private static final long MERGE_FRAMES = 1_440; // 30 ms at 48 kHz
    private static final long CAP_FRAMES = 2_400; // 50 ms at 48 kHz
    private static final int CAP = 8;

    private Invariants() {
    }

    /** No two impacts of the same body pair of the same Throw start within 30 ms of each other. */
    static void mergeHolds(List<Impact> impacts) {
        for (int a = 0; a < impacts.size(); a++) {
            for (int b = a + 1; b < impacts.size(); b++) {
                Impact x = impacts.get(a);
                Impact y = impacts.get(b);
                if (x.soundThrow() == y.soundThrow() && samePair(x.event(), y.event())) {
                    assertThat(Math.abs(x.frame() - y.frame()))
                            .as("same pair %s and %s", x.event(), y.event())
                            .isGreaterThanOrEqualTo(MERGE_FRAMES);
                }
            }
        }
    }

    /** No 50 ms window of output holds more than 8 impact starts. */
    static void capHolds(List<Impact> impacts) {
        for (Impact first : impacts) {
            long count = impacts.stream()
                    .filter(i -> i.frame() >= first.frame() && i.frame() < first.frame() + CAP_FRAMES)
                    .count();
            assertThat(count).as("starts in the 50 ms from frame %d", first.frame()).isLessThanOrEqualTo(CAP);
        }
    }

    /** No impact plays an event whose impulse is under 1/64 of its category's full scale. */
    static void cullHolds(List<Impact> impacts, MixerTunables tunables) {
        for (Impact impact : impacts) {
            BodyKind other = impact.event().other().kind();
            double full = other == BodyKind.DIE || other == BodyKind.FROZEN_DIE
                    ? tunables.dieDieFullScale() : tunables.dieTrayFullScale();
            assertThat(impact.event().impulse()).isGreaterThanOrEqualTo((float) (full / 64));
        }
    }

    static boolean samePair(ContactEvent a, ContactEvent b) {
        return Set.of(a.die(), a.other()).equals(Set.of(b.die(), b.other()));
    }
}
