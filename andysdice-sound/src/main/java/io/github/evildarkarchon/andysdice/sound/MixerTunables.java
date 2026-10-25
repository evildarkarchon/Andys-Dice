package io.github.evildarkarchon.andysdice.sound;

/**
 * The mixer's shaping numbers (ADR-0012 "Shaping lives in the sound module", "Variation"; research
 * dice-sound §3.4, §4.3, §4.4). They are tunables, not contracts: the build may change them.
 *
 * @param dieTrayFullScale the impulse, in kg·cm/s, at which a Die striking the floor or a wall plays at full
 *     gain (J_max for die–tray)
 * @param dieDieFullScale the same for a Die striking a Die, thrown or frozen (J_max for die–die)
 * @param cullFraction events below this fraction of their full-scale impulse are culled
 * @param mergeWindow events of the same body pair closer than this, in seconds of real (heard) time, merge
 *     into the loudest
 * @param windowCap at most this many impacts start in any {@link #capWindow} of real time
 * @param capWindow the cap's window, in seconds of real time
 * @param shuffleMemory a category's slice never repeats any of its last this many
 * @param pitchJitter the largest random pitch shift, in semitones either way
 * @param gainJitterDb the largest random gain change, in dB either way
 * @param sizePitchSlope semitones of pitch offset per halving of a Die Kind's inradius against the d6's
 *     (smaller Dice sound higher)
 * @param panWidth how far across the stereo field the screen's edges pan, 0 (mono) to 1 (hard left and right)
 * @param headroom the linear gain applied to the summed impacts before the limiter
 * @param limiterKnee the level, as a fraction of full scale, above which the soft limiter starts to bend
 * @param limiterCeiling the level the soft limiter approaches but never reaches, below full scale
 * @param lateTolerance an event this many seconds of real time overdue still starts, at once; later, it's
 *     dropped
 */
public record MixerTunables(double dieTrayFullScale, double dieDieFullScale, double cullFraction,
        double mergeWindow, int windowCap, double capWindow, int shuffleMemory, double pitchJitter,
        double gainJitterDb, double sizePitchSlope, double panWidth, double headroom, double limiterKnee,
        double limiterCeiling, double lateTolerance) {

    /**
     * The defaults. Full scale is set from the research's scale check (a 10 cm drop of a d6, about 1 kg·cm/s)
     * and #37's logged 20-Dice Throws (impulses up to about 0.8); die–die contacts are of lighter bodies.
     */
    public static final MixerTunables DEFAULTS = new MixerTunables(
            1.0, 0.5, 1.0 / 64, 0.030, 8, 0.050, 3, 1.0, 1.5, 6.0, 0.6, 0.5, 0.5, 0.98, 0.050);

    /**
     * Checks every number is in range.
     *
     * @throws IllegalArgumentException if one isn't
     */
    public MixerTunables {
        positive(dieTrayFullScale, "dieTrayFullScale");
        positive(dieDieFullScale, "dieDieFullScale");
        fraction(cullFraction, "cullFraction");
        notNegative(mergeWindow, "mergeWindow");
        if (windowCap < 1) {
            throw new IllegalArgumentException("windowCap must be at least 1: " + windowCap);
        }
        positive(capWindow, "capWindow");
        if (shuffleMemory < 0) {
            throw new IllegalArgumentException("shuffleMemory must not be negative: " + shuffleMemory);
        }
        notNegative(pitchJitter, "pitchJitter");
        notNegative(gainJitterDb, "gainJitterDb");
        notNegative(sizePitchSlope, "sizePitchSlope");
        fraction(panWidth, "panWidth");
        positive(headroom, "headroom");
        if (!(limiterKnee >= 0 && limiterKnee < limiterCeiling && limiterCeiling <= 1)) {
            throw new IllegalArgumentException("The limiter needs 0 <= knee < ceiling <= 1: " + limiterKnee
                    + ", " + limiterCeiling);
        }
        notNegative(lateTolerance, "lateTolerance");
    }

    /**
     * Returns these tunables with other jitter.
     *
     * @param pitch the largest pitch shift, in semitones
     * @param gainDb the largest gain change, in dB
     * @return the copy
     */
    public MixerTunables withJitter(double pitch, double gainDb) {
        return new MixerTunables(dieTrayFullScale, dieDieFullScale, cullFraction, mergeWindow, windowCap,
                capWindow, shuffleMemory, pitch, gainDb, sizePitchSlope, panWidth, headroom, limiterKnee,
                limiterCeiling, lateTolerance);
    }

    /**
     * Returns these tunables with another headroom gain.
     *
     * @param gain the linear gain before the limiter
     * @return the copy
     */
    public MixerTunables withHeadroom(double gain) {
        return new MixerTunables(dieTrayFullScale, dieDieFullScale, cullFraction, mergeWindow, windowCap,
                capWindow, shuffleMemory, pitchJitter, gainJitterDb, sizePitchSlope, panWidth, gain, limiterKnee,
                limiterCeiling, lateTolerance);
    }

    private static void positive(double value, String name) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be positive and finite: " + value);
        }
    }

    private static void notNegative(double value, String name) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and not negative: " + value);
        }
    }

    private static void fraction(double value, String name) {
        if (!(value >= 0 && value <= 1)) {
            throw new IllegalArgumentException(name + " must lie in [0, 1]: " + value);
        }
    }
}
