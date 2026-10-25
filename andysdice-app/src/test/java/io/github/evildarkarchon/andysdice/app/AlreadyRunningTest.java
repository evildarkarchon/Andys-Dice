package io.github.evildarkarchon.andysdice.app;

import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.fromFx;
import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.onFx;
import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.pulse;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A second launch (spec §3.6 "One instance"): while another copy holds the data folder's lock, the launch says
 * "Andy's Dice is already running" in a small window of its own and exits once that is dismissed. Headless
 * (ADR-0011), with the lock held by this JVM.
 */
class AlreadyRunningTest {

    @TempDir
    Path dataFolder;

    private Stage stage;
    private final AtomicInteger exits = new AtomicInteger();

    @BeforeAll
    static void startToolkit() {
        HeadlessUiTest.startToolkit();
    }

    @AfterEach
    void closeWindow() throws Exception {
        if (stage != null) {
            onFx(stage::close);
        }
    }

    @Test
    void whileAnotherCopyHoldsTheLockTheLaunchSaysSoAndExitsWhenDismissed() throws Exception {
        try (InstanceLock running = InstanceLock.tryAcquire(dataFolder).orElseThrow()) {
            assertThat(running.file()).exists();
            // What Main does on a second launch: the lock is held, so the app shows the message instead.
            assertThat(InstanceLock.tryAcquire(dataFolder)).isEmpty();
            show();

            assertThat(fromFx(() -> stage.isShowing())).isTrue();
            assertThat(fromFx(() -> stage.getTitle())).isEqualTo("Andy's Dice");
            assertThat(fromFx(() -> texts(stage.getScene().getRoot()))).contains("Andy's Dice is already running");
            assertThat(exits).hasValue(0);

            onFx(() -> okButton().fire());

            assertThat(exits).hasValue(1);
            assertThat(fromFx(() -> stage.isShowing())).isFalse();
        }
    }

    @Test
    void enterDismissesItFromTheKeyboard() throws Exception {
        show();

        assertThat(fromFx(() -> okButton().isFocused())).isTrue();
        onFx(() -> new Robot().keyType(KeyCode.ENTER));
        onFx(() -> {});

        assertThat(exits).hasValue(1);
    }

    @Test
    void escapeDismissesItToo() throws Exception {
        show();

        onFx(() -> new Robot().keyType(KeyCode.ESCAPE));
        onFx(() -> {});

        assertThat(exits).hasValue(1);
    }

    @Test
    void itIsThemedLikeTheApp() throws Exception {
        show();

        assertThat(fromFx(() -> stage.getScene().getRoot().getStyleClass())).contains(Stylesheet.ROOT_CLASS);
        assertThat(fromFx(() -> stage.getScene().getRoot().getStylesheets())).contains(Stylesheet.uri());
    }

    private void show() throws Exception {
        onFx(() -> {
            stage = new Stage();
            AlreadyRunning.show(stage, exits::incrementAndGet);
        });
        pulse(stage.getScene());
    }

    private Button okButton() {
        return (Button) stage.getScene().lookup("." + AlreadyRunning.OK_CLASS);
    }

    /** Every Labeled's text under a node, depth first. */
    private static List<String> texts(Node node) {
        java.util.ArrayList<String> found = new java.util.ArrayList<>();
        if (node instanceof Labeled labeled) {
            found.add(labeled.getText());
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> found.addAll(texts(child)));
        }
        return found;
    }
}
