package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A render thread that dies says so (spec §3.2 "Runtime failure"): v1 is crash-only, so the app must learn that its
 * one render thread has ended, whatever ended it (a frame that threw, or the driver reporting the GL context lost),
 * and show its failure panel. Nothing is recovered: ADR-0002 allows one GL context per app run.
 *
 * <p>It starts the JVM's one render thread, so it runs in a JVM of its own (render's surefire doesn't reuse
 * forks), and it needs an OpenGL 3.3 core context, so it is {@code gl}-tagged.
 */
@Tag("gl")
class RenderThreadDeathTest {

    /** Draws nothing until it is told to fail, then throws from its next frame. */
    private static final class FailingRenderer implements FrameRenderer {

        private volatile RuntimeException failure;

        @Override
        public void init() {}

        @Override
        public void render(int width, int height) {
            RuntimeException now = failure;
            if (now != null) {
                throw now;
            }
        }

        @Override
        public void dispose() {}
    }

    @Test
    void aRenderThreadThatDiesCompletesItsStoppedFutureWithWhatEndedIt() throws Exception {
        FailingRenderer frames = new FailingRenderer();
        RenderThread renderer = RenderThread.start(new CaptureTransport(), frames);
        try {
            CompletableFuture<Void> stopped = renderer.stopped().toCompletableFuture();
            renderer.resize(8, 8);
            renderer.submit(() -> null).get(30, TimeUnit.SECONDS);
            assertThat(stopped).as("a running render thread hasn't stopped").isNotDone();

            GlContextLostException lost = new GlContextLostException("the driver reset the GPU");
            frames.failure = lost;
            renderer.requestFrame();

            assertThat(stopped).as("the stopped future fails with what ended the thread")
                    .failsWithin(30, TimeUnit.SECONDS).withThrowableOfType(ExecutionException.class)
                    .havingCause().isSameAs(lost);
            // Nothing is recovered: the dead thread takes no more work.
            assertThat(renderer.submit(() -> null)).failsWithin(30, TimeUnit.SECONDS);
        } finally {
            renderer.close();
        }
    }
}
