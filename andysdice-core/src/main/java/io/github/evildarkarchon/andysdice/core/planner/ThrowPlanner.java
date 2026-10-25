package io.github.evildarkarchon.andysdice.core.planner;

import io.github.evildarkarchon.andysdice.core.expression.DiceGroup;
import io.github.evildarkarchon.andysdice.core.expression.FaceValueChain;
import io.github.evildarkarchon.andysdice.core.expression.GroupKind;
import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollEvaluator;
import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValues;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.NumberingRotation;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.FrozenDie;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.joml.Quaterniond;

/**
 * The Throw planner: {@code core}'s Roll pipeline, the primary seam (spec §3.1). It parses a Roll Expression,
 * chooses every Face Value up front (ADR-0001), and pre-simulates the whole chain of Throws before anything
 * plays (ADR-0006, ADR-0014: no pipelining).
 *
 * <p>Not thread-safe: a planner, its simulator and the generators it is handed are used from one thread at a
 * time (the physics worker, in the app).
 */
public final class ThrowPlanner {

    /** Silent Attempts per Throw before it falls back on a Cocked Rethrow (spec §4: a contract, ADR-0013). */
    public static final int ATTEMPTS_PER_THROW = 24;

    /** Cocked Rethrows one Throw may need before the Roll fails (spec §4: a contract, ADR-0013). */
    public static final int COCKED_RETHROWS_PER_THROW = 3;

    /** The run-time backstop on reroll rounds per Roll Expression (spec §4: a contract, ADR-0006). */
    public static final int MAX_REROLL_ROUNDS = 100;

    private final Simulator simulator;
    private final TrayGeometry tray;
    private final TrayMaterials materials;

    /**
     * Creates a planner that Throws into one Tray through one simulator. Each Die is handed to the simulator as
     * its Die Kind's {@link DieKindBody}.
     *
     * @param simulator the simulator; every call is made from the thread {@link #plan} runs on
     * @param tray      the Tray's shape
     * @param materials the Tray's physics materials
     */
    public ThrowPlanner(Simulator simulator, TrayGeometry tray, TrayMaterials materials) {
        this.simulator = Objects.requireNonNull(simulator, "simulator");
        this.tray = Objects.requireNonNull(tray, "tray");
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    /**
     * Plans one Roll of a Roll Expression as typed.
     *
     * @param text       the Roll Expression as typed
     * @param faceValues the Face Value generator; only Face Values are drawn from it
     * @param releases   the Release generator, handed to the simulator for every Attempt
     * @return the parse error, the Failed Roll, or the planned Roll
     */
    public RollPlan plan(String text, FaceValueGenerator faceValues, RandomGenerator releases) {
        return switch (RollExpressionParser.parse(text)) {
            case ParseResult.Invalid(var error) -> new RollPlan.Invalid(error);
            case ParseResult.Valid(var expression) -> plan(expression, faceValues, releases);
        };
    }

    /**
     * Plans one Roll of a parsed Roll Expression.
     *
     * @param expression the Roll Expression
     * @param faceValues the Face Value generator; only Face Values are drawn from it
     * @param releases   the Release generator, handed to the simulator for every Attempt
     * @return the Failed Roll or the planned Roll
     */
    public RollPlan plan(RollExpression expression, FaceValueGenerator faceValues, RandomGenerator releases) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(faceValues, "faceValues");
        Objects.requireNonNull(releases, "releases");
        // Choose: every Face Value, every Reroll's next value included, before anything is simulated (ADR-0001),
        // so the Release generator and the retries can never shift one.
        Choice choice = choose(expression, faceValues);
        if (choice == null) {
            return new RollPlan.Failed(expression, FailureReason.TOO_MANY_REROLLS);
        }
        // Pre-simulate: the whole chain, before the plan is handed back to be played (ADR-0014: no pipelining).
        return new Simulation(choice, releases).run();
    }

    /**
     * The Face Values drawn so far for one roll of a Dice Group (one Die, or a Percentile Roll's pair), while they
     * are chosen. Not a Roll: in the glossary that is the whole Roll Expression's.
     */
    private record DieDraws(DiceGroup group, List<Integer> values) {

        /** Whether the roll's group rerolls it again after the values it has shown so far. */
        boolean rerollsAgain() {
            return group.reroll().map(rule -> rule.rerollsAgain(new FaceValueChain(values))).orElse(false);
        }
    }

    /**
     * Every Face Value of a Roll, and the physical Dice that will show them.
     *
     * @param rollKinds each roll's grammar kind, in roll order
     * @param rounds    for each reroll round, the rolls rerolled in it, in roll order
     */
    private record Choice(RollExpression expression, List<PlannedDie> dice, List<GroupKind> rollKinds,
            List<FaceValueChain> chains, List<List<Integer>> rounds) {
    }

