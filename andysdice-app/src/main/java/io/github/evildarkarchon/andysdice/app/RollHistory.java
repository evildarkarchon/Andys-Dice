package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.FaceValueChain;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The Roll History (spec §3.6): the last {@value #CAP} Rolls, oldest first, as an immutable value. {@link #with}
 * adds a Roll and drops the oldest past the cap; {@link #newestFirst()} is the order the History tab lists them in.
 *
 * <p>{@link #FILE} is {@code history.txt}: the version header, then one line per Roll, newest last, of four
 * tab-separated facts (ADR-0007):
 * <pre>{@code <UTC instant>⇥<expression as written>⇥<Saved Roll name or nothing>⇥<chains>}</pre>
 * where the chains are each Die's Face Value Chain in Dice Group order, joined by {@code ,}, each chain's values
 * joined by {@code >} ({@code 1>5,3,6,2}). Neither the grammar nor a Saved Roll's name can hold a tab or any other
 * control character, so nothing is escaped.
 *
 * @param rolls the Rolls, oldest first; at most {@value #CAP}
 */
record RollHistory(List<PastRoll> rolls) {

    /** How many Rolls are kept (spec §4, a contract). */
    static final int CAP = 100;

    /** The file's name in the data folder. */
    static final String FILE_NAME = "history.txt";

    /** No Rolls: the first run's history, and what Clear all leaves. */
    static final RollHistory EMPTY = new RollHistory(List.of());

    /** The {@code history.txt} format. */
    static final PersistedFile<RollHistory> FILE = new Format();

    /**
     * Copies the Rolls, keeping only the newest {@value #CAP}.
     */
    RollHistory {
        rolls = List.copyOf(rolls.subList(Math.max(0, rolls.size() - CAP), rolls.size()));
    }

    /**
     * Returns this history with a Roll added as the newest, the oldest dropped if that passes the cap.
     *
     * @param roll the Roll just revealed
     * @return the new history
     */
    RollHistory with(PastRoll roll) {
        List<PastRoll> more = new ArrayList<>(rolls);
        more.add(roll);
        return new RollHistory(more);
    }

    /**
     * Returns the Rolls newest first, as the History tab lists them.
     *
     * @return the Rolls, the latest first
     */
    List<PastRoll> newestFirst() {
        return rolls.reversed();
    }

    /** The {@code history.txt} format, version 1. */
    private static final class Format implements PersistedFile<RollHistory> {

        private static final String SEPARATOR = "\t";
        /** A Face Value is 1 to 100 (a Percentile Roll's), written in plain digits with no sign. */
        private static final Pattern VALUE = Pattern.compile("[0-9]{1,3}");

        @Override
        public String name() {
            return FILE_NAME;
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public RollHistory fresh() {
            return EMPTY;
        }

        @Override
        public RollHistory read(List<String> body) throws UnreadableException {
            List<PastRoll> rolls = new ArrayList<>();
            for (int i = 0; i < body.size(); i++) {
                String line = body.get(i);
                try {
                    rolls.add(parse(line));
                } catch (IllegalArgumentException | DateTimeParseException e) {
                    // A line that makes no Roll means the file isn't ours to understand: it is set aside whole
                    // (and kept) rather than losing that Roll at the next write.
                    throw new UnreadableException("line " + (i + 2) + " isn't a Roll: " + e.getMessage(), e);
                }
            }
            return new RollHistory(rolls);
        }

        @Override
        public List<String> write(RollHistory value) {
            return value.rolls().stream().map(Format::line).toList();
        }

        private static String line(PastRoll roll) {
            String chains = roll.chains().stream()
                    .map(chain -> chain.values().stream().map(String::valueOf).collect(Collectors.joining(">")))
                    .collect(Collectors.joining(","));
            return String.join(SEPARATOR, roll.at().toString(), roll.expression(),
                    roll.savedRollName().orElse(""), chains);
        }

        private static PastRoll parse(String line) {
            String[] fields = line.split(SEPARATOR, -1);
            if (fields.length != 4) {
                throw new IllegalArgumentException("it has " + fields.length + " fields, not 4");
            }
            Instant at = Instant.parse(fields[0]);
            Optional<String> savedRollName = fields[2].isEmpty() ? Optional.empty() : Optional.of(fields[2]);
            List<FaceValueChain> chains = new ArrayList<>();
            for (String chain : fields[3].split(",", -1)) {
                chains.add(new FaceValueChain(Arrays.stream(chain.split(">", -1)).map(Format::value).toList()));
            }
            return new PastRoll(at, fields[1], savedRollName, chains);
        }

        private static Integer value(String text) {
            if (!VALUE.matcher(text).matches()) {
                throw new IllegalArgumentException("`" + text + "` isn't a Face Value");
            }
            return Integer.valueOf(text);
        }
    }
}
