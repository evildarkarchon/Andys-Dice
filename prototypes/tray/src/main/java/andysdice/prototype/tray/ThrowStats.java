package andysdice.prototype.tray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PROTOTYPE. Headless: throws the 20-Dice cap (and a 7-piece set) into each tray size, wall height,
 * surface and escape volume many times, and tabulates what it took: attempts per accepted Throw, why
 * attempts ended, time to rest, and pre-simulation wall time. No window; physics only.
 *
 * <p>Usage: {@code -Dtray.main=andysdice.prototype.tray.ThrowStats [-Dtray.runs=40]} (see README.md).
 * Writes {@code target/throw-stats.txt}.
 */
public final class ThrowStats {
    private ThrowStats() {
    }

    /** Runs the grid and writes the table. */
    public static void main(String[] args) throws IOException {
        Throws.loadNatives(Path.of(System.getProperty("tray.natives")));
        Throws sim = new Throws();
        int runs = Integer.getInteger("tray.runs", 40);
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%d Throws per row. Attempts = attempts per Throw (mean / max). Endings are over all attempts.", runs));
        lines.add(String.format(Locale.ROOT, "%-22s %-6s %-5s %-11s %-8s %-15s %-44s %-16s %s",
                "dice", "size", "wall", "surface", "escape", "attempts", "endings (all attempts)", "rest s (mean/max)", "pre-sim ms (mean/max)"));
        Params.Dice[] diceRows = {Params.DICE[5], Params.DICE[3]};
        for (Params.Dice dice : diceRows) {
            for (Params.Size size : Params.Size.values()) {
                for (float wall : new float[]{15, 30}) {
                    for (Params.Surface surface : new Params.Surface[]{Params.Surface.GREEN_FELT, Params.Surface.WOOD}) {
                        for (Params.Escape escape : Params.Escape.values()) {
                            if (dice != diceRows[0] && (wall != 30 || escape != Params.Escape.VISIBLE)) continue;
                            TrayGeom tray = new TrayGeom(Params.Shape.RECT, size, Params.WallMaterial.WOOD, wall, escape);
                            Map<Throws.Ending, Integer> endings = new EnumMap<>(Throws.Ending.class);
                            int attempts = 0, maxAttempts = 0, failed = 0;
                            double rest = 0, maxRest = 0;
                            long ms = 0, maxMs = 0;
                            for (int r = 0; r < runs; r++) {
                                Throws.Result res = sim.simulate(tray, surface, List.of(dice.kinds()), List.of(), List.of(), 1000 + r);
                                attempts += res.attempts();
                                maxAttempts = Math.max(maxAttempts, res.attempts());
                                res.endings().forEach((k, v) -> endings.merge(k, v, Integer::sum));
                                if (res.ending() != Throws.Ending.REST) failed++;
                                rest += res.rec().restTime();
                                maxRest = Math.max(maxRest, res.rec().restTime());
                                ms += res.wallMillis();
                                maxMs = Math.max(maxMs, res.wallMillis());
                            }
                            String line = String.format(Locale.ROOT, "%-22s %-6s %-5.0f %-11s %-8s %4.2f / %-2d %-3s %-44s %4.2f / %-9.2f %5.0f / %d",
                                    dice.expression().replaceAll(" \\(.*", ""), size.name(), wall, surface.name(), escape.name(),
                                    attempts / (double) runs, maxAttempts, failed > 0 ? "!" + failed : "", endings,
                                    rest / runs, maxRest, ms / (double) runs, maxMs);
                            lines.add(line);
                            System.out.println(line);
                        }
                    }
                }
            }
        }
        lines.add("'!n' = n Throws ran out of attempts (" + Throws.MAX_ATTEMPTS + ") without a flat rest.");
        Path out = Path.of("target", "throw-stats.txt");
        Files.write(out, lines);
        System.out.println("wrote " + out.toAbsolutePath());
    }
}
