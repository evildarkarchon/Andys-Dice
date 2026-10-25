package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The GPU bench (ADR-0014, ticket #61) on a real render thread: it times every candidate it is given, then the
 * dearest at every Quality Preset, all through the render thread's own timer queries. Only invariants: CI's Mesa
 * times say nothing about any GPU (ADR-0005), and few frames are drawn, as the software rasteriser is slow at 1080p.
 */
@Tag("gl")
class GpuBenchSceneTest {

    @Test
    void itTimesEveryCandidateThenTheDearestAtEveryPreset() {
        List<GpuBench.Scene> candidates = List.of(GpuBench.scenes().getFirst(), GpuBench.scenes().getLast());
        PacingTransport nowhere = new PacingTransport();
        DiceScene scene = new DiceScene();
        GpuBench.Report report;
        try (RenderThread renderer = RenderThread.start(nowhere, scene)) {
            report = GpuBench.run(renderer, scene, candidates, 1, 1, 2, 3);
        }

        assertThat(report.search()).extracting(GpuBench.GpuTime::scene).containsExactlyElementsOf(candidates);
        assertThat(report.search()).allSatisfy(time -> {
            assertThat(time.preset()).isEqualTo(GpuBench.BUDGETED_PRESET);
            assertThat(time.frames()).isEqualTo(2);
            assertThat(time.millis()).isPositive();
        });
        assertThat(report.byPreset()).containsOnlyKeys(QualityPreset.values());
        assertThat(report.byPreset().values()).allSatisfy(time -> {
            assertThat(time.scene()).isEqualTo(report.worst().scene());
            assertThat(time.frames()).isEqualTo(3);
            assertThat(time.millis()).isPositive();
        });
        assertThat(report.byPreset()).allSatisfy((preset, time) -> assertThat(time.preset()).isEqualTo(preset));
    }
}
