package io.github.evildarkarchon.andysdice.render.numerals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.NumberingRotation;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.joml.Quaterniond;
import org.joml.Vector2dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The numeral atlas of every Die Kind, rasterised in pure Java and checked without GL (ADR-0005, ADR-0008). The
 * exhaustive test reads every face back through the atlas mapping and finds its face-table Numeral there; with
 * the face table (#30) and the Numbering Rotation (#31) each tested exhaustively, that proves the Numeral facing
 * up is the one in the Roll Result.
 */
class NumeralAtlasTest {

    /** Spec Appendix A, to the one decimal it gives: the reader matches at these heights, not the layout's. */
    private static final Map<DieKind, Double> APPENDIX_A_HEIGHT_MM = Map.of(
            DieKind.D4, 4.3, DieKind.D6, 11.6, DieKind.D8, 7.9, DieKind.D10, 7.6,
            DieKind.PERCENTILE_D10, 5.4, DieKind.D12, 7.2, DieKind.D20, 4.2);

    /** Each Die Kind's atlas and its readings, made once for the class: rasterising takes a moment. */
    private static final Map<DieKind, NumeralAtlas> ATLASES = new EnumMap<>(DieKind.class);
    private static final Map<DieKind, List<List<AtlasReader.Reading>>> READINGS = new EnumMap<>(DieKind.class);

    private static NumeralAtlas atlas(DieKind kind) {
        return ATLASES.computeIfAbsent(kind, k -> NumeralAtlas.rasterise(NumeralLayout.of(k)));
    }

    private static List<List<AtlasReader.Reading>> readings(DieKind kind) {
        return READINGS.computeIfAbsent(kind, k -> {
            AtlasReader reader = new AtlasReader(atlas(k), APPENDIX_A_HEIGHT_MM.get(k));
            List<List<AtlasReader.Reading>> out = new ArrayList<>();
            for (int f = 0; f < k.faceCount(); f++) {
                out.add(reader.read(f));
            }
            return out;
        });
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void theAtlasMappingPutsEachNumeralOnItsFaceTableFace(DieKind kind) {
        for (int f = 0; f < kind.faceCount(); f++) {
            List<AtlasReader.Reading> read = readings(kind).get(f);
            if (kind == DieKind.D4) {
                // Top-read: the face shows its three corners' Numerals, each at the corner of that label.
                assertThat(read).hasSize(3);
                for (AtlasReader.Reading r : read) {
                    int corner = Integer.parseInt(r.numeral()) - 1;
                    assertThat(corner).as("face %d does not carry its own name", f).isNotEqualTo(f);
                    Vector3d vertex = new Vector3d(kind.solid().faces().get(corner).normal()).mul(-3 * kind.inradius());
                    Vector3d toward = inPlane(vertex.sub(r.centre()), kind.solid().faces().get(f).normal());
                    assertThat(r.up().dot(toward)).as("d4 face %d: %s reads toward its corner", f, r.numeral())
                            .isGreaterThan(Math.cos(Math.toRadians(5)));
                    assertThat(r.underlined()).isFalse();
                    assertThat(r.match()).isGreaterThan(0.7);
                }
                assertThat(read).extracting(AtlasReader.Reading::numeral).doesNotHaveDuplicates();
            } else {
                assertThat(read).as("%s face %d shows one Numeral", kind, f).hasSize(1);
                AtlasReader.Reading r = read.getFirst();
                assertThat(r.numeral()).as("%s face %d", kind, f).isEqualTo(kind.numeral(f));
                boolean marked = kind != DieKind.D4 && kind != DieKind.PERCENTILE_D10
                        && (r.numeral().equals("6") || r.numeral().equals("9"));
                assertThat(r.underlined()).as("%s %s underlined", kind, r.numeral()).isEqualTo(marked);
                assertThat(r.match()).as("%s %s match", kind, r.numeral()).isGreaterThan(0.7);
                // The atlas shows the Numeral the way the layout turned it.
                Vector3dc laidOut = NumeralLayout.of(kind).faces().get(f).placements().getFirst().up();
                double agreement = r.up().dot(laidOut);
                // A running-track 0 (and so 00) looks the same turned half round: either way up reads right.
                if (r.numeral().matches("0+")) {
                    agreement = Math.abs(agreement);
                }
                assertThat(agreement).as("%s %s orientation", kind, r.numeral())
                        .isGreaterThan(Math.cos(Math.toRadians(5)));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void theNumeralFacingUpAfterTheNumberingRotationIsTheRollResult(DieKind kind) {
        for (int landed = 0; landed < kind.faceCount(); landed++) {
            // Physics lands the Die on some face, at some yaw: up on most kinds, on the table on the d4.
            Vector3dc toward = new Vector3d(0, kind == DieKind.D4 ? -1 : 1, 0);
            Quaterniond pose = new Quaterniond().rotationY(0.7 + landed)
                    .mul(new Quaterniond().rotationTo(kind.solid().faces().get(landed).normal(), toward));
            assertThat(UpFace.read(kind, pose).face()).isEqualTo(landed);
            for (int target = 0; target < kind.faceCount(); target++) {
                Quaterniond drawn = NumberingRotation.of(kind, landed, target).applyTo(pose, new Quaterniond());
                UpFace up = UpFace.read(kind, drawn);
                assertThat(seenOnTop(kind, up.face(), drawn)).as("%s landed %d, rolled %s", kind, landed,
                        kind.numeral(target)).isEqualTo(kind.numeral(target));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void inkStaysOnItsFlatFaceAndTheOtherCellsAreBlank(DieKind kind) {
        NumeralAtlas atlas = atlas(kind);
        NumeralLayout layout = atlas.layout();
        int size = atlas.sizePx();
        assertThat(size).isEqualTo(layout.grid() * NumeralAtlas.CELL_PX);
        double mmPerPx = layout.mmPerUv() / size;
        int[] inked = new int[layout.grid() * layout.grid()];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (atlas.profile(x, y) == 0) {
                    continue;
                }
                int column = x / NumeralAtlas.CELL_PX;
                int row = y / NumeralAtlas.CELL_PX;
                int cell = row * layout.grid() + column;
                inked[cell]++;
                assertThat(cell).as("%s pixel (%d, %d) in a face's cell", kind, x, y).isLessThan(kind.faceCount());
                // Back from pixel to face frame: the inverse of the atlas mapping.
                double s = ((x + 0.5) / NumeralAtlas.CELL_PX - column - 0.5) * 2 * layout.cellHalfMm();
                double t = -((y + 0.5) / NumeralAtlas.CELL_PX - row - 0.5) * 2 * layout.cellHalfMm();
                List<Vector2dc> polygon = layout.faces().get(cell).polygon();
                // Clear of the fillet by the margin, less the sloped wall and a pixel's rounding.
                assertThat(insetDistance(polygon, s, t)).as("%s face %d ink clear of the fillet", kind, cell)
                        .isGreaterThan(NumeralLayout.MARGIN_MM - 4 * NumeralAtlas.WALL_MM - 2 * mmPerPx);
            }
        }
        for (int f = 0; f < kind.faceCount(); f++) {
            assertThat(inked[f]).as("%s face %d has a Numeral", kind, f).isPositive();
        }
        assertThat(layout.blankCell()).isGreaterThanOrEqualTo(kind.faceCount());
    }

    @Test
    void inkFillsOnlyTheFloorAndLowerWallsOfARecess() {
        NumeralAtlas atlas = atlas(DieKind.D6);
        int size = atlas.sizePx();
        int full = 0;
        for (int y = 0; y < size; y += 3) {
            for (int x = 0; x < size; x += 3) {
                double p = atlas.profile(x, y);
                double ink = atlas.ink(x, y);
                if (p < 0.12) {
                    // Tumbling strips the paint from the face and the rim.
                    assertThat(ink).isZero();
                } else if (p >= 0.5) {
                    assertThat(ink).isEqualTo(1.0);
                    full++;
                }
            }
        }
        assertThat(full).isPositive();
    }

    @Test
    void recessWallsSlopeAcrossAboutFourteenHundredthsOfAMillimetre() {
        // Walk right along the middle row of the d6's 1 (face 0) and time the rise into its stem.
        NumeralAtlas atlas = atlas(DieKind.D6);
        NumeralLayout layout = atlas.layout();
        int y = NumeralAtlas.CELL_PX / 2;
        double mmPerPx = layout.mmPerUv() / atlas.sizePx();
        int lastFace = -1;
        int firstFloor = -1;
        double rise = 0;
        for (int x = 0; x < NumeralAtlas.CELL_PX; x++) {
            double p = atlas.profile(x, y);
            if (p == 0) {
                lastFace = x;
            } else if (lastFace >= 0) {
                rise += atlas.slopeU(x, y) * mmPerPx;
                if (p == 1) {
                    firstFloor = x;
                    break;
                }
            }
        }
        assertThat(firstFloor).as("a stem was crossed").isPositive();
        double wallMm = (firstFloor - lastFace) * mmPerPx;
        assertThat(wallMm).isBetween(0.06, 0.2);
        // The slopes are the profile's gradient in profile units per mm: summed across the wall, the rise.
        assertThat(rise).isCloseTo(1, within(0.25));
    }

    /** The Numeral a face shows nearest the top in the world: the only one, or the d4's highest corner. */
    private static String seenOnTop(DieKind kind, int upFace, Quaterniond drawn) {
        if (kind != DieKind.D4) {
            return readings(kind).get(upFace).getFirst().numeral();
        }
        // The d4's up face is on the table; its value is read at the top corner of the faces standing round it.
        String seen = null;
        for (int f = 0; f < kind.faceCount(); f++) {
            if (f == upFace) {
                continue;
            }
            AtlasReader.Reading top = readings(kind).get(f).stream()
                    .max((a, b) -> Double.compare(drawn.transform(a.centre(), new Vector3d()).y,
                            drawn.transform(b.centre(), new Vector3d()).y))
                    .orElseThrow();
            assertThat(seen == null || seen.equals(top.numeral())).as("all standing faces agree").isTrue();
            seen = top.numeral();
        }
        return seen;
    }

    private static double insetDistance(List<Vector2dc> polygon, double x, double y) {
        double least = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            Vector2dc p = polygon.get(i);
            Vector2dc q = polygon.get((i + 1) % polygon.size());
            double ex = q.x() - p.x();
            double ey = q.y() - p.y();
            least = Math.min(least, (ex * (y - p.y()) - ey * (x - p.x())) / Math.hypot(ex, ey));
        }
        return least;
    }

    private static Vector3d inPlane(Vector3dc d, Vector3dc n) {
        return new Vector3d(d).fma(-d.dot(n), n).normalize();
    }
}
