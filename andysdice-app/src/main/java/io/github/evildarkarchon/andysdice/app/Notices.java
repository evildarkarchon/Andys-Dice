package io.github.evildarkarchon.andysdice.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * The app's one Notice service (spec §3.2 "Notices and confirmations"). Anything that has something to tell the
 * Player without interrupting them posts a {@link Notice} here: set-aside files, skipped Player Files, an
 * unwritable data folder, no audio device, a slow GPU. Notices never pop up: they collect behind the sidebar's bell
 * ({@link NoticeBell}), which shows how many there are and is highlighted while any are unread, until the Player
 * dismisses them all.
 *
 * <p><b>Threads.</b> {@link #post} may be called from any thread. A post on the JavaFX Application Thread joins the
 * list at once; a post from any other thread joins it on the JavaFX thread soon after (through
 * {@link Platform#runLater}), in the order the posts were made. Everything else, the list and the unread count
 * included, is confined to the JavaFX thread. Posting off the JavaFX thread needs the toolkit to be running, as it
 * is from {@code Application.start} on.
 */
final class Notices {

    private static final System.Logger LOG = System.getLogger(Notices.class.getName());

    /** Posts not yet moved into {@link #notices}, oldest first; written by any thread, drained on the FX thread. */
    private final Queue<Notice> posted = new ConcurrentLinkedQueue<>();
    private final ObservableList<Notice> notices = FXCollections.observableArrayList();
    private final ObservableList<Notice> readOnly = FXCollections.unmodifiableObservableList(notices);
    private final ReadOnlyIntegerWrapper unread = new ReadOnlyIntegerWrapper(this, "unread");

    /**
     * Posts a Notice. It is logged, then shown behind the bell, unread; nothing pops up and focus stays where it is.
     *
     * @param notice the Notice
     * @throws NullPointerException if it is null
     */
    void post(Notice notice) {
        Objects.requireNonNull(notice, "notice");
        LOG.log(System.Logger.Level.INFO, "Notice: {0}{1}", notice.title(),
                notice.detail().isEmpty() ? "" : " - " + notice.detail());
        posted.add(notice);
        if (Platform.isFxApplicationThread()) {
            drain();
        } else {
            // One drain per post: a drain may find the queue already emptied by an earlier one, which is harmless.
            Platform.runLater(this::drain);
        }
    }

    /**
     * Posts a Notice made of a title and a detail; see {@link #post(Notice)}.
     *
     * @param title one short line naming what happened
     * @param detail what it means for the Player, or empty
     */
    void post(String title, String detail) {
        post(new Notice(title, detail));
    }

    /**
     * Returns the Notices behind the bell, oldest first. JavaFX thread only.
     *
     * @return a read-only live view
     */
    ObservableList<Notice> all() {
        return readOnly;
    }

    /**
     * Returns how many Notices the Player hasn't seen listed yet. JavaFX thread only.
     *
     * @return the unread count, as a property the bell follows
     */
    ReadOnlyIntegerProperty unreadProperty() {
        return unread.getReadOnlyProperty();
    }

    /**
     * Returns how many Notices are unread. JavaFX thread only.
     *
     * @return the unread count
     */
    int unread() {
        return unread.get();
    }

    /** Marks every Notice read, as when the bell's list is shown. JavaFX thread only. */
    void markAllRead() {
        unread.set(0);
    }

    /** Dismisses every Notice: the list empties and the bell shows none. JavaFX thread only. */
    void dismissAll() {
        unread.set(0);
        notices.clear();
    }

    /** Moves every waiting post into the list, as unread. Runs on the JavaFX thread. */
    private void drain() {
        List<Notice> arrived = new ArrayList<>();
        for (Notice next = posted.poll(); next != null; next = posted.poll()) {
            arrived.add(next);
        }
        if (arrived.isEmpty()) {
            return;
        }
        // Count first, so a listener on the list already sees the new unread count.
        unread.set(unread.get() + arrived.size());
        notices.addAll(arrived);
    }
}
