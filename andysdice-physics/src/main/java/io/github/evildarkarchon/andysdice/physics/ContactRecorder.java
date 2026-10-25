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
 *   <li>It records a contact event for each new contact, with its impulse estimated by
 *       {@link MiscUtil#estimateCollisionResponse} before the solver runs (ADR-0003 as amended by ADR-0012).
 * </ul>
 *
 * <p>Jolt calls it from inside {@code PhysicsSystem.update}, on the physics worker, since the job system is
 * single-threaded. An exception must not unwind through Jolt's native frames, so each callback catches
 * everything and keeps the first failure for {@link #rethrowFailure()} after the step.
 */
final class ContactRecorder extends CustomContactListener {

    private final TrayMaterials materials;
    private final float minVelocity;
    private final CollisionEstimationResult estimate;
    private final Map<Integer, ContactBody> bodies = new HashMap<>();
    /** Contact points seen by onContactValidate in the current step, by body pair. */
    private final Map<Long, Vector3> points = new HashMap<>();
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
            ContactBody first = bodies.get(body1.getId());
            ContactBody second = bodies.get(body2.getId());
            ContactMaterial material = combine(new ContactSettings(settingsVa), first, second);

            ContactManifold manifold = new ContactManifold(manifoldVa);
            MiscUtil.estimateCollisionResponse(body1, body2, manifold, estimate, material.friction(),
                    material.restitution(), minVelocity);
            float impulse = 0;
            for (float pointImpulse : estimate.getContactImpulses()) {
                impulse += pointImpulse;
            }
            if (!(impulse > 0)) {
                return; // a speculative contact the solver won't act on: nothing to hear
            }
            Vector3 point = points.get(pairKey(body1.getId(), body2.getId()));
            if (point == null) {
                RVec3 base = manifold.getBaseOffset();
                point = new Vector3((float) base.xx(), (float) base.yy(), (float) base.zz());
            }
            // Only thrown Dice move, so at least one body is a Die; the event names it first.
            boolean dieFirst = first.kind() == BodyKind.DIE;
            events.add(new ContactEvent(time, dieFirst ? first : second, dieFirst ? second : first, impulse, point));
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void onContactPersisted(long body1Va, long body2Va, long manifoldVa, long settingsVa) {
        try {
            // Jolt recomputes the combined values for a persisted contact, so they are set again every step.
            combine(new ContactSettings(settingsVa), bodies.get(new Body(body1Va).getId()),
                    bodies.get(new Body(body2Va).getId()));
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Sets the contact's combined friction and restitution from the materials and returns the material. */
    private ContactMaterial combine(ContactSettings settings, ContactBody first, ContactBody second) {
        ContactMaterial material = material(first.kind(), second.kind());
        settings.setCombinedFriction(material.friction());
        settings.setCombinedRestitution(material.restitution());
        return material;
    }

    private ContactMaterial material(BodyKind a, BodyKind b) {
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

    /** A key for an unordered pair of body IDs. */
    private static long pairKey(int id1, int id2) {
        int low = Math.min(id1, id2);
        int high = Math.max(id1, id2);
        return ((long) high << 32) | (low & 0xFFFF_FFFFL);
    }
}
