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

    /**
     * A dev and test override of the core version the render thread asks for, as {@code major.minor}. It can only
     * raise the floor: setting it above what the GPU offers forces the start-up fail-fast (ADR-0002) through the
     * real driver on a machine that has OpenGL 3.3, which is how the packaged app's fail-fast is checked away
     * from CI's no-Mesa job (ticket #35).
     */
    static final String REQUIRE_PROPERTY = "andysdice.gl.require";

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
     * The core version to ask the driver for: the floor, or the {@value #REQUIRE_PROPERTY} override if that is
     * higher. An override below the floor is ignored, so no setting lets the app run on less than 3.3.
     *
     * @param override the override's value, or {@code null} or blank when it isn't set
     * @return the version to require
     * @throws IllegalArgumentException if the override isn't a {@code major.minor} version
     */
    static GlVersion required(String override) {
        if (override == null || override.isBlank()) {
            return FLOOR;
        }
        String value = override.strip();
        GlVersion asked = parse(value).filter(v -> value.equals(v.toString()))
                .orElseThrow(() -> new IllegalArgumentException("-D" + REQUIRE_PROPERTY + " must be a major.minor"
                        + " OpenGL version such as 4.6, not \"" + override + "\""));
        return asked.isAtLeast(FLOOR) ? asked : FLOOR;
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
