package io.github.evildarkarchon.andysdice.core.tray;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The Trays that ship inside the app (spec Appendix C, ADR-0010): {@code .tray} files packaged as resources of this
 * module, read once on first use through the same {@link TrayFile} parser as a Player's Tray. In Appendix C's order,
 * with green felt in walnut first: the default, and the fallback for a missing choice.
 */
public final class BuiltInTrays {

    /** The default Tray's identity: green felt in walnut (Appendix C). */
    public static final String DEFAULT_IDENTITY = "01-green-felt";

    /** The built-in files' identities, in catalogue order; a module can't list its own resources. */
    private static final List<String> IDENTITIES = List.of(DEFAULT_IDENTITY, "02-leather-hexagon", "03-walnut-board");

    /** Read at class initialisation: a built-in that won't parse is a broken build, not a Player's mistake. */
    private static final List<Tray> ALL = IDENTITIES.stream().map(BuiltInTrays::load).toList();

    private BuiltInTrays() {}

    /**
     * Returns every built-in Tray.
     *
     * @return the Trays in Appendix C's order, the default first
     */
    public static List<Tray> all() {
        return ALL;
    }

    /**
     * Returns the default Tray.
     *
     * @return green felt in walnut
     */
    public static Tray defaultTray() {
        return ALL.getFirst();
    }

    /**
     * Finds a built-in Tray by its identity.
     *
     * @param identity the identity, such as {@code 02-leather-hexagon}
     * @return the Tray, or empty if no built-in has that identity
     */
    public static Optional<Tray> find(String identity) {
        return ALL.stream().filter(tray -> tray.identity().equals(identity)).findFirst();
    }

    /**
     * Finds a built-in Tray by its identity, falling back to the default for a missing one (ADR-0010).
     *
     * @param identity the identity a setting names
     * @return that Tray, or the default
     */
    public static Tray orDefault(String identity) {
        return find(identity).orElse(defaultTray());
    }

    /** Reads one built-in file from this module's resources, beside this class. */
    private static Tray load(String identity) {
        String resource = identity + TrayFile.EXTENSION;
        try (InputStream in = BuiltInTrays.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("The built-in Tray " + resource + " is missing from the build");
            }
            return TrayFile.parse(identity, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("The built-in Tray " + resource + " can't be read", e);
        }
    }
}
