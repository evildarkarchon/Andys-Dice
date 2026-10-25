package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * A stand-in simulator for sound tests: no physics, but plenty of clatter. Each Attempt draws, from the
 * Release generator as a real simulator would, when each Die comes to rest and a burst of contacts before
 * then: floor and wall hits, knocks against other Dice and any Frozen Die, some too quiet to hear, and a few
 * contacts of Dice that are already at rest.
 *
 * <p>Every {@code escapeEvery}-th Attempt escapes (with contacts of its own), so the planner discards it and
 * a test can check none of its events ever sound. It remembers every Attempt and each Die's rest time.
 */
final class ClatterSimulator implements Simulator {

    private final int escapeEvery;
    private final List<Attempt> attempts = new ArrayList<>();
    private final Map<Recording, float[]> rests = new IdentityHashMap<>();

    /**
     * @param escapeEvery every this many Attempts, one escapes; 0 for never
     */
    ClatterSimulator(int escapeEvery) {
        this.escapeEvery = escapeEvery;
    }

    /** Every Attempt simulated, oldest first. */
    List<Attempt> attempts() {
        return attempts;
    }

    /** When each Die of a Recording this made came to rest, as it drew them. */
    float[] rests(Recording recording) {
        return rests.get(recording);
    }

    @Override
    public Attempt simulate(AttemptRequest request) {
        RandomGenerator random = ((Release.Drawn) request.release()).generator();
        int dice = request.dice().size();
        float[] rest = new float[dice];
        for (int die = 0; die < dice; die++) {
            rest[die] = 0.3f + 0.3f * random.nextFloat();
        }
        float last = 0;
        for (float r : rest) {
            last = Math.max(last, r);
        }
        List<ContactEvent> events = new ArrayList<>();
        for (int die = 0; die < dice; die++) {
            Vector3 at = new Vector3(random.nextFloat(-12, 12), 0, random.nextFloat(-8, 8));
            int hits = 3 + random.nextInt(6);
            for (int hit = 0; hit < hits; hit++) {
                float time = 0.05f + (rest[die] - 0.05f) * random.nextFloat();
                ContactBody other = switch (random.nextInt(4)) {
                    case 0 -> ContactBody.wall();
                    case 1 -> dice > 1 ? ContactBody.die((die + 1) % dice) : ContactBody.floor();
                    case 2 -> request.frozen().isEmpty() ? ContactBody.floor() : ContactBody.frozenDie(0);
                    default -> ContactBody.floor();
                };
                // One in five too quiet to hear; the rest up to a little over full scale.
                float impulse = random.nextInt(5) == 0 ? 0.001f : 0.02f + 1.1f * random.nextFloat();
                events.add(new ContactEvent(time, ContactBody.die(die), other, impulse, at));
            }
            if (rest[die] + 0.05f < last) {
                // A Die lying still still reports contacts; none of them may sound.
                events.add(new ContactEvent(rest[die] + 0.05f, ContactBody.die(die), ContactBody.floor(), 0.5f, at));
            }
        }
        boolean escapes = escapeEvery > 0 && (attempts.size() + 1) % escapeEvery == 0;
        Recording recording = Synthetic.recording(rest, events);
        Attempt attempt = new Attempt(escapes ? Ending.ESCAPED : Ending.AT_REST, recording, Set.of());
        attempts.add(attempt);
        rests.put(recording, rest);
        return attempt;
    }
}
