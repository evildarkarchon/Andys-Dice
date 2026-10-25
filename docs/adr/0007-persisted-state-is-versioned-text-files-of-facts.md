# Persisted state is three versioned text files of facts, and recordings are never kept

v1 keeps a Roll History of the last 100 Rolls and a player-ordered list of Saved Rolls, and both survive a restart along with the settings. They live in the data folder chosen by ADR-0004, as three independent UTF-8 files with no third-party dependency: `settings.properties`, `saved-rolls.txt` and `history.txt`. The history stores only facts: the time, the Roll Expression as written, the Saved Roll's name at the time (if any), and each Die's chain of Face Values. The Roll Result is recomputed from those, which ADR-0001 makes a pure function, so a stored entry can never disagree with the evaluator. Recordings are never persisted and past Rolls can't be re-watched. Real dice can't be re-watched either, and writing recordings to disk would turn ADR-0003's free-to-change recording into a file contract. JDK 27 has no JSON module. The data is flat settings plus short lists of tiny records over the grammar's closed alphabet, so a JSON library would be a dependency with no other use.

## Considered Options

- **JSON through Jackson 3.** Rejected. It means about 2 MB of modular jars and more `app\legal\` notices (ADR-0004), for data that needs neither nesting nor escaping. If a later version needs nested data, the version header gives it a clean migration point.
- **One file for everything.** Rejected. A corrupt file would then cost every kind of state at once.
- **`java.util.prefs`.** Rejected. On Windows it writes to the registry, so deleting the folder would no longer uninstall the app (ADR-0004).
- **Re-watching past Rolls**, with recordings kept in memory or on disk. Rejected for v1. It adds a second tray mode in which the tray shows the past and the result panel the present, and the on-disk form would freeze the recording format.
- **Storing totals and breakdowns in the history.** Rejected. The breakdown is derived data, and it could drift from the evaluator.

## Consequences

- **Formats.**
  - Each file starts with a version header line. Older versions are migrated forward in code.
  - `settings.properties` is a `java.util.Properties` file of flat keys: the chosen Dice Set, the window's normal bounds plus a maximised flag, the ADR-0002 quality settings, and mute and volume. An unknown key is ignored. An invalid value falls back to that key's default without discarding the rest. The Roll Expression box is never persisted.
  - `saved-rolls.txt` has one `name⇥expression` line per Saved Roll, in the player's order.
  - `history.txt` has one line per Roll, newest last: a UTC instant, the expression, the Saved Roll name (or empty), then each Die's Face Value chain in Dice Group order, for example `1>5,3,6,2`.
  - Neither the grammar nor Saved Roll names can contain control characters, so no escaping exists.
- **Unreadable files.** A corrupt or newer-version file is renamed to `<name>.bad-<timestamp>` and never overwritten. The app starts that file fresh, logs it and shows a one-time notice that doesn't block. The other files are unaffected. A Saved Roll whose expression no longer parses is kept, marked invalid and left editable.
- **Writes.** Each file is rewritten when it changes. Window geometry is written once, on close. Every write goes to a temp file and is then renamed over the old one with `ATOMIC_MOVE`, on one background writer thread, never the JavaFX thread. If the folder stops being writable mid-run, the app logs it, keeps running in memory and shows a one-time notice.
- **Location.** The data folder is chosen once at startup, by ADR-0004's rule, and never re-chosen or merged. An app-owned system property overrides it: dev runs set it to `target\data`, and tests to a temp folder.
- **One instance.** A `FileChannel.tryLock` on a lock file in the data folder makes a second launch show "Andy's Dice is already running" and exit. The two copies never talk to each other.
- **Joining the history.** A Roll is appended when its Roll Result is revealed. A Roll that fails (the retry cap or the reroll backstop is exceeded) never enters the history. A Roll interrupted by closing the app is lost, because it was never shown.
- **Only the latest recordings live.** The latest Roll's recordings stay in memory until the next Roll, and its Dice lie at rest on the tray until then. After a restart the tray starts empty, even though the history still shows the last Roll.
- **Testing.** Writing then reading back gives the same Rolls, Saved Rolls and settings. That round trip, the migration path and the set-aside rule are pure-Java tests under ADR-0005.
