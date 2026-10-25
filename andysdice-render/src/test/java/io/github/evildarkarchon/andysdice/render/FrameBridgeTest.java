package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glScissor;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glIsProgram;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * The whole bridge on a real GL 3.3 core context (ADR-0002, ADR-0005): the render thread draws into its FBO,
 * frames come back through the PBO ring and are presented by JavaFX (headless here) through a
 * {@code PixelBuffer}. Without a 3.3 context {@link #startRenderThread} throws and every test here fails; none
 * is ever skipped.
 *
 * <p>One render thread serves the whole class, as one serves the whole app run.
 */
@Tag("gl")
class FrameBridgeTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final int OPAQUE_RED = 0xffff0000;
    private static final int OPAQUE_BLUE = 0xff0000ff;

    private static FrameView view;
    private static Probe probe;
    private static RenderThread renderer;

    /** Wraps the test scene, recording where and how it was called, and can draw two flat halves instead. */
    private static final class Probe implements FrameRenderer {
        final TestScene scene = new TestScene();
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        final Set<GLCapabilities> contexts = ConcurrentHashMap.newKeySet();
        final List<String> sizes = new CopyOnWriteArrayList<>();
        volatile boolean halves;

        @Override
        public void init() {
            record();
            scene.init();
        }

        @Override
        public void render(int width, int height) {
            record();
            sizes.add(width + "x" + height);
            if (!halves) {
                scene.render(width, height);
                return;
            }
            // GL's origin is bottom-left: the bottom half blue, the top half red.
            glEnable(GL_SCISSOR_TEST);
            glScissor(0, 0, width, height / 2);
            glClearColor(0, 0, 1, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glScissor(0, height / 2, width, height - height / 2);
            glClearColor(1, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glDisable(GL_SCISSOR_TEST);
        }

        @Override
        public void dispose() {
            record();
            scene.dispose();
        }

        private void record() {
            threads.add(Thread.currentThread());
            contexts.add(GL.getCapabilities());
        }
    }

    @BeforeAll
    static void startRenderThread() throws Exception {
        HeadlessFx.start();
        view = HeadlessFx.call(() -> {
            FrameView v = new FrameView();
            // Unmanaged, so the tests set its size directly.
            v.setManaged(false);
            Stage stage = new Stage();
            stage.setScene(new Scene(new Pane(v), 200, 150));
            stage.show();
            return v;
        });
        probe = new Probe();
        renderer = RenderThread.start(view.transport(), probe);
        HeadlessFx.call(() -> {
            view.setOnTargetSizeChanged(renderer::resize);
            view.resizeRelocate(0, 0, 64, 48);
            return null;
        });
    }

    @AfterAll
    static void stopRenderThread() {
        if (renderer != null) {
            renderer.close();
        }
    }

    @Test
    void theTestScenesShadersCompileAndLink() throws Exception {
        boolean linked = renderer.submit(() -> {
            int program = Shaders.program("flat.vert", "flat.frag");
            boolean ok = glIsProgram(program);
            glDeleteProgram(program);
            return ok;
        }).get(10, TimeUnit.SECONDS);

        assertThat(linked).isTrue();
    }

    @Test
    void aShaderThatDoesNotCompileFailsWithItsInfoLog() {
        Throwable failure = catchThrowable(() -> renderer.submit(() -> Shaders.compile(GL_FRAGMENT_SHADER,
                "#version 330 core\nvoid main() { undefined_function(); }\n", "broken.frag"))
                .get(10, TimeUnit.SECONDS));

        assertThat(failure).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessageContaining("broken.frag");
    }

    @Test
    void framesAreDeliveredThroughTheBridge() throws Exception {
        probe.halves = false;
        long before = view.framesPresented();
        renderer.requestFrame();

        // Wait for the test scene itself: a frame another test asked for may still be on its way.
        assertThat(HeadlessFx.await(() -> view.framesPresented() > before
                && argbAt(0, 0) == TestScene.BACKGROUND_ARGB, WAIT)).isTrue();
        Image shown = HeadlessFx.call(view::image);
        double scale = HeadlessFx.call(() -> view.getScene().getWindow().getOutputScaleX());
        assertThat(shown.getWidth()).isEqualTo(Math.round(64 * scale));
        assertThat(shown.getHeight()).isEqualTo(Math.round(48 * scale));
        int corner = HeadlessFx.call(() -> shown.getPixelReader().getArgb(0, 0));
        int centre = HeadlessFx.call(() ->
                shown.getPixelReader().getArgb((int) shown.getWidth() / 2, (int) shown.getHeight() / 2));
        assertThat(corner).isEqualTo(TestScene.BACKGROUND_ARGB);
        assertThat(centre).as("the cube covers the centre").isNotEqualTo(TestScene.BACKGROUND_ARGB);
    }

    @Test
    void framesArriveUprightWithTrueColours() throws Exception {
        probe.halves = true;
        try {
            renderer.requestFrame();

            assertThat(HeadlessFx.await(() -> argbAt(0, 0) == OPAQUE_RED, WAIT)).isTrue();
            int bottom = HeadlessFx.call(() -> view.image().getPixelReader().getArgb(0,
                    (int) view.image().getHeight() - 1));
            assertThat(bottom).isEqualTo(OPAQUE_BLUE);
        } finally {
            probe.halves = false;
        }
    }

    @Test
    void resizingResizesTheRenderTargetWithoutHangingOrANewContext() throws Exception {
        // A burst of sizes, as a window drag gives, then the one that should stick.
        HeadlessFx.call(() -> {
            for (int i = 0; i < 30; i++) {
                view.resizeRelocate(0, 0, 40 + i * 7, 30 + i * 5);
            }
            view.resizeRelocate(0, 0, 120, 90);
            return null;
        });
        renderer.requestFrame();
        double scale = HeadlessFx.call(() -> view.getScene().getWindow().getOutputScaleX());
        long width = Math.round(120 * scale);
        long height = Math.round(90 * scale);

        assertThat(HeadlessFx.await(() -> view.image() != null && view.image().getWidth() == width
                && view.image().getHeight() == height, WAIT)).isTrue();
        assertThat(probe.sizes).contains(width + "x" + height);
        assertThat(probe.contexts).as("one context, resized, never recreated").hasSize(1);

        HeadlessFx.call(() -> {
            view.resizeRelocate(0, 0, 64, 48);
            return null;
        });
    }

    @Test
    void everyGlCallIsOnTheOneRenderThread() throws Exception {
        renderer.requestFrame();
        assertThat(HeadlessFx.await(() -> view.framesPresented() > 0, WAIT)).isTrue();

        assertThat(probe.threads).hasSize(1);
        Thread renderThread = probe.threads.iterator().next();
        assertThat(renderer.submit(Thread::currentThread).get(10, TimeUnit.SECONDS)).isSameAs(renderThread);
        // The JavaFX thread has no GL context at all; it only presents and sends messages.
        assertThat(HeadlessFx.call(() -> catchThrowable(GL::getCapabilities)))
                .isInstanceOf(IllegalStateException.class);
        // The transport refuses GL work from any other thread.
        assertThatThrownBy(() -> view.transport().submit(64, 48)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thereIsOnlyOneRenderThreadPerRun() {
        assertThatThrownBy(() -> RenderThread.start(view.transport(), probe))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportsTheDriverItRendersWith() {
        // Into the build log: every machine's run, CI's Mesa job above all, then says which driver the gl tests ran
        // on (GALLIUM_DRIVER picks among several in one Mesa, and nothing else would show which one was used).
        System.getLogger(FrameBridgeTest.class.getName())
                .log(System.Logger.Level.INFO, "The gl tests run on OpenGL " + renderer.driver());
        assertThat(GlVersion.parse(renderer.driver().version()))
                .hasValueSatisfying(v -> assertThat(v.isAtLeast(GlVersion.FLOOR)).isTrue());
        assertThat(renderer.driver().renderer()).isNotBlank();
    }

    private static int argbAt(int x, int y) {
        Image image = view.image();
        return image == null ? 0 : image.getPixelReader().getArgb(x, y);
    }
}
