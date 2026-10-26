package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.planner.PlannedThrow;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * The stand-in tray UI tests mount behind the Tray seam ({@link TraySurface}) in place of the GL renderer and its
 * frame transport (ADR-0002, ADR-0011). It draws nothing but an empty region, needs no GL, and reports where the
 * Roll it shows stands: which Throw is on the Tray, whether it is moving, held between Throws or at rest, and the
 * injected playback clock's time when each of those began.
 *
 * <p>The controller drives it exactly as it drives the real Tray: {@link #show} when a pre-simulated chain is ready,
 * then {@link #showTime} on every clock advance. Thread contract: the JavaFX Application Thread.
 */
final class StandInTray extends Region implements TraySurface {

    /** Where the Roll on the Tray stands. */
    enum Phase {
        /** Nothing rolled yet: the first-run Tray is empty. */
        EMPTY,
        /** A Throw's Dice are moving. */
        THROWING,
        /** A Throw has come to rest and the next one hasn't started (the hold #47's pickup will fill). */
        HOLDING,
        /** The last Throw is at rest: the whole Roll has landed. */
        AT_REST
    }

    /**
     * A change of phase, as the Tray reported it.
     *
     * @param phase the new phase
     * @param throwIndex the Throw on the Tray, an index into the plan's Throw chain ({@code -1} when empty)
     * @param clockTime the playback clock's time when the Tray first drew this phase
     */
    record Report(Phase phase, int throwIndex, double clockTime) {}

    private final PlaybackClock clock;
    private final List<Report> reports = new ArrayList<>();
    private final List<Look> looks = new ArrayList<>();
    private ChainPlayback showing;
    private double shownTime;
    private int shows;

    /**
     * Creates an empty stand-in tray.
     *
     * @param clock the injected playback clock, read to time-stamp each report
     */
    StandInTray(PlaybackClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        getStyleClass().add("stand-in-tray");
        reports.add(new Report(Phase.EMPTY, -1, clock.time()));
    }

    @Override
    public Node node() {
        return this;
    }

    @Override
    public void show(ChainPlayback chain) {
        showing = Objects.requireNonNull(chain, "chain");
        shows++;
        draw(0);
    }

    @Override
    public void showTime(double seconds) {
        draw(seconds);
    }

    @Override
    public void showLook(Look look) {
        looks.add(Objects.requireNonNull(look, "look"));
    }

    /**
     * Tells whether the Tray still moves on the clock after the Roll is over: never, since the stand-in has no
     * camera to settle in.
     *
     * @return {@code false}
     */
    @Override
    public boolean moving() {
        return false;
    }

    /**
     * Returns the look the Tray is drawn in, as the controller last showed it (#57): the Look tab's pick, though a
     * Tray picked while Dice move only once they rest.
     *
     * @return the latest look, or {@code null} if none has been shown
     */
    Look look() {
        return looks.isEmpty() ? null : looks.getLast();
    }

    /**
     * Returns every look the Tray has been shown, oldest first.
     *
     * @return a copy of the looks
     */
    List<Look> looks() {
        return List.copyOf(looks);
    }

    /**
     * Returns the chain on the Tray.
     *
     * @return the chain last shown, or {@code null} on the first-run Tray
     */
    ChainPlayback showing() {
        return showing;
    }

    /**
     * Returns how many Rolls the Tray has been given to play.
     *
     * @return the number of {@link #show} calls
     */
    int shows() {
        return shows;
    }

    /**
     * Returns the playback time last drawn.
     *
     * @return seconds since the shown chain's first Release, or 0 before any
     */
    double shownTime() {
        return shownTime;
    }

    /**
     * Returns where the Roll on the Tray stands now.
     *
     * @return the latest phase
     */
    Phase phase() {
        return reports.getLast().phase();
    }

    /**
     * Returns every phase change since the Tray was made, oldest first, starting with {@link Phase#EMPTY}.
     *
     * @return a copy of the reports
     */
    List<Report> reports() {
        return List.copyOf(reports);
    }

    /** Draws the chain at a playback time: notes the phase it is in, if that changed. */
    private void draw(double seconds) {
        shownTime = seconds;
        int k = showing.throwAt(seconds);
        List<PlannedThrow> chain = showing.plan().throwChain();
        double sinceRelease = seconds - showing.throwStart(k);
        Phase phase;
        if (sinceRelease < chain.get(k).recording().restTime()) {
            phase = Phase.THROWING;
        } else {
            phase = k < chain.size() - 1 ? Phase.HOLDING : Phase.AT_REST;
        }
        Report last = reports.getLast();
        // A new Roll starts a new THROWING report even when the last Roll's first Throw is the one shown.
        if (last.phase() != phase || last.throwIndex() != k || seconds == 0) {
            reports.add(new Report(phase, k, clock.time()));
        }
    }
}
