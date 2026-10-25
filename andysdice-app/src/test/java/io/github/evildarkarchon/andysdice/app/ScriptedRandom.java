package io.github.evildarkarchon.andysdice.app;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.random.RandomGenerator;

/**
 * A Face Value source that returns exactly the values a test lists, in order, so a test can force a Face Value
 * sequence no seed would give in practice (a reroll every time, a hundred rerolls in a row). Only bounded int
 * draws are supported, which is all the Face Value generator makes.
 */
final class ScriptedRandom implements RandomGenerator {

    private final Deque<Integer> values = new ArrayDeque<>();

    /**
     * Scripts the values to return.
     *
     * @param values the draws, in order
     */
    ScriptedRandom(int... values) {
        for (int value : values) {
            this.values.add(value);
        }
    }

    /**
     * Scripts {@code times} more draws of {@code value}.
     *
     * @param times how many
     * @param value the value each returns
     * @return this source
     */
    ScriptedRandom thenRepeat(int times, int value) {
        for (int i = 0; i < times; i++) {
            values.add(value);
        }
        return this;
    }

    /**
     * Returns the next scripted value.
     *
     * @throws IllegalStateException if the script ran out or the value is outside {@code [origin, bound)}
     */
    @Override
    public int nextInt(int origin, int bound) {
        Integer value = values.poll();
        if (value == null) {
            throw new IllegalStateException("the scripted Face Values ran out");
        }
        if (value < origin || value >= bound) {
            throw new IllegalStateException("scripted value " + value + " is outside [" + origin + ", " + bound + ")");
        }
        return value;
    }

    /** Not scripted: the Face Value generator only makes bounded int draws. */
    @Override
    public long nextLong() {
        throw new UnsupportedOperationException("only bounded int draws are scripted");
    }
}
