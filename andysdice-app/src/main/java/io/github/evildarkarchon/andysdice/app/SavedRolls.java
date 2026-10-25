package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.ParseError;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Saved Rolls (spec §3.6 "Saved Rolls"): a flat list in the Player's own order, new ones at the end, at most
 * {@value #CAP}, as an immutable value. {@link #refusal} holds the rules a Saved Roll must meet to be saved: a name
 * trimmed, 1-{@value #NAME_MAX} characters, unique ignoring case and free of control characters, and an expression
 * that parses.
 *
 * <p>{@link #FILE} is {@code saved-rolls.txt}: the version header, then one {@code name⇥expression} line per Saved
 * Roll in the Player's order (ADR-0007). Neither a name nor the grammar can hold a tab or any other control character,
 * so nothing is escaped. A Saved Roll whose expression no longer parses is kept as it is; a file whose names break the
 * rules, or that holds more than {@value #CAP}, isn't one this build wrote, so it is set aside whole rather than
 * losing any line of it at the next write.
 *
 * @param rolls the Saved Rolls in the Player's order; at most {@value #CAP}
 */
record SavedRolls(List<SavedRoll> rolls) {

    /** How many Saved Rolls are kept (spec §4). */
    static final int CAP = 100;

    /** The most characters a name may have once trimmed. */
    static final int NAME_MAX = 40;

    /** The index {@link #refusal} takes for a Saved Roll that is new rather than an edit of one in the list. */
    static final int NEW = -1;

    /** The file's name in the data folder. */
    static final String FILE_NAME = "saved-rolls.txt";

    /** No Saved Rolls: the first run's list. */
    static final SavedRolls EMPTY = new SavedRolls(List.of());

    /** The {@code saved-rolls.txt} format. */
    static final PersistedFile<SavedRolls> FILE = new Format();

    /**
     * Copies the list.
     *
     * @throws IllegalArgumentException if it holds more than {@value #CAP}
     */
    SavedRolls {
        rolls = List.copyOf(rolls);
        if (rolls.size() > CAP) {
            throw new IllegalArgumentException(rolls.size() + " Saved Rolls; at most " + CAP + " are kept");
        }
    }

    /** Why a Saved Roll can't be saved: one message, the first rule it breaks. */
    sealed interface Refusal {

        /**
         * Returns the message to show.
         *
         * @return one sentence
         */
        String message();

        /**
         * The list already holds {@value SavedRolls#CAP}; only a new Saved Roll is refused for it.
         *
         * @param message the message
         */
        record Full(String message) implements Refusal {}

        /**
         * The name breaks a rule.
         *
         * @param message the message
         */
        record BadName(String message) implements Refusal {}

        /**
         * The expression doesn't parse.
         *
         * @param error the parse error, whose span is in the expression as typed
         */
        record BadExpression(ParseError error) implements Refusal {
            @Override
            public String message() {
                return error.message();
            }
        }
    }

    /**
     * Tells why a Saved Roll can't be saved as typed, if it can't: the cap first (for a new one), then the name, then
     * the expression.
     *
     * @param name the name as typed; trimmed before the rules apply
     * @param expression the expression as typed; the error's span is into this text
     * @param except the index of the Saved Roll being edited, whose own name doesn't clash, or {@link #NEW}
     * @return the refusal, or empty if it can be saved
     */
    Optional<Refusal> refusal(String name, String expression, int except) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expression, "expression");
        if (except == NEW && full()) {
            return Optional.of(new Refusal.Full("There are already " + CAP
                    + " Saved Rolls, the most kept. Delete one to save another."));
        }
        Optional<String> badName = nameProblem(name.strip(), except);
        if (badName.isPresent()) {
            return badName.map(Refusal.BadName::new);
        }
        return new SavedRoll(name, expression).problem().map(Refusal.BadExpression::new);
    }

    /**
     * Tells whether a trimmed name breaks a rule, against every Saved Roll but the one at {@code except}.
     *
     * @return the message, or empty if the name is fine
     */
    private Optional<String> nameProblem(String name, int except) {
        if (name.isEmpty()) {
            return Optional.of("Give the Saved Roll a name.");
        }
        // Characters as a person counts them, so a letter outside the BMP counts once.
        int length = name.codePointCount(0, name.length());
        if (length > NAME_MAX) {
            return Optional.of("A name can be at most " + NAME_MAX + " characters; this one has " + length + ".");
        }
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            return Optional.of("A name can't hold a control character, such as a tab.");
        }
        for (int i = 0; i < rolls.size(); i++) {
            String other = rolls.get(i).name();
            if (i != except && other.equalsIgnoreCase(name)) {
                return Optional.of("There's already a Saved Roll called \"" + other + "\".");
            }
        }
        return Optional.empty();
    }

    /**
     * Tells whether the list is at the cap, so no new Saved Roll can join it.
     *
     * @return {@code true} at {@value #CAP}
     */
    boolean full() {
        return rolls.size() >= CAP;
    }

    /**
     * Returns this list with a Saved Roll added at the end.
     *
     * @param roll the new Saved Roll, already {@linkplain #refusal checked}
     * @return the new list
     * @throws IllegalStateException if the list is {@linkplain #full full}
     */
    SavedRolls with(SavedRoll roll) {
        Objects.requireNonNull(roll, "roll");
        if (full()) {
            throw new IllegalStateException("already " + CAP + " Saved Rolls");
        }
        List<SavedRoll> more = new ArrayList<>(rolls);
        more.add(roll);
        return new SavedRolls(more);
    }

    /**
     * Returns this list with the Saved Roll at {@code index} replaced, in the same place (an edit).
     *
     * @param index its index
     * @param roll what it becomes, already {@linkplain #refusal checked}
     * @return the new list
     */
    SavedRolls replaced(int index, SavedRoll roll) {
        Objects.requireNonNull(roll, "roll");
        List<SavedRoll> next = new ArrayList<>(rolls);
        next.set(index, roll);
        return new SavedRolls(next);
    }

    /**
     * Returns this list with the Saved Roll at {@code index} moved by {@code by} places; past either end it stays.
     *
     * @param index its index
     * @param by -1 to move it up, 1 to move it down
     * @return the new list, or this one if it can't move that far
     */
    SavedRolls moved(int index, int by) {
        int to = index + by;
        if (to < 0 || to >= rolls.size()) {
            return this;
        }
        List<SavedRoll> next = new ArrayList<>(rolls);
        next.add(to, next.remove(index));
        return new SavedRolls(next);
    }

    /**
     * Returns this list without the Saved Roll at {@code index}.
     *
     * @param index its index
     * @return the new list
     */
    SavedRolls without(int index) {
        List<SavedRoll> next = new ArrayList<>(rolls);
        next.remove(index);
        return new SavedRolls(next);
    }

    /** The {@code saved-rolls.txt} format, version 1. */
    private static final class Format implements PersistedFile<SavedRolls> {

        private static final char SEPARATOR = '\t';

        @Override
        public String name() {
            return FILE_NAME;
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public SavedRolls fresh() {
            return EMPTY;
        }

        @Override
        public SavedRolls read(List<String> body) throws UnreadableException {
            if (body.size() > CAP) {
                throw new UnreadableException(body.size() + " Saved Rolls; at most " + CAP + " are kept");
            }
            SavedRolls read = EMPTY;
            for (int i = 0; i < body.size(); i++) {
                String line = body.get(i);
                int tab = line.indexOf(SEPARATOR);
                if (tab < 0) {
                    throw new UnreadableException("line " + (i + 2) + " has no tab between a name and an expression");
                }
                String name = line.substring(0, tab);
                // The expression is kept whatever it says: one that no longer parses is shown as such, not lost.
                String expression = line.substring(tab + 1);
                Optional<String> badName = read.nameProblem(name.strip(), NEW);
                if (badName.isPresent()) {
                    throw new UnreadableException("line " + (i + 2) + "'s name: " + badName.get());
                }
                read = read.with(new SavedRoll(name.strip(), expression));
            }
            return read;
        }

        @Override
        public List<String> write(SavedRolls value) {
            return value.rolls().stream().map(roll -> roll.name() + SEPARATOR + roll.expression()).toList();
        }
    }
}
