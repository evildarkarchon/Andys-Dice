package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.sound.AudioSink;
import io.github.evildarkarchon.andysdice.sound.Impact;
import io.github.evildarkarchon.andysdice.sound.ImpactObserver;
import io.github.evildarkarchon.andysdice.sound.SinkException;
import io.github.evildarkarchon.andysdice.sound.SoundCategory;
import io.github.evildarkarchon.andysdice.sound.SoundOutput;
import io.github.evildarkarchon.andysdice.sound.SoundThrow;
import io.github.evildarkarchon.andysdice.sound.TraySounds;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javafx.scene.AccessibleAttribute;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rolls make sound (#59, spec §3.5, ADR-0012): each contact of every Throw plays on a line opened for the Roll and
 * closed after its last tail; only contacts sound; a Roll sounds like the Tray it was thrown into (#67); no device
 * means silence and one Notice; and mute and volume sit in the Settings tab, saved in {@code settings.properties}.
 */
class ClatterTest extends HeadlessUiTest {

    /**
     * Real time as the clock's follower and the audio thread see it: kept equal to the playback time, so every block
     * is rendered for the clock's time now and the sound can't run ahead of, or behind, the frames the test plays.
     */
    private final AtomicLong now = new AtomicLong();
    private final FakeOpener opener = new FakeOpener();
    private Persistence persistence;
    private SettingsStore settings;
    private SoundOutput output;
    private SoundSettings controls;
    /** Every impact the mixer has started, as the audio thread renders it. */
    private final List<Impact> impacts = new CopyOnWriteArrayList<>();

    /** A sink that keeps every block, queues nothing and can be told to fail. */
    private final class FakeSink implements AudioSink {
        final List<short[]> blocks = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        volatile int failAfter = Integer.MAX_VALUE;

        @Override
        public int queuedFrames() {
            return 0;
        }

        @Override
        public void write(short[] block) throws SinkException {
            if (blocks.size() >= failAfter) {
                throw new SinkException("unplugged", null);
            }
            blocks.add(block.clone());
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean heardSomething() {
            for (short[] block : blocks) {
                for (short sample : block) {
                    if (sample != 0) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** Opens fake sinks, or fails as a machine with no device does. */
    private final class FakeOpener implements AudioSink.Opener {
        final List<FakeSink> sinks = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        volatile boolean noDevice;
        volatile int failNextAfter = Integer.MAX_VALUE;

        @Override
        public AudioSink open() throws SinkException {
            attempts.incrementAndGet();
            if (noDevice) {
                throw new SinkException("no device", null);
            }
            FakeSink sink = new FakeSink();
            sink.failAfter = failNextAfter;
            failNextAfter = Integer.MAX_VALUE;
            sinks.add(sink);
            return sink;
        }
    }

    @BeforeEach
    void prepareSound() {
        persistence = new Persistence(dataFolder, notice -> {});
        settings = new SettingsStore(persistence);
    }

    /** Starts the sound as the app does, on the stand-ins, and waits for its start-up try of the device. */
    private void startSound() throws Exception {
        onFx(() -> {
            // Before the follower's own listener, so it stamps each advance with the time it moved to.
            clock.addListener(c -> now.set(Math.round(c.time() * 1e9)));
            output = Clatter.output(clock, new SplittableRandom(5), opener, session.notices(), now::get,
                    new ImpactObserver() {
                        @Override
                        public void started(Impact impact) {
                            impacts.add(impact);
                        }
                    });
            Clatter.attach(session, output);
            controls = SoundSettings.attach(ui(), settings, output);
        });
        assertThat(await(() -> opener.attempts.get() == 1, Duration.ofSeconds(10))).isTrue();
    }

    @AfterEach
    void stopSound() {
        if (output != null) {
            output.close();
        }
        persistence.close();
    }

    private boolean lineClosed(int index) {
        return opener.sinks.size() > index && opener.sinks.get(index).closed;
    }

    private boolean lineOpen() {
        return !opener.sinks.isEmpty() && !opener.sinks.getLast().closed;
    }

    private int blocksWritten() {
        return opener.sinks.stream().mapToInt(sink -> sink.blocks.size()).sum();
    }

    /**
     * Plays the Roll to rest as {@link #playToRest} does, but frame by frame behind the fake device: once its line is
     * open, each frame waits for the audio thread to write a block rendered at that frame's time. A real clock moves
     * at the device's pace; the test's would otherwise outrun the audio thread and every contact would be too late.
     */
    private void playToRestHeard() throws Exception {
        assertThat(await(this::lineOpen, Duration.ofSeconds(10))).as("a line opened for the Roll").isTrue();
        for (int frame = 0; fromFx(() -> rollState().underWay()); frame++) {
            assertThat(frame).as("frames played").isLessThan(10_000);
            int written = blocksWritten();
            advance(FRAME);
            // Two blocks: the one being rendered may have read the clock before this frame.
            assertThat(await(() -> blocksWritten() > written + 1 || !lineOpen(), Duration.ofSeconds(10))).isTrue();
        }
    }

    @Test
    void eachThrowOfTheChainIsQueuedAtTheClockTimeItsReleasePlays() throws Exception {
        faces.then(1, 4, 5);
        simulator.clatter = true;
        List<SoundThrow> queued = new CopyOnWriteArrayList<>();
        onFx(() -> clock.advance(2.5));
        // As Clatter.attach does: each chain once, as its Roll's status becomes Playing.
        onFx(() -> addRollStateListener(RollState.statusChanges(status -> {
            if (status instanceof RollStatus.Playing playing) {
                queued.addAll(Clatter.throwsOf(playing.chain(), playing.start()));
            }
        })));

        roll("1d6rr1+1d8");
        playToRest();

        ChainPlayback chain = fromFx(() -> tray.showing());
        assertThat(queued).hasSize(chain.plan().throwChain().size()).hasSizeGreaterThan(1);
        for (int k = 0; k < queued.size(); k++) {
            SoundThrow sound = queued.get(k);
            assertThat(sound.start()).isEqualTo(2.5 + chain.throwStart(k));
            assertThat(sound.recording()).isSameAs(chain.plan().throwChain().get(k).recording());
            assertThat(sound.tray()).isEqualTo(new TraySounds(SoundCategory.FELT_FLOOR, SoundCategory.WOOD_WALL));
        }
        assertThat(queued.getFirst().kinds()).containsExactly(DieKind.D6, DieKind.D8);
        assertThat(queued.get(1).kinds()).containsExactly(DieKind.D6);
    }

    /** Fills the Look tab as start-up does, over this test's settings. */
    private LookTab attachLook() throws Exception {
        return fromFx(() -> session.attachLook(LookCatalogue.load(dataFolder, session.notices()::post), settings));
    }

    /** Picks a Tray in the Look tab, as the Player's click does. */
    private void pickTray(LookTab look, String name) throws Exception {
        onFx(() -> look.trayButtons().stream().filter(b -> b.getAccessibleText().startsWith("Tray " + name))
                .findFirst().orElseThrow().fire());
    }

    /** The floor categories of every impact heard from the given one on. */
    private List<SoundCategory> floorsHeard(int from) {
        return impacts.subList(from, impacts.size()).stream().map(Impact::category).toList();
    }

    @Test
    void aRollSoundsLikeTheTrayItWasThrownIntoWhateverTrayIsPickedWhileItPreSimulatesOrPlays() throws Exception {
        LookTab look = attachLook();
        startSound();
        simulator.clatter = true;
        physics.setHolding(true);

        // Thrown into the first-run Tray, Green felt; Leather hexagon is picked while it pre-simulates.
        roll("2d6");
        pickTray(look, "Leather hexagon");
        onFx(physics::finish);
        // Then Walnut board while its Dice move.
        advance(FRAME);
        pickTray(look, "Walnut board");
        playToRestHeard();

        assertThat(await(() -> lineClosed(1), Duration.ofSeconds(10))).isTrue();
        assertThat(floorsHeard(0)).as("the Roll's contacts, in Green felt's felt floor").isNotEmpty()
                .containsOnly(SoundCategory.FELT_FLOOR);

        // The next Roll is thrown into the Tray picked last, and sounds like it.
        int heard = impacts.size();
        roll("2d6");
        onFx(physics::finish);
        playToRestHeard();

        assertThat(await(() -> lineClosed(2), Duration.ofSeconds(10))).isTrue();
        assertThat(floorsHeard(heard)).as("the next Roll's contacts, in Walnut board's wood floor").isNotEmpty()
                .containsOnly(SoundCategory.WOOD_FLOOR);
    }

    @Test
    void aRollIsHeardOnALineOpenedForItAndClosedAfterTheLastTail() throws Exception {
        startSound();
        simulator.clatter = true;

        roll("2d6");
        playToRestHeard();

        assertThat(await(() -> lineClosed(1), Duration.ofSeconds(10))).isTrue();
        assertThat(opener.sinks.get(1).heardSomething()).isTrue();
        assertThat(opener.sinks).hasSize(2);
    }

    @Test
    void onlyContactsMakeSoundTheReleaseThePickupAndTheUiAreSilent() throws Exception {
        startSound();
        // No contacts: a Release, a Reroll Throw's pickup and the Dice's flight make no sound.
        faces.then(1, 4);
        roll("1d6rr1");
        playToRest();
        // Nor does the UI.
        onFx(() -> ui().dieButtons().getFirst().fire());
        onFx(() -> ui().tabs().getSelectionModel().select(ui().settingsTab()));
        onFx(() -> controls.mute().fire());

        assertThat(fromFx(() -> rollState().status())).isInstanceOf(RollStatus.Rested.class);
        assertThat(opener.attempts.get()).as("only the start-up try of the device").isEqualTo(1);
    }

    @Test
    void noDeviceGivesSilenceAndOneNoticeAndIsTriedAgainAtTheNextThrow() throws Exception {
        opener.noDevice = true;
        startSound();
        simulator.clatter = true;

        roll("1d6");
        playToRest();
        roll("1d6");
        playToRest();

        assertThat(await(() -> opener.attempts.get() == 3, Duration.ofSeconds(10))).isTrue();
        List<Notice> notices = fromFx(() -> List.copyOf(session.notices().all()));
        assertThat(notices).extracting(Notice::title).containsExactly(Clatter.NO_DEVICE_TITLE);
        assertThat(opener.sinks).isEmpty();
    }

    @Test
    void aDeviceThatFailsMidRunIsRetriedSilentlyAtTheNextThrow() throws Exception {
        startSound();
        simulator.clatter = true;
        opener.failNextAfter = 1;

        roll("1d6");
        playToRest();
        assertThat(await(() -> lineClosed(1), Duration.ofSeconds(10))).isTrue();
        roll("1d6");
        playToRestHeard();

        assertThat(await(() -> lineClosed(2), Duration.ofSeconds(10))).isTrue();
        assertThat(opener.sinks.get(2).heardSomething()).isTrue();
        assertThat(fromFx(() -> List.copyOf(session.notices().all()))).isEmpty();
    }

    @Test
    void theSettingsTabHasMuteAndVolumeUnmutedAtSeventyPercentByDefault() throws Exception {
        startSound();

        assertThat(fromFx(() -> SettingsPane.of(ui()).getChildren())).contains(controls.node());
        assertThat(fromFx(() -> controls.node().getChildren())).contains(controls.mute());
        assertThat(fromFx(() -> ((Label) controls.node().getChildren().getFirst()).getText())).isEqualTo("Sound");
        assertThat(fromFx(() -> controls.mute().isSelected())).isFalse();
        assertThat(fromFx(() -> controls.volume().getValue())).isEqualTo(70);
        assertThat(fromFx(() -> controls.volumeText().getText())).isEqualTo("70%");
        assertThat(output.muted()).isFalse();
        assertThat(output.volume()).isEqualTo(0.7);
    }

    @Test
    void muteAndVolumeHaveAccessibleNamesAndWorkFromTheKeyboardAndAreSaved() throws Exception {
        startSound();
        onFx(() -> ui().tabs().getSelectionModel().select(ui().settingsTab()));
        pulse(stage.getScene());

        assertThat(fromFx(() -> controls.mute().queryAccessibleAttribute(AccessibleAttribute.TEXT)))
                .isEqualTo("Mute sound");
        assertThat(fromFx(() -> controls.volume().queryAccessibleAttribute(AccessibleAttribute.TEXT)))
                .isEqualTo("Volume");
        assertThat(fromFx(() -> controls.mute().isFocusTraversable() && controls.volume().isFocusTraversable()))
                .isTrue();

        onFx(() -> controls.mute().requestFocus());
        onFx(() -> robot().keyType(KeyCode.SPACE));
        onFx(() -> controls.volume().requestFocus());
        onFx(() -> robot().keyType(KeyCode.LEFT));
        onFx(() -> robot().keyType(KeyCode.LEFT));

        assertThat(fromFx(() -> controls.mute().isSelected())).isTrue();
        assertThat(fromFx(() -> controls.volume().getValue())).isEqualTo(60);
        assertThat(fromFx(() -> controls.volumeText().getText())).isEqualTo("60%");
        assertThat(output.muted()).isTrue();
        assertThat(output.volume()).isEqualTo(0.6);
        persistence.flush();
        List<String> file = Files.readAllLines(dataFolder.resolve(Settings.FILE.name()), StandardCharsets.UTF_8);
        assertThat(file).contains("sound.muted=true", "sound.volume=60");
    }

    @Test
    void savedMuteAndVolumeAreRestoredAtStartUp() throws Exception {
        onFx(() -> {
            settings.set(Clatter.MUTED, true);
            settings.set(Clatter.VOLUME, 35);
        });

        startSound();

        assertThat(fromFx(() -> controls.mute().isSelected())).isTrue();
        assertThat(fromFx(() -> controls.volume().getValue())).isEqualTo(35);
        assertThat(output.muted()).isTrue();
        assertThat(output.volume()).isEqualTo(0.35);
    }
}
