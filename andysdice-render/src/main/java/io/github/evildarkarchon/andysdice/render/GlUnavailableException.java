package io.github.evildarkarchon.andysdice.render;

import java.io.Serial;
import java.util.Optional;

/**
 * No OpenGL 3.3 core context could be created, so the app can't render and must fail fast (ADR-0002). There is
 * no fallback renderer.
 *
 * <p>The message names the requirement, the reason and the renderer, vendor and version the driver reported,
 * so the start-up failure panel (spec §3, ticket #53) and the log can show it as is.
 */
public final class GlUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The requirement, in the words every report of this failure uses. */
    public static final String REQUIREMENT = "OpenGL " + GlVersion.FLOOR + " core";

    /** {@code null} when not even a legacy context could be made to ask. */
    private final GlDriver driver;

    /**
     * Creates the failure.
     *
     * @param reason why the context couldn't be made, in a few words (for example "the driver offers only OpenGL
     *     1.1.0")
     * @param driver what the driver reported, or {@code null} if nothing could be asked
     */
    public GlUnavailableException(String reason, GlDriver driver) {
        this(REQUIREMENT, reason, driver, null);
    }

    /**
     * Creates the failure for a context of the given version, which is the floor except in tests.
     *
     * @param required the core version that was asked for
     * @param reason why the context couldn't be made
     * @param driver what the driver reported, or {@code null}
     * @param cause the underlying error, or {@code null}
     */
    GlUnavailableException(GlVersion required, String reason, GlDriver driver, Throwable cause) {
        this("OpenGL " + required + " core", reason, driver, cause);
    }

    private GlUnavailableException(String requirement, String reason, GlDriver driver, Throwable cause) {
        super(message(requirement, reason, driver), cause);
        this.driver = driver;
    }

    /**
     * What the driver reported about itself.
     *
     * @return the driver's renderer, vendor and version, or empty if no context at all could be made
     */
    public Optional<GlDriver> driver() {
        return Optional.ofNullable(driver);
    }

    private static String message(String requirement, String reason, GlDriver driver) {
        String detected = driver == null ? "renderer: unknown, vendor: unknown, version: unknown" : driver.toString();
        return "Andy's Dice needs " + requirement + ", but " + reason + " (" + detected + ")";
    }
}
