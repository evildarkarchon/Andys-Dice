package io.github.evildarkarchon.andysdice.app;

import java.util.ArrayList;
import java.util.List;

/**
 * A test-only persisted file at version 3, with two older versions to migrate from, so the generic migration path
 * of {@link Persistence} can be checked before any real file has a second version.
 * <ul>
 *   <li>Version 1: one line of comma-separated numbers ({@code 4,8,15}).</li>
 *   <li>Version 2: one {@code n <number>} line per number.</li>
 *   <li>Version 3 (current): one number per line. Anything else is unreadable.</li>
 * </ul>
 */
final class NumbersFile implements PersistedFile<List<Integer>> {

    /** The file's name in the data folder. */
    static final String NAME = "numbers.txt";

    /** Thread names {@link #read} ran on, oldest first. */
    final List<String> readThreads = new ArrayList<>();

    /** Runs inside {@link #read}, before it parses: tests block the writer thread with it. */
    Runnable duringRead = () -> {};

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int version() {
        return 3;
    }

    @Override
    public List<Integer> fresh() {
        return List.of();
    }

    @Override
    public List<Integer> read(List<String> body) throws UnreadableException {
        readThreads.add(Thread.currentThread().getName());
        duringRead.run();
        List<Integer> numbers = new ArrayList<>();
        for (String line : body) {
            try {
                numbers.add(Integer.parseInt(line));
            } catch (NumberFormatException e) {
                throw new UnreadableException("not a number: " + line);
            }
        }
        return List.copyOf(numbers);
    }

    @Override
    public List<String> migrate(int from, List<String> body) throws UnreadableException {
        return switch (from) {
            case 1 -> body.isEmpty() ? List.of()
                    : List.of(body.getFirst().split(",")).stream().map(n -> "n " + n.strip()).toList();
            case 2 -> body.stream().map(line -> line.substring("n ".length())).toList();
            default -> PersistedFile.super.migrate(from, body);
        };
    }

    @Override
    public List<String> write(List<Integer> value) {
        return value.stream().map(String::valueOf).toList();
    }
}
