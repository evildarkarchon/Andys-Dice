package io.github.evildarkarchon.andysdice.sound;

import static io.github.evildarkarchon.andysdice.sound.Synthetic.recording;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.throwOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each impact is equal-power panned, with modest width, by its contact point's screen-space x at mix time
 * (ADR-0012 "Output").
 */
class PanningTest {

    private static final MixerTunables NO_JITTER = MixerTunables.DEFAULTS.withJitter(0, 0);

    private static ContactEvent hitAt(float time, int die, float x) {
        return new ContactEvent(time, ContactBody.die(die), ContactBody.floor(), 0.5f, new Vector3(x, 0, 0));
    }

    @Test
    void panningIsEqualPowerAndFollowsScreenX() {
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.constant(0.2f, 48)), NO_JITTER);
        // A projection that puts world x = -10 cm at the left edge and +10 cm at the right.
        rig.mixer.setProjection(point -> point.x() / 10);
        List<ContactEvent> events = new ArrayList<>();
        float[] xs = {-10, -5, 0, 3, 10, 25};
        for (int i = 0; i < xs.length; i++) {
            events.add(hitAt(0.1f + 0.1f * i, 0, xs[i]));
        }
        rig.mixer.play(throwOf(recording(1, 1f, events), 0));

        rig.run(0.8, 1);

        for (ContactEvent event : events) {
            Impact impact = rig.impactOf(event);
            assertThat(impact.left() * impact.left() + impact.right() * impact.right()).isCloseTo(1, within(1e-9));
        }
        Impact left = rig.impactOf(events.get(0));
        Impact centre = rig.impactOf(events.get(2));
        Impact right = rig.impactOf(events.get(4));
        assertThat(centre.left()).isCloseTo(Math.sqrt(0.5), within(1e-9));
        assertThat(centre.right()).isCloseTo(Math.sqrt(0.5), within(1e-9));
        assertThat(left.left()).isGreaterThan(left.right());
        assertThat(right.right()).isGreaterThan(right.left());
        // Modest width: even the screen's edge keeps some sound in the far channel.
        assertThat(right.left()).isGreaterThan(0.2);
        // Points off screen pan as if at its edge.
        assertThat(rig.impactOf(events.get(5)).right()).isCloseTo(right.right(), within(1e-9));
        assertThat(rig.impactOf(events.get(1)).left())
                .isBetween(centre.left(), left.left());
    }

    @Test
    void theOutputChannelsCarryThePanGains() {
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.constant(0.4f, 480)), NO_JITTER);
        rig.mixer.setVolume(1);
        rig.mixer.setProjection(point -> 1);
        ContactEvent event = hitAt(0.1f, 0, 0);
        rig.mixer.play(throwOf(recording(1, 1f, List.of(event)), 0));

        rig.run(0.2, 1);

        Impact impact = rig.impactOf(event);
        short[] out = rig.output();
        int frame = (int) impact.frame() + 10;
        assertThat((double) out[2 * frame] / out[2 * frame + 1]).isCloseTo(impact.left() / impact.right(),
                within(0.01));
    }

    @Test
    void theProjectionIsReadWhenTheImpactStartsNotWhenTheThrowIsQueued() {
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.constant(0.2f, 48)), NO_JITTER);
        ContactEvent event = hitAt(0.3f, 0, 0);
        rig.mixer.setProjection(point -> -1);
        rig.mixer.play(throwOf(recording(1, 1f, List.of(event)), 0));
        rig.run(0.1, 1);

        rig.mixer.setProjection(point -> 1); // the camera orbits before the contact is heard
        rig.run(0.4, 1);

        Impact impact = rig.impactOf(event);
        assertThat(impact.right()).isGreaterThan(impact.left());
    }
}