    /**
     * Draws every roll's Face Values: each roll's first value in roll order, then round by round the next value
     * of every roll its group's reroll rule rerolls, in roll order.
     *
     * @return the choice, or {@code null} if the Roll needs more than {@link #MAX_REROLL_ROUNDS} rounds
     */
    private static Choice choose(RollExpression expression, FaceValueGenerator faceValues) {
        List<PlannedDie> dice = new ArrayList<>();
        List<GroupKind> rollKinds = new ArrayList<>();
        List<DieDraws> draws = new ArrayList<>();
        for (DiceGroup group : expression.diceGroups()) {
            for (int i = 0; i < group.count(); i++) {
                int roll = draws.size();
                if (group.kind() == GroupKind.PERCENTILE) {
                    dice.add(new PlannedDie(DieKind.PERCENTILE_D10, roll));
                    dice.add(new PlannedDie(DieKind.D10, roll));
                } else {
                    dice.add(new PlannedDie(dieKind(group.kind()), roll));
                }
                rollKinds.add(group.kind());
                draws.add(new DieDraws(group, new ArrayList<>(List.of(draw(group.kind(), faceValues)))));
            }
        }
        List<List<Integer>> rounds = new ArrayList<>();
        while (true) {
            List<Integer> again = IntStream.range(0, draws.size()).filter(r -> draws.get(r).rerollsAgain())
                    .boxed().toList();
            if (again.isEmpty()) {
                break;
            }
            if (rounds.size() == MAX_REROLL_ROUNDS) {
                return null;
            }
            for (int roll : again) {
                draws.get(roll).values().add(draw(draws.get(roll).group().kind(), faceValues));
            }
            rounds.add(again);
        }
        List<FaceValueChain> chains = draws.stream().map(r -> new FaceValueChain(r.values())).toList();
        return new Choice(expression, dice, rollKinds, chains, rounds);
    }

    /** One Face Value for a roll of {@code kind}: a Percentile Roll is one draw for both its Dice. */
    private static int draw(GroupKind kind, FaceValueGenerator faceValues) {
        return kind == GroupKind.PERCENTILE ? faceValues.percentileRoll() : faceValues.faceValue(dieKind(kind));
    }

    /** The pre-simulation of one Roll's chain of Throws. */
    private final class Simulation {

        private final Choice choice;
        private final RandomGenerator releases;
        private final List<PlannedThrow> throwChain = new ArrayList<>();
        /** Where each Die lies now: its final pose in the last Throw that moved it; null before the first. */
        private final Pose[] resting;

        Simulation(Choice choice, RandomGenerator releases) {
            this.choice = choice;
            this.releases = releases;
            this.resting = new Pose[choice.dice().size()];
        }

        /**
         * Simulates every Throw in order: the first Throw, then one Reroll Throw per round, each with any Cocked
         * Rethrows it needs. The Roll fails as a whole if any Throw does, so nothing of it is ever shown.
         */
        RollPlan run() {
            List<Integer> all = IntStream.range(0, choice.dice().size()).boxed().toList();
            if (!throwToRest(ThrowKind.FIRST, all, 0)) {
                return new RollPlan.Failed(choice.expression(), FailureReason.WOULDNT_SETTLE);
            }
            for (int round = 1; round <= choice.rounds().size(); round++) {
                Set<Integer> rerolled = Set.copyOf(choice.rounds().get(round - 1));
                // Every Die of every roll rerolled this round, across every Dice Group, goes in one Reroll Throw.
                List<Integer> thrown = all.stream().filter(die -> rerolled.contains(choice.dice().get(die).roll()))
                        .toList();
                if (!throwToRest(ThrowKind.REROLL, thrown, round)) {
                    return new RollPlan.Failed(choice.expression(), FailureReason.WOULDNT_SETTLE);
                }
            }
            return new RollPlan.Planned(choice.expression(), choice.dice(), choice.chains(), throwChain,
                    RollEvaluator.evaluate(choice.expression(), choice.chains()));
        }

        /**
         * Brings one Throw to rest, adding it and any Cocked Rethrows it needs to the chain: up to
         * {@link #ATTEMPTS_PER_THROW} silent Attempts, then, if every one failed, the Attempt at rest with the fewest
         * Cocked Dice is kept and only those Dice are thrown again, up to {@link #COCKED_RETHROWS_PER_THROW} times.
         *
         * @param kind   why the Throw is made
         * @param thrown the Dice to throw, in Die order
         * @param step   which value of each Die's chain the Throw shows: 0 for the first Throw, n for round n
         * @return {@code false} if the Dice wouldn't settle: an Attempt series had nothing at rest, or the last
         *         Cocked Rethrow still left a Die cocked
         */
        private boolean throwToRest(ThrowKind kind, List<Integer> thrown, int step) {
            List<Integer> toThrow = thrown;
            ThrowKind throwKind = kind;
            for (int rethrows = 0; ; rethrows++) {
                Attempts outcome = attempts(toThrow);
                if (outcome.kept() == null) {
                    return false;
                }
                Set<Integer> cocked = outcome.kept().cockedDice().stream().map(toThrow::get)
                        .collect(Collectors.toUnmodifiableSet());
                if (!cocked.isEmpty() && rethrows == COCKED_RETHROWS_PER_THROW) {
                    return false;
                }
                accept(throwKind, toThrow, outcome, step, cocked);
                if (cocked.isEmpty()) {
                    return true;
                }
                // A Cocked Rethrow: only the Cocked Dice, still showing this Throw's values, the rest frozen.
                toThrow = toThrow.stream().filter(cocked::contains).toList();
                throwKind = ThrowKind.COCKED_RETHROW;
            }
        }

