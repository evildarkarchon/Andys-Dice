package io.github.evildarkarchon.andysdice.render;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An OpenGL version as {@code major.minor}, read from a driver's {@code GL_VERSION} string.
 *
 * <p>The string is used rather than {@code GL_MAJOR_VERSION}/{@code GL_MINOR_VERSION} because those queries
 * only exist from GL 3.0, and the fail-fast must also describe a legacy 1.1 context such as GDI Generic.
 *
 * @param major the major version
 * @param minor the minor version
 */
record GlVersion(int major, int minor) {

    /** The hard floor (ADR-0002): OpenGL 3.3 core, which keeps macOS's GL 4.1 core usable. */
    static final GlVersion FLOOR = new GlVersion(3, 3);

    // Desktop GL's GL_VERSION starts with "<major>.<minor>"; whatever follows is vendor text. GL ES strings
    // start with "OpenGL ES" and so never match, which is right: an ES context can't run our shaders.
    private static final Pattern LEADING_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    /**
     * Reads the version at the start of a {@code GL_VERSION} string.
     *
     * @param reported the string the driver reported; may be {@code null}
     * @return the version, or empty if the string doesn't start with {@code major.minor}
     */
    static Optional<GlVersion> parse(String reported) {
        if (reported == null) {
            return Optional.empty();
        }
        Matcher m = LEADING_VERSION.matcher(reported.strip());
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new GlVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
    }

    /**
     * Whether this version is at least {@code other}.
     *
     * @param other the version to compare against
     * @return {@code true} if this version is equal to or newer than {@code other}
     */
    boolean isAtLeast(GlVersion other) {
        return major != other.major ? major > other.major : minor >= other.minor;
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
