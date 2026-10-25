package io.github.evildarkarchon.andysdice.render;

import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * A dev tool (ticket #33): a JavaFX window showing the test scene's flat-shaded cube, turning, drawn on the
 * render thread and presented through the frame bridge. Resize the window to watch the render target follow.
 *
 * <p>Run from the reactor with {@code mvn -pl andysdice-render -am process-classes exec:exec}. An optional
 * argument is a number of seconds after which it prints how many frames it presented and exits, for a
 * scripted check. Without OpenGL 3.3 it prints the reason and exits with status 1.
 */
public final class TestSceneWindow extends Application {

    private RenderThread renderer;

    /** Created by {@link Application#launch}. */
    public TestSceneWindow() {}

    /**
     * Launches the window.
     *
     * @param args optionally, the seconds to run before exiting
     */
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        FrameView view = new FrameView();
        TestScene scene = new TestScene();
        try {
            renderer = RenderThread.start(view.transport(), scene);
        } catch (GlUnavailableException e) {
            System.err.println(e.getMessage());
            Platform.exit();
            // The JavaFX launcher would otherwise exit 0 once the toolkit stops.
            System.exit(1);
            return;
        }
        System.out.println("OpenGL: " + renderer.driver());
        view.setOnTargetSizeChanged(renderer::resize);

        long started = System.nanoTime();
        AnimationTimer spin = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // 30 degrees a second, one frame asked for per pulse: the on-demand path at playback rate.
                scene.setYaw(35 + (now - started) / 1e9 * 30);
                renderer.requestFrame();
            }
        };
        spin.start();

        stage.setTitle("Andy's Dice: frame bridge test");
        stage.setScene(new Scene(new StackPane(view), 1280, 800));
        stage.setMinWidth(320);
        stage.setMinHeight(200);
        stage.show();

        List<String> args = getParameters().getRaw();
        if (!args.isEmpty()) {
            double seconds = Double.parseDouble(args.getFirst());
            Timeline exit = new Timeline(new KeyFrame(Duration.seconds(seconds), event -> {
                System.out.println("frames presented: " + view.framesPresented() + " in " + seconds + " s");
                Platform.exit();
            }));
            exit.play();
        }
    }

    @Override
    public void stop() {
        if (renderer != null) {
            renderer.close();
        }
    }
}
