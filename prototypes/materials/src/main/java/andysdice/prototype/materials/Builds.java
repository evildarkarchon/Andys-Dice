package andysdice.prototype.materials;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PROTOTYPE. Builds the seven {@link DieModel}s and their atlases off the FX and render threads. The
 * render thread polls {@link #latest()} and uploads whatever is new.
 *
 * <p>The numerals prototype's second phase (dense displaced meshes for real recessed geometry) is gone:
 * ADR-0008 locked parallax occlusion mapping, so this prototype only ever draws the coarse meshes.
 *
 * <p>A newer request supersedes an older one: a build checks the requested version between kinds and
 * drops its work if it has been overtaken, so mashing a key never queues up stale builds.
 */
final class Builds {
    /** One finished build. */
    record Built(Params.Structure st, List<DieModel> models, List<Atlas> atlases, long millis) {
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "materials-build");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Built> latest = new AtomicReference<>();
    private volatile int requested = -1;
    volatile String status = "";

    Built latest() {
        return latest.get();
    }

    void request(Params.Structure st) {
        if (st.version() == requested) return;
        requested = st.version();
        exec.submit(() -> build(st));
    }

    private void build(Params.Structure st) {
        try {
            long t0 = System.nanoTime();
            status = "building dice and atlases...";
            List<DieModel> models = new ArrayList<>();
            List<Atlas> atlases = new ArrayList<>();
            for (DieKind k : DieKind.values()) {
                if (st.version() != requested) return;
                DieModel m = new DieModel(k, st);
                models.add(m);
                atlases.add(new Atlas(m));
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            latest.set(new Built(st, models, atlases, ms));
            System.out.printf("build v%d: models+atlases %d ms%n", st.version(), ms);
            status = "";
        } catch (RuntimeException ex) {
            status = "BUILD FAILED: " + ex;
            ex.printStackTrace();
        }
    }
}
