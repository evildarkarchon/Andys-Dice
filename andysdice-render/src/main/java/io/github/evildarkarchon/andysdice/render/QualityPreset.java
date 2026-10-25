package io.github.evildarkarchon.andysdice.render;

import java.util.Optional;

/**
 * The Quality Preset the Player picks in the Settings tab (ADR-0014, spec §3.2): Low, Medium (the default) or High,
 * with no Custom and no per-lever control. Each sets every one of the renderer's quality levers at once: ADR-0002's
 * render scale, MSAA samples and shadow quality (the shadow map's size and its filter's tap count, ADR-0010), the
 * engraving's parallax occlusion sample counts (ADR-0008) and the translucent march's samples (ADR-0009). Below High
 * the render target is also capped at about 2.07 million pixels before render scale applies; the app applies that
 * cap, as it is what sizes the render target ({@link #capsRenderTarget()}).
 *
 * <p>High is full quality, the numbers the prototypes were judged at. Medium is defined as whatever meets ADR-0014's
 * 0.5 ms budget in {@code --bench} (ticket #61) and Low is for weaker integrated GPUs; the lever values below are
 * tunables (spec §6), set before the bench existed and meant to be tuned against it.
 *
 * <p>{@link DiceScene#setQualityPreset} hands a preset to the renderer.
 */
public enum QualityPreset {

    /** For weak (DX11-class) integrated GPUs: usable, with no frame-rate promise (ADR-0014). */
    LOW("Low", 0.7, 0, 1024, 8, 8, 16, 4, 4, true),

    /** The default: what an Iris Xe-class integrated GPU draws at 60 fps (ADR-0014). */
    MEDIUM("Medium", 0.85, 2, 1024, 12, 10, 32, 8, 6, true),

    /** Full quality, for a discrete GPU; the only preset that lifts the render-target cap. */
    HIGH("High", 1.0, 4, 2048, 16, 12, 48, 12, 8, false);

    /** The preset of a first run, and of a settings file whose preset can't be read (ADR-0014). */
    public static final QualityPreset DEFAULT = MEDIUM;

    private final String displayName;
    private final double renderScale;
    private final int msaaSamples;
    private final int shadowMapPx;
    private final int shadowTaps;
    private final int parallaxMinSamples;
    private final int parallaxMaxSamples;
    private final int parallaxShadowSamples;
    private final int volumeSteps;
    private final boolean capsRenderTarget;

    QualityPreset(String displayName, double renderScale, int msaaSamples, int shadowMapPx, int shadowTaps,
            int parallaxMinSamples, int parallaxMaxSamples, int parallaxShadowSamples, int volumeSteps,
            boolean capsRenderTarget) {
        this.displayName = displayName;
        this.renderScale = renderScale;
        this.msaaSamples = msaaSamples;
        this.shadowMapPx = shadowMapPx;
        this.shadowTaps = shadowTaps;
        this.parallaxMinSamples = parallaxMinSamples;
        this.parallaxMaxSamples = parallaxMaxSamples;
        this.parallaxShadowSamples = parallaxShadowSamples;
        this.volumeSteps = volumeSteps;
        this.capsRenderTarget = capsRenderTarget;
    }

    /**
     * Returns the name the Settings tab shows.
     *
     * @return "Low", "Medium" or "High"
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the render scale: the fraction of the render target's width and height (in physical pixels, after
     * the cap) that the scene is drawn at, before it is scaled up to fill the target.
     *
     * @return a scale in (0, 1]
     */
    public double renderScale() {
        return renderScale;
    }

    /**
     * Returns the MSAA samples per pixel the scene is drawn with.
     *
     * @return 0 for no multisampling, else the sample count (the driver may cap it)
     */
    public int msaaSamples() {
        return msaaSamples;
    }

    /**
     * Returns the key light's shadow map size, one half of the shadow-quality lever.
     *
     * @return texels per side
     */
    public int shadowMapPx() {
        return shadowMapPx;
    }

    /**
     * Returns how many taps PCSS takes in its blocker search and again in its filter, the other half of the
     * shadow-quality lever.
     *
     * @return 1 to 16, the size of {@code shadow.glsl}'s Poisson disc
     */
    public int shadowTaps() {
        return shadowTaps;
    }

    /**
     * Returns the fewest profile samples the engraving's parallax occlusion takes, looking straight at a face.
     *
     * @return the sample count at normal incidence
     */
    public int parallaxMinSamples() {
        return parallaxMinSamples;
    }

    /**
     * Returns the most profile samples the engraving's parallax occlusion takes, at grazing angles.
     *
     * @return the sample count at grazing incidence, at most 64
     */
    public int parallaxMaxSamples() {
        return parallaxMaxSamples;
    }

    /**
     * Returns the samples of the engraving's self-shadow march toward the key light.
     *
     * @return the march's sample count
     */
    public int parallaxShadowSamples() {
        return parallaxShadowSamples;
    }

    /**
     * Returns the samples a translucent Die's volume march takes through its body.
     *
     * @return 1 to {@value DiceScene#MAX_VOLUME_STEPS}
     */
    public int volumeSteps() {
        return volumeSteps;
    }

    /**
     * Tells whether the render target is capped at about 2.07 million pixels under this preset (ADR-0014).
     *
     * @return true below High
     */
    public boolean capsRenderTarget() {
        return capsRenderTarget;
    }

    /**
     * Returns the next cheaper preset, which the slow-GPU Notice suggests.
     *
     * @return the preset below this one, or empty for Low
     */
    public Optional<QualityPreset> lower() {
        return ordinal() == 0 ? Optional.empty() : Optional.of(values()[ordinal() - 1]);
    }
}
