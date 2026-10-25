package io.github.evildarkarchon.andysdice.core.simulation;

/**
 * The simulator seam (ADR-0003): something that pre-simulates one Attempt of a Throw and reports it in
 * engine-neutral terms. {@code physics} implements it with Jolt; {@code core}'s tests script a fake.
 *
 * <p>The world is in centimetres with y up and the floor's top at y = 0, under {@link #GRAVITY}. An Attempt
 * ends in exactly one of the four {@link Ending}s, and one that is still moving after
 * {@link #TIME_CAP_SECONDS} has {@link Ending#TIMED_OUT timed out}. The Jolt simulator also gives a Die
 * settling cocked its Settle Nudges inside the Attempt (ADR-0013); a fake need not.
 *
 * <p>Calls are synchronous. An implementation may be confined to one thread (the Jolt one runs only on the
 * physics worker that started it), so callers make every call from the thread the implementation was handed
 * to them on.
 */
public interface Simulator {

    /** Gravity in the simulator's world: about 9.81 m/s² downwards, in cm/s² (ADR-0003). */
    Vector3 GRAVITY = new Vector3(0, -981, 0);

    /** The simulated-time cap of one Attempt, in seconds (ADR-0014). */
    float TIME_CAP_SECONDS = 4;

    /**
     * Simulates one Attempt from its Release until it ends.
     *
     * @param request the Dice, Release, Frozen Dice and Tray
     * @return how it ended, with its Recording
     */
    Attempt simulate(AttemptRequest request);
}