        /**
         * The Attempts of one Throw: the one it keeps, and how many were simulated.
         *
         * @param kept  the first Attempt at rest; else the cocked one with the fewest Cocked Dice, the earliest
         *              on a tie; {@code null} if none came to rest
         * @param count how many Attempts were simulated
         */
        private record Attempts(Attempt kept, int count) {
        }

        /**
         * Simulates up to {@link #ATTEMPTS_PER_THROW} Attempts of one Throw, stopping at the first at rest. An
         * Attempt that escaped or timed out is only ever simulated again, never kept (ADR-0013).
         */
        private Attempts attempts(List<Integer> thrown) {
            Set<Integer> moving = Set.copyOf(thrown);
            List<FrozenDie> frozen = new ArrayList<>();
            for (int die = 0; die < choice.dice().size(); die++) {
                if (!moving.contains(die)) {
                    frozen.add(new FrozenDie(body(choice.dice().get(die).kind()), resting[die]));
                }
            }
            // One request for every Attempt: the Drawn Release advances the Release generator each time, so
            // each Attempt gets its own Release.
            AttemptRequest request = new AttemptRequest(
                    thrown.stream().map(die -> body(choice.dice().get(die).kind())).toList(),
                    new Release.Drawn(releases), frozen, tray, materials);
            Attempt best = null;
            for (int count = 1; count <= ATTEMPTS_PER_THROW; count++) {
                Attempt attempt = simulator.simulate(request);
                switch (attempt.ending()) {
                    case AT_REST -> {
                        return new Attempts(attempt, count);
                    }
                    case COCKED -> {
                        if (best == null || attempt.cockedDice().size() < best.cockedDice().size()) {
                            best = attempt;
                        }
                    }
                    case ESCAPED, TIMED_OUT -> {
                        // Never shown and never kept: the next Attempt simply simulates the Throw again.
                    }
                }
            }
            return new Attempts(best, ATTEMPTS_PER_THROW);
        }

        /** Adds a kept Attempt to the chain as a Throw, and leaves its Dice lying where it ended. */
        private void accept(ThrowKind kind, List<Integer> thrown, Attempts outcome, int step, Set<Integer> cocked) {
            Set<Integer> moving = Set.copyOf(thrown);
            List<Integer> frozen = IntStream.range(0, choice.dice().size()).filter(die -> !moving.contains(die))
                    .boxed().toList();
            Attempt attempt = outcome.kept();
            throwChain.add(new PlannedThrow(kind, thrown, frozen, attempt.recording(),
                    rotations(thrown, attempt, step), outcome.count(), cocked));
            for (int slot = 0; slot < thrown.size(); slot++) {
                resting[thrown.get(slot)] = attempt.recording().finalPose(slot);
            }
        }

        /** Each thrown Die's Numbering Rotation for the value its chain shows at {@code step}. */
        private List<NumberingRotation> rotations(List<Integer> thrown, Attempt attempt, int step) {
            List<NumberingRotation> rotations = new ArrayList<>();
            for (int slot = 0; slot < thrown.size(); slot++) {
                PlannedDie die = choice.dice().get(thrown.get(slot));
                int value = choice.chains().get(die.roll()).values().get(step);
                Orientation o = attempt.recording().finalPose(slot).orientation();
                UpFace landed = UpFace.read(die.kind(), new Quaterniond(o.x(), o.y(), o.z(), o.w()));
                rotations.add(NumberingRotation.of(landed,
                        targetFace(die.kind(), choice.rollKinds().get(die.roll()), value)));
            }
            return rotations;
        }
    }

    /**
     * The face that shows a roll's value on one of its Dice: a Percentile Roll's percentile d10 shows the tens and
     * its d10 the units; any other Die shows the value itself.
     */
    private static int targetFace(DieKind kind, GroupKind rollKind, int value) {
        if (rollKind == GroupKind.PERCENTILE) {
            return kind == DieKind.PERCENTILE_D10
                    ? FaceValues.percentileTensFace(value)
                    : FaceValues.percentileUnitsFace(value);
        }
        return FaceValues.face(kind, value);
    }

    private static DieBody body(DieKind kind) {
        return DieKindBody.of(kind);
    }

    /** The solid a Dice Group of one grammar kind throws; a Percentile Roll is handled apart, as two Dice. */
    private static DieKind dieKind(GroupKind kind) {
        return switch (kind) {
            case D4 -> DieKind.D4;
            case D6 -> DieKind.D6;
            case D8 -> DieKind.D8;
            case D10 -> DieKind.D10;
            case D12 -> DieKind.D12;
            case D20 -> DieKind.D20;
            case PERCENTILE -> throw new IllegalArgumentException("a Percentile Roll is two Dice, not one");
        };
    }
}
