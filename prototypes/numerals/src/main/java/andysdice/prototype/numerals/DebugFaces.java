package andysdice.prototype.numerals;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * PROTOTYPE debugging aid: prints, for one die at rest, each face's world normal and the world
 * direction of its numeral's top, so orientation can be checked without squinting at screenshots.
 * Usage: java -cp ... DebugFaces D10 9
 */
final class DebugFaces {
    private DebugFaces() {
    }

    public static void main(String[] args) {
        DieKind kind = DieKind.valueOf(args[0]);
        DieModel m = new DieModel(kind, new Params().structure());
        Quaternionf q = m.restRotation(args[1], 0);
        for (DieModel.FaceLayout l : m.layouts) {
            Vector3f n = q.transform(new Vector3f(l.n));
            Vector3f up = q.transform(new Vector3f(l.up));
            Vector3f right = q.transform(new Vector3f(l.right));
            if (n.y < -0.2f) continue;
            System.out.printf("%3s n=(%5.2f %5.2f %5.2f) up=(%5.2f %5.2f %5.2f) right=(%5.2f %5.2f %5.2f) %s%n", l.label,
                    n.x, n.y, n.z, up.x, up.y, up.z, right.x, right.y, right.z, l.orientNote);
        }
    }
}
