package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.time.Duration;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The JavaFX end of the bridge, without GL: frames filled off the JavaFX thread are presented through a public
 * {@code PixelBuffer} at the next pulse, and the double-buffered ring never hands out more than its three
 * buffers (the one on screen, the one just replaced, and one to fill).
 */
class FramePresenterTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private ImageView view;
    private FramePresenter presenter;

    @BeforeAll
    static void startJavaFx() {
        HeadlessFx.start();
    }

    @BeforeEach
    void newPresenter() throws Exception {
        view = HeadlessFx.call(ImageView::new);
        presenter = new FramePresenter(view, () -> {});
    }

    @Test
    void aPublishedFrameIsPresentedAsTheViewsImage() throws Exception {
        FramePresenter.Frame frame = presenter.acquire(4, 2);
        // BYTE_BGRA_PRE, top row first: (0,0) opaque red, (3,1) opaque blue, the rest opaque grey.
        ByteBuffer px = frame.pixels();
        for (int i = 0; i < 4 * 2; i++) {
            px.put(i * 4, new byte[] {0x40, 0x40, 0x40, (byte) 0xff});
        }
        px.put(0, new byte[] {0, 0, (byte) 0xff, (byte) 0xff});
        px.put((1 * 4 + 3) * 4, new byte[] {(byte) 0xff, 0, 0, (byte) 0xff});

        presenter.publish(frame);

        assertThat(HeadlessFx.await(() -> view.getImage() != null, WAIT)).isTrue();
        Image shown = HeadlessFx.call(view::getImage);
        assertThat(shown.getWidth()).isEqualTo(4);
        assertThat(shown.getHeight()).isEqualTo(2);
        assertThat(HeadlessFx.call(() -> shown.getPixelReader().getArgb(0, 0))).isEqualTo(0xffff0000);
        assertThat(HeadlessFx.call(() -> shown.getPixelReader().getArgb(3, 1))).isEqualTo(0xff0000ff);
        assertThat(HeadlessFx.call(() -> shown.getPixelReader().getArgb(1, 0))).isEqualTo(0xff404040);
        assertThat(presenter.framesPresented()).isEqualTo(1);
    }

    @Test
    void whoeverListensIsToldOfEachFrameOnTheJavaFxThreadOnceItIsShown() throws Exception {
        java.util.List<String> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        HeadlessFx.call(() -> {
            presenter.setOnPresented(() -> told.add(javafx.application.Platform.isFxApplicationThread() + " "
                    + presenter.framesPresented() + " " + (view.getImage() != null)));
            return null;
        });

        presenter.publish(awaitFrame(8, 8));
        assertThat(HeadlessFx.await(() -> told.size() == 1, WAIT)).isTrue();
        presenter.publish(awaitFrame(8, 8));
        assertThat(HeadlessFx.await(() -> told.size() == 2, WAIT)).isTrue();

        // On the JavaFX thread, after the count went up and the image was set.
        assertThat(told).containsExactly("true 1 true", "true 2 true");
    }

    @Test
    void neverHandsOutMoreThanThreeBuffers() {
        assertThat(presenter.acquire(8, 8)).isNotNull();
        assertThat(presenter.acquire(8, 8)).isNotNull();
        assertThat(presenter.acquire(8, 8)).isNotNull();

        assertThat(presenter.acquire(8, 8)).isNull();
    }

    @Test
    void framesKeepComingAndTheirBuffersAreReused() throws Exception {
        for (int i = 1; i <= 10; i++) {
            FramePresenter.Frame frame = awaitFrame(16, 16);
            presenter.publish(frame);
            long expected = i;
            assertThat(HeadlessFx.await(() -> presenter.framesPresented() == expected, WAIT)).isTrue();
        }
    }

    @Test
    void aNewSizeGivesFramesOfThatSize() throws Exception {
        presenter.publish(awaitFrame(16, 16));
        assertThat(HeadlessFx.await(() -> presenter.framesPresented() == 1, WAIT)).isTrue();

        FramePresenter.Frame bigger = awaitFrame(32, 24);
        assertThat(bigger.width()).isEqualTo(32);
        assertThat(bigger.height()).isEqualTo(24);
        assertThat(bigger.pixels().capacity()).isEqualTo(32 * 24 * 4);
        presenter.publish(bigger);

        assertThat(HeadlessFx.await(() -> view.getImage() != null && view.getImage().getWidth() == 32, WAIT))
                .isTrue();
        assertThat(HeadlessFx.call(() -> view.getImage().getHeight())).isEqualTo(24);
    }

    /** Waits until the ring has a free buffer, which it gets back one pulse after a frame is replaced. */
    private FramePresenter.Frame awaitFrame(int width, int height) throws Exception {
        FramePresenter.Frame[] got = new FramePresenter.Frame[1];
        assertThat(HeadlessFx.await(() -> (got[0] = presenter.acquire(width, height)) != null, WAIT)).isTrue();
        return got[0];
    }
}
