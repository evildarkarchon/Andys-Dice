package andysdice.prototype.tray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PROTOTYPE for "How does a 20-Dice Throw reach a flat rest?" (issue #21). Headless: throws the 20-Dice
 * cap into ADR-0010's three built-in Trays (300 x 200 mm, 30 mm walls, visible-wall escape) under each
 * set of {@link Throws.Knobs}, and reports what it takes to reach a flat rest under three policies:
 *
 * <ul>
 *   <li><b>whole</b>: ADR-0001 as written. Any non-flat attempt re-simulates the whole Throw. One run is
 *       scored at several Cocked Die tolerances: an attempt that is flat at 4 degrees is flat at every
 *       wider one, so stopping at the first 4-degree success observes every tolerance's first success.</li>
 *   <li><b>cocked-only</b>: a Throw that rests with Cocked Dice keeps its flat dice frozen and re-throws
 *       only the cocked ones, round after round (a visible re-throw, like a table's "that one's cocked").
 *       Escaped or timed-out rounds are re-simulated silently.</li>
 *   <li><b>reroll</b>: ADR-0006's Reroll Throw. k dice thrown again among 20 - k frozen at rest, with
 *       whole-Throw retries of that Reroll Throw.</li>
 * </ul>
 *
 * <p>Options (system properties): {@code flat.runs} (Throws per row, default 300), {@code flat.knobs}
 * (comma-separated names from {@link #KNOBS}, default all), {@code flat.policies} (default
 * {@code whole,cocked-only,reroll,count}), {@code flat.out} (default {@code target/flat-rest.txt}).
 */
public final class FlatRest {
    private FlatRest() {
    }

    /** Tolerances the whole-Throw policy is scored at, in degrees. */
    static final float[] TOLERANCES = {4, 6, 8, 10, 15};
    /** Attempt caps the whole-Throw policy reports failures at; the largest is also the run's ceiling. */
    static final int[] CAPS = {12, 24, 48};

    /** The three built-in Trays of ADR-0010, refitted to the footprint and 30 mm walls. */
    record Tray(String name, TrayGeom geom, Params.Surface surface) {
    }

    static final List<Tray> TRAYS = List.of(
            new Tray("felt/walnut rect", new TrayGeom(Params.Shape.RECT, Params.Size.M, Params.WallMaterial.WOOD, 30, Params.Escape.VISIBLE),
                    Params.Surface.GREEN_FELT),
            new Tray("leather/padded hex", new TrayGeom(Params.Shape.HEX, Params.Size.M, Params.WallMaterial.PADDED, 30, Params.Escape.VISIBLE),
                    Params.Surface.LEATHER),
            new Tray("walnut/walnut rect", new TrayGeom(Params.Shape.RECT, Params.Size.M, Params.WallMaterial.WOOD, 30, Params.Escape.VISIBLE),
                    Params.Surface.WOOD));

    static final Map<String, Throws.Knobs> KNOBS = new LinkedHashMap<>();

    static {
        Throws.Knobs b = Throws.Knobs.BASE;
        for (Throws.Knobs k : List.of(
                b,
                b.with("spread", Throws.Release.SPREAD),
                b.with("gentle", Throws.Release.GENTLE),
                b.with("spread+gentle", Throws.Release.SPREAD_GENTLE),
                b.friction("dieF 0.2", 0.2f),
                b.friction("dieF 0.1", 0.1f),
                b.sleep("sleep strict", 0.2f, 0.6f),
                b.nudging("nudge", 3, 4f),
                b.with("x", Throws.Release.SPREAD_GENTLE).friction("s+g dieF 0.2", 0.2f),
                b.with("x", Throws.Release.SPREAD_GENTLE).nudging("s+g nudge", 3, 4f),
                b.with("x", Throws.Release.SPREAD_GENTLE).friction("x", 0.2f).nudging("combo", 3, 4f))) {
            KNOBS.put(k.name(), k);
        }
    }

    static final List<DieKind> CAP = List.of(Params.DICE[5].kinds());

    /** Runs the selected policies and knobs and writes the report. */
    public static void main(String[] args) throws IOException {
        Throws.loadNatives(Path.of(System.getProperty("tray.natives")));
        Throws sim = new Throws();
        int runs = Integer.parseInt(prop("flat.runs", "300"));
        List<String> knobNames = List.of(prop("flat.knobs", String.join(",", KNOBS.keySet())).split(","));
        List<String> policies = List.of(prop("flat.policies", "whole,cocked-only,reroll,count").split(","));
        Report out = new Report();
        out.add("Issue #21 flat-rest measurements. %d Throws of the 20-Dice cap per row, seeds 1000..%d.", runs, 1000 + runs - 1);
        out.add("Trays: ADR-0010's three built-ins, 300 x 200 mm, 30 mm walls, escape = visible walls.");

        if (policies.contains("whole")) whole(sim, runs, knobNames, out);
        if (policies.contains("cocked-only")) cockedOnly(sim, runs, knobNames, out);
        if (policies.contains("reroll")) reroll(sim, runs, knobNames, out);
        if (policies.contains("count")) count(sim, runs, knobNames, out);

        Path file = Path.of(prop("flat.out", "target/flat-rest.txt"));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, out.lines);
        System.out.println("wrote " + file.toAbsolutePath());
    }

    /** Collects report lines and echoes them as they come, so a long run shows progress. */
    static final class Report {
        final List<String> lines = new ArrayList<>();

        void add(String fmt, Object... args) {
            String s = String.format(Locale.ROOT, fmt, args);
            lines.add(s);
            System.out.println(s);
        }
    }

    /** Whole-Throw retries, scored at every tolerance in {@link #TOLERANCES}. */
    static void whole(Throws sim, int runs, List<String> knobNames, Report out) {
        int ceiling = CAPS[CAPS.length - 1];
        out.add("");
        out.add("== whole: re-simulate the whole Throw until flat (ADR-0001 as written), ceiling %d attempts ==", ceiling);
        out.add("attempts @T = mean / p95 / max attempts to the first attempt flat within T degrees; p = per-attempt flat rate;");
        out.add("fail@c = Throws (of %d) with no flat attempt in the first c; est = (1-p)^c, the failure rate extrapolated.", runs);
        out.add("cocked dice = over attempts that came to rest: share of cocked dice by tilt band and by what they lean on.");
        for (Tray tray : TRAYS) {
            out.add("");
            out.add("-- %s --", tray.name);
            for (String kn : knobNames) {
                Throws.Knobs knobs = KNOBS.get(kn);
                int[][] need = new int[TOLERANCES.length][runs];
                Map<Throws.Ending, Integer> physics = new EnumMap<>(Throws.Ending.class);
                int[] band = new int[6];
                Map<Throws.Lean, Integer> lean = new EnumMap<>(Throws.Lean.class);
                int cockedDice = 0, restAttempts = 0, stacked = 0, nudges = 0, attempts = 0;
                double restTime = 0;
                long ms = 0;
                for (int r = 0; r < runs; r++) {
                    for (int t = 0; t < TOLERANCES.length; t++) need[t][r] = ceiling + 1;
                    long t0 = System.nanoTime();
                    for (int n = 1; n <= ceiling; n++) {
                        Throws.Attempt a = sim.attempt(tray.geom, tray.surface, knobs, CAP, List.of(), List.of(), Throws.attemptSeed(1000 + r, n));
                        attempts++;
                        nudges += a.nudges();
                        physics.merge(a.physics(), 1, Integer::sum);
                        if (a.physics() == Throws.Ending.REST) {
                            restAttempts++;
                            for (int i = 0; i < a.tilt().length; i++) {
                                if (a.stacked()[i]) stacked++;
                                float tl = a.tilt()[i];
                                if (tl <= 4) continue;
                                cockedDice++;
                                band[tl <= 6 ? 0 : tl <= 8 ? 1 : tl <= 10 ? 2 : tl <= 15 ? 3 : tl <= 25 ? 4 : 5]++;
                                lean.merge(a.lean()[i], 1, Integer::sum);
                            }
                        }
                        for (int t = 0; t < TOLERANCES.length; t++) {
                            if (need[t][r] > ceiling && a.ending(TOLERANCES[t]) == Throws.Ending.REST) need[t][r] = n;
                        }
                        if (need[0][r] <= ceiling) {
                            restTime += a.rec().restTime();
                            break;
                        }
                    }
                    ms += (System.nanoTime() - t0) / 1_000_000;
                }
                out.add("%-14s attempts/Throw %.2f, %.1f ms/attempt, rest %.2f s, nudges/attempt %.2f, physics endings %s",
                        kn, attempts / (double) runs, ms / (double) attempts, restTime / runs, nudges / (double) attempts, physics);
                for (int t = 0; t < TOLERANCES.length; t++) {
                    int[] s = need[t].clone();
                    Arrays.sort(s);
                    long sum = 0;
                    for (int v : s) sum += Math.min(v, ceiling);
                    double p = (runs - count(s, ceiling + 1)) / (double) sum;
                    StringBuilder fails = new StringBuilder();
                    for (int c : CAPS) {
                        int f = 0;
                        for (int v : s) if (v > c) f++;
                        fails.append(String.format(Locale.ROOT, "  fail@%d %3d est %.1e", c, f, Math.pow(1 - p, c)));
                    }
                    out.add("    @%2.0f deg  attempts %5.2f / %2d / %2s   p %.3f%s", TOLERANCES[t],
                            mean(s, ceiling), s[(int) Math.ceil(runs * 0.95) - 1], s[runs - 1] > ceiling ? ">" + ceiling : s[runs - 1], p, fails);
                }
                out.add("    cocked dice per resting attempt %.2f; tilt 4-6 %s, 6-8 %s, 8-10 %s, 10-15 %s, 15-25 %s, >25 %s; lean %s; stacked flat %.2f%% of dice",
                        cockedDice / (double) Math.max(1, restAttempts), pct(band[0], cockedDice), pct(band[1], cockedDice), pct(band[2], cockedDice),
                        pct(band[3], cockedDice), pct(band[4], cockedDice), pct(band[5], cockedDice), shares(lean, cockedDice),
                        100.0 * stacked / Math.max(1, restAttempts * CAP.size()));
            }
        }
    }

    /**
     * Cocked-only re-throws at 4 degrees: flat dice freeze where they lie and only the cocked ones are
     * thrown again. Reports visible re-throw rounds (what the player watches) and silent attempts.
     */
    static void cockedOnly(Throws sim, int runs, List<String> knobNames, Report out) {
        int ceiling = 48;
        out.add("");
        out.add("== cocked-only: freeze the flat dice, re-throw only the cocked ones (visible), at 4 deg; ceiling %d attempts ==", ceiling);
        out.add("visible = re-throw rounds the player watches after the first landing (mean / p95 / max); dice = dice per re-throw;");
        out.add("silent = attempts re-simulated unseen (escapes, time-outs) per Throw; fail = Throws (of %d) that hit the ceiling.", runs);
        for (Tray tray : TRAYS) {
            out.add("");
            out.add("-- %s --", tray.name);
            for (String kn : knobNames) {
                Throws.Knobs knobs = KNOBS.get(kn);
                int[] visible = new int[runs];
                int silent = 0, fail = 0, rethrownDice = 0, rounds = 0;
                for (int r = 0; r < runs; r++) {
                    List<DieKind> frozenKinds = new ArrayList<>(), moving = new ArrayList<>(CAP);
                    List<Throws.Pose> frozen = new ArrayList<>();
                    int n = 0;
                    boolean done = false;
                    while (n < ceiling) {
                        n++;
                        Throws.Attempt a = sim.attempt(tray.geom, tray.surface, knobs, moving, frozenKinds, frozen, Throws.attemptSeed(1000 + r, n));
                        if (a.physics() != Throws.Ending.REST) {
                            silent++;
                            continue;
                        }
                        List<Throws.Pose> poses = a.rec().last();
                        List<DieKind> cocked = new ArrayList<>();
                        for (int i = 0; i < moving.size(); i++) {
                            if (a.tilt()[i] > 4) {
                                cocked.add(moving.get(i));
                            } else {
                                frozenKinds.add(moving.get(i));
                                frozen.add(poses.get(i));
                            }
                        }
                        if (cocked.isEmpty()) {
                            done = true;
                            break;
                        }
                        visible[r]++;
                        rounds++;
                        rethrownDice += cocked.size();
                        moving = cocked;
                    }
                    if (!done) fail++;
                }
                int[] s = visible.clone();
                Arrays.sort(s);
                out.add("%-14s visible %.2f / %d / %d, dice %.2f, Throws with any re-throw %s, silent %.2f, fail %d",
                        kn, mean(s, Integer.MAX_VALUE), s[(int) Math.ceil(runs * 0.95) - 1], s[runs - 1],
                        rethrownDice / (double) Math.max(1, rounds), pct(runs - count(s, 0), runs), silent / (double) runs, fail);
            }
        }
    }

    /**
     * ADR-0006 Reroll Throws: from each Throw's flat rest, k dice (the first k, a mix of kinds) are thrown
     * again among the other 20 - k frozen, with whole-Throw retries of the Reroll Throw at 4 degrees.
     */
    static void reroll(Throws sim, int runs, List<String> knobNames, Report out) {
        int ceiling = 48;
        out.add("");
        out.add("== reroll: ADR-0006 Reroll Throw of k dice among 20 - k frozen at rest, whole retries at 4 deg ==");
        out.add("attempts = mean / max attempts for the Reroll Throw; fail = Reroll Throws (of those with a flat start) hitting %d.", ceiling);
        for (Tray tray : TRAYS) {
            out.add("");
            out.add("-- %s --", tray.name);
            for (String kn : knobNames) {
                Throws.Knobs knobs = KNOBS.get(kn);
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-14s", kn));
                for (int k : new int[]{1, 4, 10}) {
                    int total = 0, max = 0, fail = 0, tried = 0;
                    for (int r = 0; r < runs; r++) {
                        Throws.Attempt start = null;
                        for (int n = 1; n <= ceiling && start == null; n++) {
                            Throws.Attempt a = sim.attempt(tray.geom, tray.surface, knobs, CAP, List.of(), List.of(), Throws.attemptSeed(1000 + r, n));
                            if (a.ending(4) == Throws.Ending.REST) start = a;
                        }
                        if (start == null) continue;
                        tried++;
                        List<Throws.Pose> poses = start.rec().last();
                        List<DieKind> moving = CAP.subList(0, k), frozenKinds = CAP.subList(k, CAP.size());
                        List<Throws.Pose> frozen = poses.subList(k, poses.size());
                        int n = 0;
                        boolean ok = false;
                        while (n < ceiling && !ok) {
                            n++;
                            ok = sim.attempt(tray.geom, tray.surface, knobs, moving, frozenKinds, frozen, Throws.attemptSeed(50_000 + r, n))
                                    .ending(4) == Throws.Ending.REST;
                        }
                        if (!ok) fail++;
                        total += n;
                        max = Math.max(max, n);
                    }
                    line.append(String.format(Locale.ROOT, "   k=%-2d attempts %5.2f / %2d fail %d", k, total / (double) Math.max(1, tried), max, fail));
                }
                out.add("%s", line);
            }
        }
    }

    /** Attempts per Throw at 4 degrees against the number of dice, on the default Tray only. */
    static void count(Throws sim, int runs, List<String> knobNames, Report out) {
        int ceiling = 48;
        Tray tray = TRAYS.getFirst();
        out.add("");
        out.add("== count: whole retries at 4 deg vs number of dice (first n of the 20-Dice mix), %s ==", tray.name);
        for (String kn : knobNames) {
            Throws.Knobs knobs = KNOBS.get(kn);
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-14s", kn));
            for (int k : new int[]{2, 5, 10, 15, 20, 25}) {
                List<DieKind> dice = new ArrayList<>();
                for (int i = 0; i < k; i++) dice.add(CAP.get(i % CAP.size()));
                long sum = 0;
                int fail = 0;
                for (int r = 0; r < runs; r++) {
                    int n = 0;
                    boolean ok = false;
                    while (n < ceiling && !ok) {
                        n++;
                        ok = sim.attempt(tray.geom, tray.surface, knobs, dice, List.of(), List.of(), Throws.attemptSeed(1000 + r, n))
                                .ending(4) == Throws.Ending.REST;
                    }
                    sum += n;
                    if (!ok) fail++;
                }
                double p = (runs - fail) / (double) sum;
                line.append(String.format(Locale.ROOT, "   n=%-2d %5.2f (p %.2f, fail@12 est %.0e)", k, sum / (double) runs, p, Math.pow(1 - p, 12)));
            }
            out.add("%s", line);
        }
    }

    /** A system property, or {@code def} when it is unset or blank (the pom passes every option, empty by default). */
    private static String prop(String key, String def) {
        String v = System.getProperty(key, "");
        return v.isBlank() ? def : v;
    }

    private static int count(int[] sorted, int value) {
        int c = 0;
        for (int v : sorted) if (v == value) c++;
        return c;
    }

    private static double mean(int[] values, int cap) {
        double s = 0;
        for (int v : values) s += Math.min(v, cap);
        return s / values.length;
    }

    private static String pct(int n, int of) {
        return String.format(Locale.ROOT, "%.0f%%", 100.0 * n / Math.max(1, of));
    }

    private static String shares(Map<Throws.Lean, Integer> m, int of) {
        StringBuilder sb = new StringBuilder();
        for (Throws.Lean l : Throws.Lean.values()) sb.append(l.name().toLowerCase(Locale.ROOT)).append(' ').append(pct(m.getOrDefault(l, 0), of)).append(' ');
        return sb.toString().trim();
    }
}
