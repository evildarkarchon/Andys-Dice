package io.github.evildarkarchon.andysdice.physics;

import com.github.stephengold.joltjni.JoltPhysicsObject;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Frees an Attempt's native objects explicitly, newest first (ADR-0003: jolt-jni's cleaner thread isn't
 * started, so nothing else would free them).
 *
 * <p>Reverse order matters: Jolt keeps raw pointers to the layer filters, the contact listener and the
 * allocators a physics system was given, so the system must be destroyed before any of them.
 */
final class NativeScope implements AutoCloseable {

    private final Deque<Runnable> frees = new ArrayDeque<>();

    /**
     * Takes ownership of a jolt-jni object, to be closed with the scope.
     *
     * @param object the object; closing one that doesn't own its native object is harmless
     * @param <T> its type
     * @return the same object
     */
    <T extends JoltPhysicsObject> T own(T object) {
        frees.push(object::close);
        return object;
    }

    /**
     * Registers a freeing action that isn't a plain {@code close()}, such as tearing down a physics system.
     *
     * @param free the action
     */
    void onClose(Runnable free) {
        frees.push(free);
    }

    /** Runs every freeing action, newest first, even if one fails; the first failure is rethrown. */
    @Override
    public void close() {
        RuntimeException failure = null;
        while (!frees.isEmpty()) {
            try {
                frees.pop().run();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
