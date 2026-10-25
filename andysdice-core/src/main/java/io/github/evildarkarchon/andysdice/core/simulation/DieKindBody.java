package io.github.evildarkarchon.andysdice.core.simulation;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.joml.Quaterniond;
import org.joml.Vector3dc;

/**
 * A Die Kind's solid as the simulator sees it (ADR-0003: physics and rendering share one definition): the
 * sharp vertices of {@link DieKind#solid()} converted from millimetres to centimetres, the
 * {@linkplain DieKind#EDGE_RADIUS_MM 0.75 mm} edge radius, and {@link UpFace}'s reading and 4° cocked test.
 *
 * <p>The model frame is the face tables' z-up frame, unchanged, so an orientation from a Recording can be
 * handed straight to {@link UpFace#read} and the Numbering Rotation. Every solid is centred on its centre of
 * mass, as {@link DieBody} requires: each is the intersection of half-spaces at one inradius about the origin.
 *
 * <p>There is one shared, immutable instance per Die Kind ({@link #of}); the percentile d10 has its own,
 * though its solid is the d10's.
 */
public final class DieKindBody implements DieBody {

    private static final float CM_PER_MM = 0.1f;

    private static final Map<DieKind, DieKindBody> BY_KIND = build();

    private final DieKind kind;
    private final List<Vector3> vertices;

    private DieKindBody(DieKind kind) {
        this.kind = kind;
        this.vertices = kind.solid().vertices().stream()
                .map(v -> new Vector3((float) v.x() * CM_PER_MM, (float) v.y() * CM_PER_MM,
                        (float) v.z() * CM_PER_MM))
                .toList();
    }

    private static Map<DieKind, DieKindBody> build() {
        Map<DieKind, DieKindBody> out = new EnumMap<>(DieKind.class);
        for (DieKind kind : DieKind.values()) {
            out.put(kind, new DieKindBody(kind));
        }
        return out;
    }

    /**
     * Returns the body of a Die Kind.
     *
     * @param kind the Die Kind
     * @return its shared body
     */
    public static DieKindBody of(DieKind kind) {
        return BY_KIND.get(kind);
    }

    /**
     * Returns the Die Kind this is the body of.
     *
     * @return the Die Kind
     */
    public DieKind kind() {
        return kind;
    }

    @Override
    public List<Vector3> vertices() {
        return vertices;
    }

    @Override
    public float edgeRadius() {
        return (float) kind.edgeRadius() * CM_PER_MM;
    }

    @Override
    public boolean isCocked(Orientation orientation) {
        return read(orientation).cocked();
    }

    @Override
    public Vector3 readingNormal(Orientation orientation) {
        Vector3dc n = read(orientation).readingNormal();
        return new Vector3((float) n.x(), (float) n.y(), (float) n.z());
    }

    private UpFace read(Orientation orientation) {
        return UpFace.read(kind, new Quaterniond(orientation.x(), orientation.y(), orientation.z(), orientation.w()));
    }

    @Override
    public String toString() {
        return "DieKindBody[" + kind + "]";
    }
}
