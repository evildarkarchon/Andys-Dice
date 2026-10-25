package andysdice.prototype.rollui;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;

/**
 * PROTOTYPE app state shared by every variant, so flipping variants keeps the history and Saved Rolls. In
 * memory only (no persistence: ADR-0007 already settled the files). Seeded with fake Rolls, Saved Rolls, player
 * Dice Sets and skipped files so the panels have something to show.
 */
final class AppState {

    static final int HISTORY_CAP = 100;
    static final int SAVED_CAP = 100;

    record HistoryEntry(Instant at, String expression, String savedName, RollPlan plan) {
    }

    /** A Saved Roll; mutable so the editors can change it in place. */
    static final class SavedRoll {
        final StringProperty name = new SimpleStringProperty();
        final StringProperty expression = new SimpleStringProperty();

        SavedRoll(String name, String expression) {
            this.name.set(name);
            this.expression.set(expression);
        }

        /** Null when the expression parses; otherwise the #7 error (ADR-0007 keeps invalid ones, marked). */
        Expr.ParseError error() {
            return Expr.parse(expression.get()).error();
        }
    }

    record DiceSetInfo(String name, Color body, Color ink, boolean player) {
        @Override
        public String toString() {
            return player ? name + "  (yours)" : name;
        }
    }

    record TrayInfo(String name, Color floor, Color wall, boolean hex, boolean player) {
        @Override
        public String toString() {
            return player ? name + "  (yours)" : name;
        }
    }

    /** A player file that was skipped at start-up (ADR-0009, ADR-0010). */
    record Skipped(String file, String reason) {
    }

    /** A one-time notice that doesn't block (ADR-0007, ADR-0009, ADR-0010). */
    record Notice(String title, String detail) {
    }

    static final List<DiceSetInfo> DICE_SETS = List.of(
            new DiceSetInfo("Onyx", Color.web("#1d1d22"), Color.web("#e8e2d0"), false),
            new DiceSetInfo("Ivory", Color.web("#efe8d6"), Color.web("#2a2622"), false),
            new DiceSetInfo("Crimson", Color.web("#9e1b22"), Color.web("#f2e6c8"), false),
            new DiceSetInfo("Vortex Blue", Color.web("#1f4fa8"), Color.web("#f4f4f4"), false),
            new DiceSetInfo("Jade Marble", Color.web("#2e7d5b"), Color.web("#f0e9d2"), false),
            new DiceSetInfo("Speckled Stone", Color.web("#8a8378"), Color.web("#1d1b18"), false),
            new DiceSetInfo("Pearl Violet", Color.web("#7a4fa0"), Color.web("#f7f0ff"), false),
            new DiceSetInfo("Steel", Color.web("#a9adb3"), Color.web("#1c1f24"), false),
            new DiceSetInfo("Gold", Color.web("#c9a23a"), Color.web("#2a1e05"), false),
            new DiceSetInfo("Gunmetal", Color.web("#4a4e55"), Color.web("#d9b45a"), false),
            new DiceSetInfo("Smoke", Color.web("#6e6e78", 0.75), Color.web("#ffffff"), false),
            new DiceSetInfo("Ruby", Color.web("#c0142f", 0.8), Color.web("#fbe9c0"), false),
            new DiceSetInfo("Emerald", Color.web("#109a55", 0.8), Color.web("#f4f0d0"), false),
            new DiceSetInfo("Nebula", Color.web("#3b2a7a", 0.85), Color.web("#ffd27a"), false),
            new DiceSetInfo("my-galaxy", Color.web("#13163a"), Color.web("#9ef0ff"), true),
            new DiceSetInfo("copper-glitter", Color.web("#b5643a"), Color.web("#1a0e08"), true));

    static final List<Skipped> SKIPPED_DICE_SETS = List.of(
            new Skipped("Onyx.diceset", "its name clashes with the built-in Onyx"),
            new Skipped("glitter-bomb.diceset", "format 3 is newer than this app reads"));

    static final List<TrayInfo> TRAYS = List.of(
            new TrayInfo("Green felt in walnut", Color.web("#2f6b3c"), Color.web("#5a3a22"), false, false),
            new TrayInfo("Leather hexagon", Color.web("#6b4428"), Color.web("#3b2414"), true, false),
            new TrayInfo("Walnut board", Color.web("#6e4a2c"), Color.web("#4a2f1a"), false, false),
            new TrayInfo("blue-velvet", Color.web("#22306b"), Color.web("#202020"), false, true));

