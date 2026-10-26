package io.github.evildarkarchon.andysdice.app;

import static io.github.evildarkarchon.andysdice.app.RollExpressions.valid;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.render.GlContextLostException;
import io.github.evildarkarchon.andysdice.render.GlDriver;
import io.github.evildarkarchon.andysdice.render.GlUnavailableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The runtime-failure panel (spec §3.2 "Runtime failure"): v1 is crash-only, so an uncaught exception on any of the
 * app's threads, a dead render thread or a lost GL context replaces the Tray with a panel drawn by JavaFX alone,
 * naming the cause and the log file, with Copy details and Quit, and the sidebar disabled behind it.
 *
 * <p>Each test injects its failure for real: an exception thrown and not caught on a thread named as the app's
 * threads are, which reaches the {@link FailureGuard} as the JVM's default uncaught-exception handler, the way
 * {@link Main} installs it. The app's log is installed into the test's data folder, so the panel names a real file.
 */
class FailurePanelTest extends HeadlessUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<Runnable> quits = new CopyOnWriteArrayList<>();
    private AppLog log;
    private FailureGuard guard;
    private Thread.UncaughtExceptionHandler previousHandler;

    /**
     * Installs the log and the guard as the app does, with the panel shown over this test's Roll UI and the session
     * ended after it.
     */
    @BeforeEach
    void guardTheApp() throws Exception {
        log = AppLog.install(dataFolder);
        guard = new FailureGuard(log.file());
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(guard);
        guard.attach(this::endSession, Platform::runLater);
    }

    /**
     * What the app's failure guard callback does ({@code AndysDice.start}): the failure panel first, then the
     * session's rolling ends, in the roll controller.
     */
    private void endSession(Failure failure) {
        FailureScreen.show(ui(), failure, () -> quits.add(() -> {}));
        session.controller().abandon();
    }

    /** Puts the JVM's handler and logging back. */
    @AfterEach
    void unguard() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        log.close();
    }

    @Test
    void anUncaughtExceptionOnTheRenderThreadReplacesTheTrayWithThePanel() throws Exception {
        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));

        assertThat(await(this::panelShown, WAIT)).as("the failure panel is shown").isTrue();
        FailurePanel panel = fromFx(this::panel);
        assertThat(fromFx(() -> panel.heading().getText())).isEqualTo("Andy's Dice hit a problem and has to close");
        assertThat(fromFx(() -> panel.cause().getText())).contains("IllegalStateException: the GPU went away")
                .contains("andysdice-render");
        assertThat(fromFx(() -> panel.logPath().getText())).contains(log.file().toString());
        // The Tray is gone from the window, not merely covered.
        assertThat(fromFx(() -> ui().tray().getScene())).isNull();
        assertThat(fromFx(() -> panel.getScene())).isSameAs(stage.getScene());
    }

    @ParameterizedTest
    @ValueSource(strings = {"andysdice-physics", "andysdice-audio", "andysdice-persistence"})
    void anUncaughtExceptionOnAnyOtherAppThreadShowsThePanelNamingIt(String threadName) throws Exception {
        dieOn(threadName, new IllegalArgumentException("broken on " + threadName));

        assertThat(await(this::panelShown, WAIT)).as("the failure panel is shown").isTrue();
        assertThat(fromFx(() -> panel().cause().getText()))
                .contains("IllegalArgumentException: broken on " + threadName).contains(threadName);
    }

    @Test
    void anExceptionAnEventHandlerOnTheJavaFxThreadDoesNotCatchShowsThePanel() throws Exception {
        // JavaFX hands what a runLater task or an event handler throws to the thread's uncaught-exception handler.
        Platform.runLater(() -> {
            throw new UnsupportedOperationException("a handler broke");
        });

        assertThat(await(this::panelShown, WAIT)).as("the failure panel is shown").isTrue();
        assertThat(fromFx(() -> panel().cause().getText()))
                .contains("UnsupportedOperationException: a handler broke").contains("JavaFX Application Thread");
    }

    @Test
    void theCauseAndItsTraceAreLogged() throws Exception {
        dieOn("andysdice-audio", new IllegalStateException("the mixer broke"));
        assertThat(await(this::panelShown, WAIT)).isTrue();

        String logged = Files.readString(log.file(), StandardCharsets.UTF_8);
        // System.Logger's ERROR is java.util.logging's SEVERE in the log file.
        assertThat(logged).contains("SEVERE").contains("Uncaught exception on thread andysdice-audio")
                .contains("java.lang.IllegalStateException: the mixer broke")
                .contains("FailurePanelTest");
        assertThat(logged).contains("Failure panel shown: IllegalStateException: the mixer broke");
    }

    @Test
    void theSidebarAndTheToolbarAreDisabledBehindThePanel() throws Exception {
        assertThat(fromFx(() -> ui().sidebar().isDisabled())).isFalse();

        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));
        assertThat(await(this::panelShown, WAIT)).isTrue();

        assertThat(fromFx(() -> ui().sidebar().isDisabled())).isTrue();
        assertThat(fromFx(() -> ui().tabs().isDisabled())).isTrue();
        assertThat(fromFx(() -> ui().expressionBox().isDisabled())).isTrue();
        assertThat(fromFx(() -> ui().rollButton().isDisabled())).isTrue();
        // The panel itself stays live, with Quit focused.
        assertThat(fromFx(() -> panel().isDisabled())).isFalse();
        assertThat(fromFx(() -> panel().quitButton().isFocused())).isTrue();
    }

    @Test
    void copyDetailsPutsTheCauseTheLogPathAndTheTraceOnTheClipboard() throws Exception {
        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));
        assertThat(await(this::panelShown, WAIT)).isTrue();

        onFx(() -> Clipboard.getSystemClipboard().clear());
        onFx(() -> panel().copyButton().fire());

        String copied = fromFx(() -> Clipboard.getSystemClipboard().getString());
        assertThat(copied).contains("Andy's Dice hit a problem and has to close")
                .contains("IllegalStateException: the GPU went away").contains("andysdice-render")
                .contains(log.file().toString())
                // The trace's frames carry the module's name before the class, so look for the class alone.
                .contains("FailurePanelTest.copyDetailsPutsTheCauseTheLogPathAndTheTraceOnTheClipboard");
    }

    @Test
    void quitEndsTheApp() throws Exception {
        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));
        assertThat(await(this::panelShown, WAIT)).isTrue();
        assertThat(quits).isEmpty();

        onFx(() -> panel().quitButton().fire());

        assertThat(quits).hasSize(1);
    }

    @Test
    void onlyTheFirstFailureIsShownAndALaterOneIsStillLogged() throws Exception {
        dieOn("andysdice-render", new IllegalStateException("the first"));
        assertThat(await(this::panelShown, WAIT)).isTrue();
        dieOn("andysdice-physics", new IllegalStateException("the second"));
        onFx(() -> {});

        assertThat(fromFx(() -> panel().cause().getText())).contains("the first").doesNotContain("the second");
        assertThat(fromFx(() -> ui().lookupAll("." + FailurePanel.STYLE_CLASS).size())).isEqualTo(1);
        assertThat(Files.readString(log.file(), StandardCharsets.UTF_8))
                .contains("java.lang.IllegalStateException: the second");
    }

    @Test
    void aRenderThreadThatDiesShowsThePanel() throws Exception {
        CompletableFuture<Void> renderStopped = new CompletableFuture<>();
        guard.watch(FailureGuard.RENDER_THREAD, renderStopped);
        assertThat(fromFx(this::panelShown)).isFalse();

        renderStopped.completeExceptionally(new GlContextLostException("the driver reset the GPU"));

        assertThat(await(this::panelShown, WAIT)).isTrue();
        assertThat(fromFx(() -> panel().cause().getText()))
                .contains("GlContextLostException: the driver reset the GPU").contains("andysdice-render");
    }

    @Test
    void aRenderThreadClosedAtExitIsNoFailure() throws Exception {
        CompletableFuture<Void> renderStopped = new CompletableFuture<>();
        guard.watch(FailureGuard.RENDER_THREAD, renderStopped);

        renderStopped.complete(null);
        onFx(() -> {});

        assertThat(fromFx(this::panelShown)).isFalse();
        assertThat(guard.failure()).isNull();
    }

    @Test
    void aPreSimulationJobThatThrowsEndsTheSession() throws Exception {
        RollController.PhysicsJobs broken = guard.reporting(
                job -> CompletableFuture.failedFuture(new IllegalStateException("Jolt broke")));

        broken.submit(simulator -> null);

        assertThat(await(this::panelShown, WAIT)).isTrue();
        assertThat(fromFx(() -> panel().cause().getText())).contains("IllegalStateException: Jolt broke")
                .contains("andysdice-physics");
    }

    @Test
    void aFailureBeforeThePanelIsAttachedIsShownOnceItIs() throws Exception {
        FailureGuard early = new FailureGuard(log.file());
        early.report("main", new IllegalStateException("before the window"));

        early.attach(this::endSession, Platform::runLater);

        assertThat(await(this::panelShown, WAIT)).isTrue();
        assertThat(fromFx(() -> panel().cause().getText())).contains("before the window");
    }

    @Test
    void aFailureBeforeThePanelIsAttachedStopsTheSessionRollingOnceItIs() throws Exception {
        // A Roll in motion when the failure arrives, with no panel attached to any view yet.
        roll("1d6");
        advance(FRAME);
        FailureGuard early = new FailureGuard(log.file());
        early.report("main", new IllegalStateException("before the window"));
        assertThat(fromFx(() -> rollState().underWay())).isTrue();

        early.attach(this::endSession, Platform::runLater);
        assertThat(await(this::panelShown, WAIT)).isTrue();
        onFx(() -> {});

        assertThat(fromFx(() -> rollState().status())).isInstanceOf(RollStatus.Lost.class);
        assertThat(fromFx(() -> ui().resultStrip().text())).isNotEqualTo(RollStatus.ROLLING);
        // Nothing more rolls: not Enter, nor a Roll from a Saved Roll or History entry, which the disabled box
        // doesn't stand in front of. And the Lost Roll never joins the history.
        onFx(() -> {
            ui().expressionBox().setText("1d8");
            pressEnterInBox();
        });
        onFx(() -> session.controller().roll(valid("1d4")));
        advance(30);
        assertThat(tray.shows()).isEqualTo(1);
        assertThat(fromFx(() -> history.list().getItems())).isEmpty();
    }

    @Test
    void withoutOpenGl33StartUpOpensTheWindowWithThePanelNamingTheRequirementRendererAndDriver() throws Exception {
        GlUnavailableException noGl = new GlUnavailableException("the driver offers only OpenGL 1.1.0",
                new GlDriver("GDI Generic", "Microsoft Corporation", "1.1.0"));
        List<Runnable> startupQuits = new CopyOnWriteArrayList<>();
        Stage window = fromFx(Stage::new);
        try {
            FailurePanel shown = fromFx(() -> FailureScreen.startup(window, Failure.startup(noGl, log.file()),
                    () -> startupQuits.add(() -> {})));

            assertThat(fromFx(window::isShowing)).isTrue();
            assertThat(fromFx(window::getTitle)).isEqualTo("Andy's Dice");
            assertThat(fromFx(() -> shown.heading().getText())).isEqualTo("Andy's Dice hit a problem and has to close");
            assertThat(fromFx(() -> shown.cause().getText())).contains("OpenGL 3.3 core")
                    .contains("renderer: GDI Generic").contains("vendor: Microsoft Corporation")
                    .contains("version: 1.1.0");
            assertThat(fromFx(() -> shown.logPath().getText())).contains(log.file().toString());
            RollUi startupUi = fromFx(() -> (RollUi) window.getScene().getRoot());
            assertThat(fromFx(() -> startupUi.sidebar().isDisabled())).isTrue();
            assertThat(fromFx(() -> FailureScreen.panelOf(startupUi))).isSameAs(shown);

            onFx(() -> shown.quitButton().fire());
            assertThat(startupQuits).hasSize(1);
        } finally {
            onFx(window::close);
        }
    }

    @Test
    void aPopupOpenWhenTheSessionFailsIsClosedSoNothingStaysOverThePanel() throws Exception {
        // The Notice list and the parse-error popup, both open at once.
        onFx(() -> ui().bell().fire());
        onFx(() -> {
            ui().expressionBox().setText("2d6+1d7kh1");
            pressEnterInBox();
        });
        assertThat(fromFx(() -> showingPopups().size())).isEqualTo(2);

        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));
        assertThat(await(this::panelShown, WAIT)).isTrue();

        assertThat(fromFx(HeadlessUiTest::showingPopups)).isEmpty();
        assertThat(fromFx(() -> panel().quitButton().isFocused())).isTrue();
    }

    @Test
    void aDialogOpenWhenTheSessionFailsIsClosedSoQuitCanBeReached() throws Exception {
        onFx(() -> saved.askNew("2d6"));
        assertThat(fromFx(() -> saved.dialog().isPresent())).isTrue();

        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));
        assertThat(await(this::panelShown, WAIT)).isTrue();

        assertThat(fromFx(() -> saved.dialog().isPresent())).isFalse();
        assertThat(fromFx(() -> Window.getWindows().stream().filter(Window::isShowing).toList()))
                .containsExactly(stage);
        // Nothing was saved by closing it.
        assertThat(fromFx(() -> saved.saved().rolls())).isEmpty();
    }

    @Test
    void aQueuedRollNeverStartsBehindThePanel() throws Exception {
        roll("1d6");
        onFx(() -> {
            ui().expressionBox().setText("1d8");
            pressEnterInBox();
        });
        assertThat(queuedRoll()).contains("1d8");

        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));
        assertThat(await(this::panelShown, WAIT)).isTrue();
        // Far past where the Roll in motion would have rested, and the beat after it.
        advance(30);

        assertThat(tray.shows()).isEqualTo(1);
        assertThat(queuedRoll()).isEmpty();
        assertThat(fromFx(() -> session.entry().nextChip().isVisible())).isFalse();
        // Nor can one be asked for: a Saved Roll or History entry rolled now does nothing, nor does Enter.
        onFx(() -> session.controller().roll(valid("1d4")));
        onFx(() -> {
            ui().expressionBox().setText("1d4");
            pressEnterInBox();
        });
        advance(RollController.BEAT_SECONDS + 1);
        assertThat(tray.shows()).isEqualTo(1);
        assertThat(queuedRoll()).isEmpty();
    }

    @Test
    void aRollInMotionWhenTheSessionFailsIsLostNeverRevealedNorJoinsTheHistory() throws Exception {
        // ADR-0007: a Roll interrupted before it rests is lost, because it was never shown.
        roll("1d6");
        assertThat(tray.shows()).isEqualTo(1);
        advance(FRAME);
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo(RollStatus.ROLLING);

        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));
        assertThat(await(this::panelShown, WAIT)).isTrue();
        // Far past the chain's rest: nothing plays on behind the panel.
        advance(30);

        assertThat(fromFx(() -> rollState().status())).isInstanceOf(RollStatus.Lost.class);
        // The strip no longer claims a Roll is coming: it shows nothing for a Lost Roll.
        assertThat(fromFx(() -> ui().resultStrip().text())).isEmpty();
        // Nothing is left under way, and the status says so too: the app has settled.
        assertThat(fromFx(() -> rollState().underWay())).isFalse();
        assertThat(fromFx(() -> rollState().settled())).isTrue();
        // A later Enter rolls nothing.
        onFx(() -> {
            ui().expressionBox().setText("1d8");
            pressEnterInBox();
        });
        advance(30);
        assertThat(tray.shows()).isEqualTo(1);
        assertThat(fromFx(() -> history.list().getItems())).isEmpty();
        // Nor is it kept for the next run.
        restartApp();
        assertThat(fromFx(() -> history.list().getItems())).isEmpty();
    }

    @Test
    void aRollStillPreSimulatingWhenTheSessionFailsNeverPlays() throws Exception {
        physics.setHolding(true);
        roll("1d6");

        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));
        assertThat(await(this::panelShown, WAIT)).isTrue();
        // The pre-simulation finishes after the session has failed.
        onFx(physics::finish);
        onFx(() -> {});
        advance(30);

        assertThat(tray.shows()).isZero();
        assertThat(fromFx(() -> rollState().status())).isInstanceOf(RollStatus.Lost.class);
        assertThat(fromFx(() -> ui().resultStrip().text())).isNotEqualTo(RollStatus.ROLLING);
        assertThat(fromFx(() -> history.list().getItems())).isEmpty();
    }

    @Test
    void theWindowsShortcutsDoNothingBehindThePanel() throws Exception {
        dieOn("andysdice-render", new IllegalStateException("the GPU went away"));
        assertThat(await(this::panelShown, WAIT)).isTrue();

        for (KeyCode key : List.of(KeyCode.S, KeyCode.DIGIT1, KeyCode.L, KeyCode.R)) {
            onFx(() -> {
                robot().keyPress(KeyCode.CONTROL);
                robot().keyType(key);
                robot().keyRelease(KeyCode.CONTROL);
            });
        }

        assertThat(fromFx(() -> saved.dialog().isPresent())).isFalse();
        assertThat(fromFx(() -> ui().tabs().getSelectionModel().getSelectedItem())).isSameAs(ui().historyTab());
        assertThat(fromFx(() -> panel().quitButton().isFocused())).isTrue();
    }

    /** Throws {@code error} on a new thread with that name, uncaught, and waits for the thread to end. */
    private static void dieOn(String threadName, RuntimeException error) throws InterruptedException {
        Thread thread = Thread.ofPlatform().name(threadName).daemon().start(() -> {
            throw error;
        });
        thread.join(WAIT.toMillis());
    }

    private boolean panelShown() {
        return FailureScreen.panelOf(ui()) != null;
    }

    /** The window's failure panel, or {@code null}. Call it on the JavaFX thread, inside an {@code onFx} block. */
    private FailurePanel panel() {
        return FailureScreen.panelOf(ui());
    }
}
