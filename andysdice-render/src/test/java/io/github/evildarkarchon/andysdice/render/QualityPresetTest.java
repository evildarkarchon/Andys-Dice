package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The three Quality Presets (ADR-0014, spec §3.2 "Settings tab"): Low, Medium (the default) and High, with no
 * Custom. Each sets every lever ADR-0014 names; the values are tunables, so these tests check only what the ADR
 * fixes: High is full quality (the counts ADR-0008 and ADR-0009 give), nothing gets more expensive going down, and
 * only High lifts the render-target cap. Pure Java, no GL.
 */
class QualityPresetTest {

    @Test
    void thereAreExactlyLowMediumAndHigh() {
        assertThat(QualityPreset.values()).containsExactly(QualityPreset.LOW, QualityPreset.MEDIUM,
                QualityPreset.HIGH);
    }

    @Test
    void mediumIsTheDefault() {
        assertThat(QualityPreset.DEFAULT).isEqualTo(QualityPreset.MEDIUM);
    }

    @Test
    void highIsFullQuality() {
        QualityPreset high = QualityPreset.HIGH;
        assertThat(high.renderScale()).isEqualTo(1.0);
        // ADR-0008: 12 to 48 profile samples plus 12 for the shadow march; ADR-0009: an 8-sample march.
        assertThat(high.parallaxMinSamples()).isEqualTo(12);
        assertThat(high.parallaxMaxSamples()).isEqualTo(48);
        assertThat(high.parallaxShadowSamples()).isEqualTo(12);
        assertThat(high.volumeSteps()).isEqualTo(8).isEqualTo(DiceScene.DEFAULT_VOLUME_STEPS);
        // The tray prototype's shadow: a 2048 px map filtered with 16 taps; and its 4x MSAA.
        assertThat(high.shadowMapPx()).isEqualTo(2048);
        assertThat(high.shadowTaps()).isEqualTo(16);
        assertThat(high.msaaSamples()).isEqualTo(4);
    }

    @Test
    void onlyHighLiftsTheRenderTargetCap() {
        assertThat(QualityPreset.LOW.capsRenderTarget()).isTrue();
        assertThat(QualityPreset.MEDIUM.capsRenderTarget()).isTrue();
        assertThat(QualityPreset.HIGH.capsRenderTarget()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = QualityPreset.class, names = {"LOW", "MEDIUM"})
    void noLeverCostsMoreThanOnThePresetAbove(QualityPreset preset) {
        QualityPreset above = QualityPreset.values()[preset.ordinal() + 1];
        assertThat(preset.renderScale()).isLessThanOrEqualTo(above.renderScale());
        assertThat(preset.msaaSamples()).isLessThanOrEqualTo(above.msaaSamples());
        assertThat(preset.shadowMapPx()).isLessThanOrEqualTo(above.shadowMapPx());
        assertThat(preset.shadowTaps()).isLessThanOrEqualTo(above.shadowTaps());
        assertThat(preset.parallaxMinSamples()).isLessThanOrEqualTo(above.parallaxMinSamples());
        assertThat(preset.parallaxMaxSamples()).isLessThanOrEqualTo(above.parallaxMaxSamples());
        assertThat(preset.parallaxShadowSamples()).isLessThanOrEqualTo(above.parallaxShadowSamples());
        assertThat(preset.volumeSteps()).isLessThanOrEqualTo(above.volumeSteps());
    }

    @Test
    void lowIsCheaperThanHighOnEveryLever() {
        QualityPreset low = QualityPreset.LOW;
        QualityPreset high = QualityPreset.HIGH;
        assertThat(low.renderScale()).isLessThan(high.renderScale());
        assertThat(low.msaaSamples()).isLessThan(high.msaaSamples());
        assertThat(low.shadowMapPx()).isLessThan(high.shadowMapPx());
        assertThat(low.shadowTaps()).isLessThan(high.shadowTaps());
        assertThat(low.parallaxMaxSamples()).isLessThan(high.parallaxMaxSamples());
        assertThat(low.parallaxShadowSamples()).isLessThan(high.parallaxShadowSamples());
        assertThat(low.volumeSteps()).isLessThan(high.volumeSteps());
    }

    @ParameterizedTest
    @EnumSource(QualityPreset.class)
    void everyLeverIsInTheRangeTheShadersTake(QualityPreset preset) {
        assertThat(preset.renderScale()).isGreaterThan(0).isLessThanOrEqualTo(1);
        assertThat(preset.msaaSamples()).isIn(0, 2, 4, 8);
        assertThat(preset.shadowMapPx()).isPositive();
        // shadow.glsl's Poisson disc has 16 taps; engraving.glsl's march stops at 64.
        assertThat(preset.shadowTaps()).isBetween(1, 16);
        assertThat(preset.parallaxMinSamples()).isPositive().isLessThanOrEqualTo(preset.parallaxMaxSamples());
        assertThat(preset.parallaxMaxSamples()).isLessThanOrEqualTo(64);
        assertThat(preset.parallaxShadowSamples()).isPositive();
        assertThat(preset.volumeSteps()).isBetween(1, DiceScene.MAX_VOLUME_STEPS);
    }

    @Test
    void eachPresetNamesTheOneBelowIt() {
        assertThat(QualityPreset.HIGH.lower()).contains(QualityPreset.MEDIUM);
        assertThat(QualityPreset.MEDIUM.lower()).contains(QualityPreset.LOW);
        assertThat(QualityPreset.LOW.lower()).isEqualTo(Optional.empty());
    }

    @Test
    void eachPresetHasThePlayersName() {
        assertThat(QualityPreset.LOW.displayName()).isEqualTo("Low");
        assertThat(QualityPreset.MEDIUM.displayName()).isEqualTo("Medium");
        assertThat(QualityPreset.HIGH.displayName()).isEqualTo("High");
    }
}
