package io.github.evildarkarchon.andysdice.physics;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.CollideShapeResult;
import com.github.stephengold.joltjni.CollisionEstimationResult;
import com.github.stephengold.joltjni.ContactManifold;
import com.github.stephengold.joltjni.ContactSettings;
import com.github.stephengold.joltjni.CustomContactListener;
import com.github.stephengold.joltjni.MiscUtil;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import io.github.evildarkarchon.andysdice.core.simulation.BodyKind;
import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jolt's contact listener for one Attempt. It does two jobs:
 *
 * <ul>
 *   <li>It sets each contact's combined friction and restitution from the Tray's materials by what the two
 *       bodies are, so die-on-die friction is exactly the requested 0.2 and die-on-floor and die-on-wall keep
 *       their own values (ADR-0013 leaves the combine method to the build; this is the per-pair one).
 *   <li>It records contact events for sound, each with its impulse estimated by
 *       {@link MiscUtil#estimateCollisionResponse} before the solver runs (ADR-0003 as amended by ADR-0012).
 * </ul>
 *
 * <p><b>When an event is recorded.</b> A new contact ({@code onContactAdded}) is recorded if its estimated
 * impulse is positive. A persisted contact ({@code onContactPersisted}) is recorded only when its normal
 * approach speed rises past {@link #RISE_SPEED} since the previous step: a Die tumbling on a contact that never
 * breaks slaps a new face down this way. That rule comes from logging real Throws of the 20-Dice cap (ticket
 * #37), which showed:
 *
 * <ul>
 *   <li>Every awake Die lying still on something gets {@code onContactPersisted} on every step until Jolt puts
 *       it to sleep, with an approach speed of one step of gravity (981 / 240 ≈ 4.09 cm/s; Jolt calls the
 *       listener after gravity has been added to the velocities) and a positive estimated impulse, its weight
 *       for one step. Recording every persisted contact with a positive impulse would add about 2,000 silent
 *       "events" to a Throw. Once a Die sleeps it gets no callbacks at all.
 *   <li>Real impacts on persisted contacts do happen: 13 to 45 per Throw rose past 10 cm/s, against about 73
 *       new contacts, so recording new contacts alone would lose a third of the clatter.
 *   <li>About two thirds of persisted callbacks come with no {@code onContactValidate} that step (Jolt reuses
 *       the cached manifold), so their contact point is the pair's last validated point, carried along with the
 *       Die since.
 * </ul>
 *
 * <p>Jolt calls the listener from inside {@code PhysicsSystem.update}, on the physics worker, since the job
 * system is single-threaded. An exception must not unwind through Jolt's native frames, so each callback catches
 * everything and keeps the first failure for {@link #rethrowFailure()} after the step.
 */
final class ContactRecorder extends CustomContactListener {

    /**
     * A persisted contact makes a new event when its normal approach speed rises past this, in cm/s: well above
     * the one step of gravity (about 4.09 cm/s) that every resting, awake Die shows, and the small jitter around
     * it (up to about 6 cm/s in the logged Throws).
     */
    static final float RISE_SPEED = 10;

    /** What the listener remembers about a touching pair of bodies between steps. */
    private static final class Pair {
        /** The normal approach speed at the last callback, in cm/s; positive when closing. */
        float approach;
        /** The last validated contact point, or {@code null} if none was seen yet. */
        Vector3 point;
        /** Where the pair's Die's centre of mass was when {@link #point} was seen. */
        Vector3 dieCentre;
    }

    private final TrayMaterials materials;
    private final float minVelocity;
    private final CollisionEstimationResult estimate;
    private final Map<Integer, ContactBody> bodies = new HashMap<>();
    /** Contact points seen by onContactValidate in the current step, by body pair. */
    private final Map<Long, Vector3> points = new HashMap<>();
    private final Map<Long, Pair> pairs = new HashMap<>();
    private final List<ContactEvent> events = new ArrayList<>();
    private float time;
    private Throwable failure;

    /**
     * Creates the listener.
     *
     * @param materials the Tray's materials
     * @param minVelocity the approach speed below which Jolt applies no restitution, in cm/s; the estimate uses
     *     the same one so it matches the solver
     * @param estimate reusable storage for the impulse estimate; owned by the caller's scope
     */
    ContactRecorder(TrayMaterials materials, float minVelocity, CollisionEstimationResult estimate) {
        this.materials = materials;
        this.minVelocity = minVelocity;
        this.estimate = estimate;
    }

    /**
     * Says what a body is, so its contacts can be named and given their material.
     *
     * @param bodyId Jolt's body ID
     * @param body what the body is
     */
    void register(int bodyId, ContactBody body) {
        bodies.put(bodyId, body);
    }

    /**
     * Starts a step: contacts found during it are stamped with its time.
     *
     * @param stepEndTime the simulated time at the end of the step, in seconds
     */
    void beginStep(float stepEndTime) {
        time = stepEndTime;
        points.clear();
    }

    /**
     * Rethrows the first failure a callback caught, if any.
     *
     * @throws IllegalStateException wrapping the failure
     */
    void rethrowFailure() {
        if (failure != null) {
            throw new IllegalStateException("A Jolt contact callback failed", failure);
        }
    }

    /**
     * Returns the contact events recorded so far.
     *
     * @return the events, in time order
     */
    List<ContactEvent> events() {
        return events;
    }

    @Override
    public int onContactValidate(long body1Va, long body2Va, double baseOffsetX, double baseOffsetY,
            double baseOffsetZ, long collisionResultVa) {
        try {
            // jolt-jni 6.2.0's ContactManifold doesn't expose its contact points, so the point is taken here,
            // where Jolt hands over the collision result (positions relative to the base offset).
            CollideShapeResult result = new CollideShapeResult(collisionResultVa);
            Vec3 on1 = result.getContactPointOn1();
            Vec3 on2 = result.getContactPointOn2();
            Vector3 point = new Vector3(
                    (float) (baseOffsetX + (on1.getX() + on2.getX()) / 2),
                    (float) (baseOffsetY + (on1.getY() + on2.getY()) / 2),
                    (float) (baseOffsetZ + (on1.getZ() + on2.getZ()) / 2));
            points.putIfAbsent(pairKey(new Body(body1Va).getId(), new Body(body2Va).getId()), point);
        } catch (Throwable t) {
            fail(t);
        }
        return super.onContactValidate(body1Va, body2Va, baseOffsetX, baseOffsetY, baseOffsetZ,
                collisionResultVa);
    }

    @Override
    public void onContactAdded(long body1Va, long body2Va, long manifoldVa, long settingsVa) {
        try {
            Body body1 = new Body(body1Va);
            Body body2 = new Body(body2Va);
            ContactMaterial material = combine(new ContactSettings(settingsVa), body1, body2);
            ContactManifold manifold = new ContactManifold(manifoldVa);
            // A new contact starts the pair afresh, even if it touched before and parted.
            Pair pair = new Pair();
            pairs.put(pairKey(body1.getId(), body2.getId()), pair);
            Vector3 point = track(pair, body1, body2, manifold);
            record(body1, body2, manifold, material, point);
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void onContactPersisted(long body1Va, long body2Va, long manifoldVa, long settingsVa) {
        try {
            Body body1 = new Body(body1Va);
            Body body2 = new Body(body2Va);
            // Jolt recomputes the combined values for a persisted contact, so they are set again every step.
            ContactMaterial material = combine(new ContactSettings(settingsVa), body1, body2);
            ContactManifold manifold = new ContactManifold(manifoldVa);
            Pair pair = pairs.computeIfAbsent(pairKey(body1.getId(), body2.getId()), k -> new Pair());
            float before = pair.approach;
            Vector3 point = track(pair, body1, body2, manifold);
            if (before < RISE_SPEED && pair.approach >= RISE_SPEED) {
                record(body1, body2, manifold, material, point);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Updates what is known about a touching pair this step: its contact point and its normal approach speed.
     *
     * @return the best contact point for this step: the one validated this step, else the pair's last validated
     *     point moved along with its Die since, else the Die's centre of mass
     */
    private Vector3 track(Pair pair, Body body1, Body body2, ContactManifold manifold) {
        Body die = bodies.get(body1.getId()).kind() == BodyKind.DIE ? body1 : body2;
        Vector3 centre = vector(die.getCenterOfMassPosition());
        Vector3 point = points.get(pairKey(body1.getId(), body2.getId()));
        if (point != null) {
            pair.point = point;
            pair.dieCentre = centre;
        } else if (pair.point != null) {
            point = new Vector3(pair.point.x() + centre.x() - pair.dieCentre.x(),
                    pair.point.y() + centre.y() - pair.dieCentre.y(),
                    pair.point.z() + centre.z() - pair.dieCentre.z());
        } else {
            point = centre;
        }
        pair.approach = approachSpeed(body1, body2, manifold.getWorldSpaceNormal(), point);
        return point;
    }

    /** Estimates the contact's impulse and records an event if it is positive. */
    private void record(Body body1, Body body2, ContactManifold manifold, ContactMaterial material,
            Vector3 point) {
        MiscUtil.estimateCollisionResponse(body1, body2, manifold, estimate, material.friction(),
                material.restitution(), minVelocity);
        float impulse = 0;
        for (float pointImpulse : estimate.getContactImpulses()) {
            impulse += pointImpulse;
        }
        if (!(impulse > 0)) {
            return; // a speculative contact the solver won't act on: nothing to hear
        }
        ContactBody first = bodies.get(body1.getId());
        ContactBody second = bodies.get(body2.getId());
        // Only thrown Dice move, so at least one body is a Die; the event names it first.
        boolean dieFirst = first.kind() == BodyKind.DIE;
        events.add(new ContactEvent(time, dieFirst ? first : second, dieFirst ? second : first, impulse, point));
    }

    /**
     * Returns how fast two bodies close along the manifold's normal at a point, in cm/s: positive when they
     * approach. Jolt's normal points from body 1 towards body 2 (the way body 2 must move to separate).
     */
    private static float approachSpeed(Body body1, Body body2, Vec3 normal, Vector3 point) {
        float[] u1 = pointVelocity(body1, point);
        float[] u2 = pointVelocity(body2, point);
        return (u1[0] - u2[0]) * normal.getX() + (u1[1] - u2[1]) * normal.getY()
                + (u1[2] - u2[2]) * normal.getZ();
    }

    /** Returns a body's velocity at a world point: v + ω × (p - centre of mass). */
    private static float[] pointVelocity(Body body, Vector3 p) {
        Vec3 v = body.getLinearVelocity();
        Vec3 w = body.getAngularVelocity();
        RVec3 c = body.getCenterOfMassPosition();
        float rx = (float) (p.x() - c.xx());
        float ry = (float) (p.y() - c.yy());
        float rz = (float) (p.z() - c.zz());
        return new float[] {v.getX() + w.getY() * rz - w.getZ() * ry, v.getY() + w.getZ() * rx - w.getX() * rz,
                v.getZ() + w.getX() * ry - w.getY() * rx};
    }

    /** Sets the contact's combined friction and restitution from the materials and returns the material. */
    private ContactMaterial combine(ContactSettings settings, Body body1, Body body2) {
        ContactMaterial material = material(materials, bodies.get(body1.getId()).kind(),
                bodies.get(body2.getId()).kind());
        settings.setCombinedFriction(material.friction());
        settings.setCombinedRestitution(material.restitution());
        return material;
    }

    /**
     * Returns the material of a contact between two kinds of body: the floor's or the walls' if either is one,
     * else die-on-die, which covers a thrown Die against a Frozen Die too (ADR-0013).
     *
     * @param materials the Tray's materials
     * @param a one body's kind
     * @param b the other's
     * @return the contact's combined material
     */
    static ContactMaterial material(TrayMaterials materials, BodyKind a, BodyKind b) {
        if (a == BodyKind.FLOOR || b == BodyKind.FLOOR) {
            return materials.floor();
        }
        if (a == BodyKind.WALL || b == BodyKind.WALL) {
            return materials.walls();
        }
        return materials.dieOnDie();
    }

    private void fail(Throwable t) {
        if (failure == null) {
            failure = t;
        }
    }

    private static Vector3 vector(RVec3 v) {
        return new Vector3((float) v.xx(), (float) v.yy(), (float) v.zz());
    }

    /** A key for an unordered pair of body IDs. */
    private static long pairKey(int id1, int id2) {
        int low = Math.min(id1, id2);
        int high = Math.max(id1, id2);
        return ((long) high << 32) | (low & 0xFFFF_FFFFL);
    }
}
