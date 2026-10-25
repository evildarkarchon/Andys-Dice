package andysdice.prototype.rollui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * PROTOTYPE of a whole Roll worked out before anything moves (ADR-0001, ADR-0006): every Die's chain of Face
 * Values, the Throws they need (the first Throw, then one Reroll Throw per round), and the Roll Result.
 */
final class RollPlan {

    /** ADR-0006's run-time backstop. */
    static final int MAX_REROLL_ROUNDS = 100;

    /** One die in the tray. A Percentile Roll's logical Die is two of these: the tens die and the units d10. */
    record PhysDie(int id, Expr.Kind shape, int logical, boolean tens) {
    }

    /** One logical Die: its Face Values oldest first (only the last counts), and whether its group kept it. */
    record DieResult(Expr.Kind kind, List<Integer> chain, boolean kept, List<Integer> physIds) {
        int value() {
            return chain.getLast();
        }

        boolean rerolled() {
            return chain.size() > 1;
        }
    }

    record TermResult(Expr.Term term, List<DieResult> dice, int subtotal) {
        String text() {
            return term.text();
        }

        int sign() {
            return term.sign();
        }
    }

    /** One Throw: the dice it throws, and the label every die in the tray shows once it is at rest. */
    record ThrowStep(List<Integer> thrown, Map<Integer, String> faces) {
    }

    static final class RollFailed extends Exception {
        RollFailed(String message) {
            super(message);
        }
    }

    final Expr.Parsed parsed;
    final List<PhysDie> phys;
    final List<TermResult> terms;
    final List<ThrowStep> steps;
    final int total;

    private RollPlan(Expr.Parsed parsed, List<PhysDie> phys, List<TermResult> terms, List<ThrowStep> steps, int total) {
        this.parsed = parsed;
        this.phys = phys;
        this.terms = terms;
        this.steps = steps;
        this.total = total;
    }

    String source() {
        return parsed.source();
    }

    /**
     * Rolls every Face Value with {@code rng} and works out the Throw chain.
     *
     * @throws RollFailed if the reroll backstop is exceeded; per ADR-0006 nothing is shown then
     */
    static RollPlan roll(Expr.Parsed parsed, RandomGenerator rng) throws RollFailed {
        record Logical(Expr.Group group, int termIndex, List<Integer> chain, List<Integer> physIds) {
        }
        List<Logical> dice = new ArrayList<>();
        List<PhysDie> phys = new ArrayList<>();
        for (int ti = 0; ti < parsed.terms().size(); ti++) {
            if (parsed.terms().get(ti) instanceof Expr.Group g) {
                for (int i = 0; i < g.count(); i++) {
                    int li = dice.size();
                    List<Integer> ids = new ArrayList<>();
                    if (g.kind() == Expr.Kind.D100) {
                        ids.add(phys.size());
                        phys.add(new PhysDie(phys.size(), Expr.Kind.D100, li, true));
                        ids.add(phys.size());
                        phys.add(new PhysDie(phys.size(), Expr.Kind.D10, li, false));
                    } else {
                        ids.add(phys.size());
                        phys.add(new PhysDie(phys.size(), g.kind(), li, false));
                    }
                    List<Integer> chain = new ArrayList<>();
                    chain.add(1 + rng.nextInt(g.kind().faces));
                    dice.add(new Logical(g, ti, chain, ids));
                }
            }
        }

        List<ThrowStep> steps = new ArrayList<>();
        steps.add(new ThrowStep(phys.stream().map(PhysDie::id).toList(), labels(phys, dice.stream().map(Logical::chain).toList())));
        for (int round = 1; ; round++) {
            List<Logical> again = new ArrayList<>();
            for (Logical d : dice) {
                Expr.Reroll r = d.group().reroll();
                if (r != null && (r.untilClear() || d.chain().size() == 1) && r.matches(d.chain().getLast())) {
                    again.add(d);
                }
            }
            if (again.isEmpty()) {
                break;
            }
            if (round > MAX_REROLL_ROUNDS) {
                throw new RollFailed("This Roll needed more than " + MAX_REROLL_ROUNDS + " reroll rounds, so nothing was rolled");
            }
            List<Integer> thrown = new ArrayList<>();
            for (Logical d : again) {
                d.chain().add(1 + rng.nextInt(d.group().kind().faces));
                thrown.addAll(d.physIds());
            }
            steps.add(new ThrowStep(thrown, labels(phys, dice.stream().map(Logical::chain).toList())));
        }

        List<TermResult> terms = new ArrayList<>();
        int total = 0;
        for (int ti = 0; ti < parsed.terms().size(); ti++) {
            Expr.Term t = parsed.terms().get(ti);
            if (t instanceof Expr.Modifier m) {
                terms.add(new TermResult(m, List.of(), m.value()));
                total += m.sign() * m.value();
                continue;
            }
            Expr.Group g = (Expr.Group) t;
            final int index = ti;
            List<Logical> mine = dice.stream().filter(d -> d.termIndex() == index).toList();
            boolean[] kept = new boolean[mine.size()];
            if (g.keepHighest() == null) {
                java.util.Arrays.fill(kept, true);
            } else {
                // Stable sort by final value: on a tie the earlier Die in the group is kept (#7, ADR-0006).
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < mine.size(); i++) {
                    order.add(i);
                }
                Comparator<Integer> byValue = Comparator.comparingInt(i -> mine.get(i).chain().getLast());
                order.sort(g.keepHighest() ? byValue.reversed() : byValue);
                for (int i = 0; i < g.keepN(); i++) {
                    kept[order.get(i)] = true;
                }
            }
            List<DieResult> results = new ArrayList<>();
            int sub = 0;
            for (int i = 0; i < mine.size(); i++) {
                Logical d = mine.get(i);
                results.add(new DieResult(g.kind(), List.copyOf(d.chain()), kept[i], List.copyOf(d.physIds())));
                if (kept[i]) {
                    sub += d.chain().getLast();
                }
            }
            terms.add(new TermResult(g, results, sub));
            total += g.sign() * sub;
        }
        return new RollPlan(parsed, List.copyOf(phys), List.copyOf(terms), List.copyOf(steps), total);
    }

    private static Map<Integer, String> labels(List<PhysDie> phys, List<List<Integer>> chains) {
        Map<Integer, String> out = new HashMap<>();
        for (PhysDie p : phys) {
            int v = chains.get(p.logical()).getLast();
            out.put(p.id(), faceLabel(p, v));
        }
        return out;
    }

    /** What a die physically shows: a d10 reads 0 for 10, and a Percentile Roll's 100 is 00 + 0. */
    static String faceLabel(PhysDie p, int value) {
        if (p.shape() == Expr.Kind.D100) {
            int tens = value == 100 ? 0 : (value / 10) * 10;
            return String.format("%02d", tens);
        }
        if (p.logical() >= 0 && p.shape() == Expr.Kind.D10 && !p.tens()) {
            return String.valueOf(value % 10);
        }
        return String.valueOf(value);
    }

    /** The history line's facts (ADR-0007): each Die's chain, Dice Group order, for example {@code 1>5,3,6,2}. */
    String facts() {
        return terms.stream().flatMap(t -> t.dice().stream())
                .map(d -> d.chain().stream().map(String::valueOf).collect(Collectors.joining(">")))
                .collect(Collectors.joining(","));
    }

    int throwCount() {
        return steps.size();
    }

    int rerolledDice() {
        return (int) terms.stream().flatMap(t -> t.dice().stream()).filter(DieResult::rerolled).count();
    }
}