    static final List<String> LIGHT_RIGS = List.of("Studio", "Lamp", "Window");

    final ObservableList<HistoryEntry> history = FXCollections.observableArrayList();
    final ObservableList<SavedRoll> saved = FXCollections.observableArrayList(s -> new javafx.beans.Observable[] {s.name, s.expression});
    final ObservableList<Notice> notices = FXCollections.observableArrayList();

    final ObjectProperty<DiceSetInfo> diceSet = new SimpleObjectProperty<>(DICE_SETS.getFirst());
    final ObjectProperty<TrayInfo> tray = new SimpleObjectProperty<>(TRAYS.getFirst());
    final StringProperty lightRig = new SimpleStringProperty("Studio");
    final DoubleProperty renderScale = new SimpleDoubleProperty(1.0);
    final IntegerProperty msaa = new SimpleIntegerProperty(4);
    final StringProperty shadowQuality = new SimpleStringProperty("High");
    final BooleanProperty muted = new SimpleBooleanProperty(false);
    final DoubleProperty volume = new SimpleDoubleProperty(0.8);

    /** ADR-0001's one Face Value generator. Replaced with a fixed seed for the capture tour. */
    RandomGenerator rng = new SplittableRandom(new SecureRandom().nextLong());

    AppState() {
        saved.addAll(
                new SavedRoll("Longsword attack", "1d20+7"),
                new SavedRoll("Longsword damage", "1d8+4"),
                new SavedRoll("Sneak attack", "1d6+6d6"),
                new SavedRoll("Great weapon damage", "2d6ro<=2+5"),
                new SavedRoll("Ability scores", "4d6rr1kh3"),
                new SavedRoll("Fireball", "8d6"),
                new SavedRoll("Old homebrew", "3d7+2"));

        Instant now = Instant.now();
        SplittableRandom seedRng = new SplittableRandom(7);
        record Seed(String expr, String name, Duration ago) {
        }
        List<Seed> seeds = List.of(
                new Seed("1d20+7", "Longsword attack", Duration.ofDays(3).plusHours(2)),
                new Seed("1d8+4", "Longsword damage", Duration.ofDays(3).plusHours(2).minusMinutes(1)),
                new Seed("8d6", "Fireball", Duration.ofDays(3).plusHours(1)),
                new Seed("d%", null, Duration.ofDays(1).plusHours(4)),
                new Seed("4d6rr1kh3", "Ability scores", Duration.ofDays(1).plusHours(3)),
                new Seed("1d20+5 adv", null, Duration.ofDays(1).plusHours(3).minusMinutes(5)),
                new Seed("2d6ro<=2+5", "Great weapon damage", Duration.ofMinutes(42)),
                new Seed("1d20-1d4+3", null, Duration.ofMinutes(9)));
        for (Seed s : seeds) {
            try {
                RollPlan plan = RollPlan.roll(Expr.parse(s.expr()).ok(), seedRng);
                history.addFirst(new HistoryEntry(now.minus(s.ago()), s.expr(), s.name(), plan));
            } catch (RollPlan.RollFailed e) {
                // Seed data with tiny expressions never hits the reroll backstop; nothing to recover.
            }
        }

        for (Skipped sk : SKIPPED_DICE_SETS) {
            notices.add(new Notice("Skipped a Dice Set file", sk.file() + ": " + sk.reason() + "."));
        }
    }

    HistoryEntry addHistory(String expression, String savedName, RollPlan plan) {
        HistoryEntry e = new HistoryEntry(Instant.now(), expression, savedName, plan);
        history.addFirst(e);
        while (history.size() > HISTORY_CAP) {
            history.removeLast();
        }
        return e;
    }

    /**
     * ADR-0007's Saved Roll name rules: trimmed, 1-40 characters, unique ignoring case, no control characters.
     *
     * @param except the Saved Roll being edited (its own name doesn't clash), or null for a new one
     * @return null when valid, else the message to show
     */
    String nameProblem(String raw, SavedRoll except) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            return "Give the Saved Roll a name";
        }
        if (name.length() > 40) {
            return "A name is at most 40 characters (this is " + name.length() + ")";
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            return "A name can't contain control characters";
        }
        for (SavedRoll s : saved) {
            if (s != except && s.name.get().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return "There is already a Saved Roll called \"" + s.name.get() + "\"";
            }
        }
        if (except == null && saved.size() >= SAVED_CAP) {
            return "That's " + SAVED_CAP + " Saved Rolls, the most v1 keeps";
        }
        return null;
    }
}
