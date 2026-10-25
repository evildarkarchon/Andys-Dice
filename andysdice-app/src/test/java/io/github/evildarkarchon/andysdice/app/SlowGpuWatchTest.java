package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.render.QualityPreset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The slow-GPU Notice (ADR-0014, spec §3.2): when every frame drawn across a Throw took more than 14 ms of GPU time,
 * one Notice suggests the next lower Quality Preset, once per run; the preset itself is never touched. Pure: frames
 * and Throws are fed in by hand.
 */
class SlowGpuWatchTest {

    private static final Object FIRST_THROW = "first";
    private static final Object SECOND_THROW = "second";

    private final List<Notice> posted = new ArrayList<>();
    private final AtomicReference<QualityPreset> preset = new AtomicReference<>(QualityPreset.MEDIUM);
    private SlowGpuWatch watch;

    @BeforeEach
    void watch() {
        watch = new SlowGpuWatch(posted::add, preset::get);
    }

    @Test
    void theThresholdIs14MsAndAThrowNeedsEnoughFramesToJudge() {
        assertThat(SlowGpuWatch.THRESHOLD_MILLIS).isEqualTo(14.0);
        assertThat(SlowGpuWatch.MIN_FRAMES).isBetween(2, 30);
    }

    @Test
    void aThrowWhoseEveryFrameTookOver14MsPostsANoticeSuggestingTheLowerPreset() {
        watch.showing(FIRST_THROW);
        frames(30, 18.5);
        assertThat(posted).as("nothing until the Throw has come to rest").isEmpty();

        watch.showing(null);

        assertThat(posted).hasSize(1);
        Notice notice = posted.getFirst();
        assertThat(notice.title()).isNotBlank();
        assertThat(notice.detail()).contains("Low").contains("Quality Preset").contains("Settings");
    }

    @Test
    void underHighItSuggestsMedium() {
        preset.set(QualityPreset.HIGH);
        watch.showing(FIRST_THROW);
        frames(30, 20);
        watch.showing(null);

        assertThat(posted).singleElement().extracting(Notice::detail).asString().contains("Medium");
    }

    @Test
    void oneFrameAtOrUnderTheThresholdMeansTheThrowWasNotSlow() {
        watch.showing(FIRST_THROW);
        frames(15, 20);
        watch.frame(14.0);
        frames(15, 20);
        watch.showing(null);

        assertThat(posted).isEmpty();
    }

    @Test
    void aThrowTooShortToJudgePostsNothing() {
        watch.showing(FIRST_THROW);
        frames(SlowGpuWatch.MIN_FRAMES - 1, 40);
        watch.showing(null);

        assertThat(posted).isEmpty();
    }

    @Test
    void framesOutsideAThrowDontCount() {
        frames(30, 40);
        watch.showing(FIRST_THROW);
        frames(SlowGpuWatch.MIN_FRAMES - 1, 40);
        watch.showing(null);
        frames(30, 40);

        assertThat(posted).isEmpty();
    }

    @Test
    void eachThrowIsJudgedOnItsOwn() {
        // The next Throw starting ends the last one: a slow one before a fast one still counts.
        watch.showing(FIRST_THROW);
        frames(30, 9);
        watch.showing(SECOND_THROW);
        frames(30, 25);
        watch.showing(FIRST_THROW);

        assertThat(posted).hasSize(1);
    }

    @Test
    void theNoticeIsPostedOncePerRun() {
        for (int i = 0; i < 3; i++) {
            watch.showing(i % 2 == 0 ? FIRST_THROW : SECOND_THROW);
            frames(30, 30);
            watch.showing(null);
        }

        assertThat(posted).hasSize(1);
    }

    @Test
    void underLowThereIsNoLowerPresetToSuggest() {
        preset.set(QualityPreset.LOW);
        watch.showing(FIRST_THROW);
        frames(30, 30);
        watch.showing(null);
        assertThat(posted).isEmpty();

        // The one Notice is still owed if the Player later moves up to a preset that has one below it.
        preset.set(QualityPreset.MEDIUM);
        watch.showing(SECOND_THROW);
        frames(30, 30);
        watch.showing(null);
        assertThat(posted).hasSize(1);
    }

    @Test
    void theWatchNeverChangesThePreset() {
        watch.showing(FIRST_THROW);
        frames(30, 30);
        watch.showing(null);

        assertThat(posted).hasSize(1);
        assertThat(preset.get()).isEqualTo(QualityPreset.MEDIUM);
    }

    private void frames(int count, double millis) {
        for (int i = 0; i < count; i++) {
            watch.frame(millis);
        }
    }
}
