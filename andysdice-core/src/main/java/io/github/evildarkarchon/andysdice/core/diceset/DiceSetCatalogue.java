package io.github.evildarkarchon.andysdice.core.diceset;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Dice Sets a Player can choose from, in swatch order, and the rule for choosing one: by identity, falling
 * back to the first when the chosen one is missing (ADR-0009). The built-ins come first, Onyx leading (spec
 * Appendix B); ticket #57 lists the Player Dice Sets after them.
 *
 * <p>Immutable, so any thread may share one.
 */
public final class DiceSetCatalogue {

    /**
     * The built-in files, in spec Appendix B order, as resources of this package. A module's resources can't be
     * listed, so they are named here. Ticket #43 adds 11-smoke through 14-nebula.
     */
    private static final List<String> BUILT_IN_FILES = List.of("01-onyx", "02-ivory", "03-crimson",
            "04-vortex-blue", "05-jade-marble", "06-speckled-stone", "07-pearl-violet", "08-steel", "09-gold",
            "10-gunmetal");

    /** Parsed once, on first use: a dozen tiny files. */
    private static final class BuiltIn {
        static final DiceSetCatalogue INSTANCE = load();
    }

    private final List<DiceSet> diceSets;

    /**
     * A catalogue of these Dice Sets, in this order; the first is the fallback.
     *
     * @param diceSets the Dice Sets
     * @throws IllegalArgumentException if there are none, or two share an identity
     */
    public DiceSetCatalogue(List<DiceSet> diceSets) {
        this.diceSets = List.copyOf(diceSets);
        if (this.diceSets.isEmpty()) {
            throw new IllegalArgumentException("a Dice Set catalogue needs at least one Dice Set");
        }
        Set<String> seen = new HashSet<>();
        for (DiceSet set : this.diceSets) {
            if (!seen.add(set.identity())) {
                throw new IllegalArgumentException("two Dice Sets are both " + set.identity());
            }
        }
    }

    /**
     * The Dice Sets that ship inside the app (spec Appendix B), parsed from the files copied verbatim from the
     * materials prototype.
     *
     * @return the built-in catalogue, Onyx first
     */
    public static DiceSetCatalogue builtIn() {
        return BuiltIn.INSTANCE;
    }

    /** @return every Dice Set, in swatch order */
    public List<DiceSet> diceSets() {
        return diceSets;
    }

    /** @return the first Dice Set, the default and the fallback for a missing choice (Onyx, for the built-ins) */
    public DiceSet fallback() {
        return diceSets.getFirst();
    }

    /**
     * Finds a Dice Set.
     *
     * @param identity its identity, such as {@code 01-onyx}
     * @return it, or empty if no Dice Set here has that identity
     */
    public Optional<DiceSet> find(String identity) {
        return diceSets.stream().filter(set -> set.identity().equals(identity)).findFirst();
    }

    /**
     * Chooses a Dice Set, as the stored setting asks: that one if it is here, otherwise the {@link #fallback()}.
     *
     * @param identity the chosen identity, such as {@code settings.properties} stores
     * @return the Dice Set to draw with
     */
    public DiceSet choose(String identity) {
        return find(identity).orElseGet(this::fallback);
    }

    private static DiceSetCatalogue load() {
        List<DiceSet> sets = new ArrayList<>();
        for (String identity : BUILT_IN_FILES) {
            String file = identity + DiceSetFile.EXTENSION;
            try (InputStream in = DiceSetCatalogue.class.getResourceAsStream(file)) {
                if (in == null) {
                    throw new IllegalStateException("the built-in Dice Set " + file + " is missing from the build");
                }
                sets.add(DiceSetFile.parse(identity, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            } catch (DiceSetFormatException e) {
                // A built-in file is part of the build, so this is a build defect, never a Player's mistake.
                throw new IllegalStateException("the built-in Dice Set " + file + " is unreadable", e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new DiceSetCatalogue(sets);
    }
}
