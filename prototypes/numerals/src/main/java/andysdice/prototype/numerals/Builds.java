package andysdice.prototype.numerals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PROTOTYPE. Builds the seven {@link DieModel}s and their atlases off the FX and render threads, then
 * the dense displaced meshes for variant C as a second phase (they take a few seconds). The render
 * thread polls {@link #latest()} and uploads whatever is new.
 *
 * <p>A newer request supersedes an older one: a build checks the requested version between kinds and
 * drops its work if it has been overtaken, so mashing a key never queues up stale builds.
 */
final class Builds {
    /** One finished build. {@code dense} is null until phase two completes. */
    record Built(Params.Structure st, List<DieModel> models, List<Atlas> atlases, List<MeshData> dense, long millis) {
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "numerals-build");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Built> latest = new AtomicReference<>();
    private volatile int requested = -1;
    volatile String status = "";

    Built latest() {
        return latest.get();
    }

    /** Whether the newest finished build matches {@code version} (and has dense meshes, if asked). */
    boolean ready(int version, boolean needDense) {
        Built b = latest.get();
        return b != null && b.st().version() == version && (!needDense || b.dense() != null);
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
                if (st.version() == 0) {
                    // Sanity line: a d10 must come out with 12 vertices and 10 four-sided faces, and so on.
                    StringBuilder rings = new StringBuilder();
                    for (Solid.Face f : m.solid.faces) rings.append(f.ring().length);
                    System.out.printf("%-4s %2d vertices, face sizes %s, numeral height %.2f mm, cell %.2f mm%n",
                            k.label, m.solid.vertices.size(), rings, m.capHeightMm, 2 * m.cellHalfMm);
                }
                models.add(m);
                atlases.add(new Atlas(m));
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            latest.set(new Built(st, models, atlases, null, ms));
            System.out.printf("build v%d: models+atlases %d ms%n", st.version(), ms);

            status = "building dense meshes for C...";
            List<MeshData> dense = new ArrayList<>();
            int tris = 0;
            for (DieModel m : models) {
                if (st.version() != requested) return;
                MeshData d = m.buildMesh(true);
                tris += d.triangleCount();
                dense.add(d);
            }
            long total = (System.nanoTime() - t0) / 1_000_000;
            latest.set(new Built(st, models, atlases, dense, ms));
            System.out.printf("build v%d: dense meshes %d triangles, total %d ms%n", st.version(), tris, total);
            status = "";
        } catch (RuntimeException ex) {
            status = "BUILD FAILED: " + ex;
            ex.printStackTrace();
        }
    }
}
