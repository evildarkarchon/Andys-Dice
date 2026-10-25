# Andy's Dice v1 specification

This is the hand-off definition of v1. It states what v1 does, as the Player sees it, and links to the
decisions behind it. It doesn't restate them.

- **Decisions** live in [`docs/adr/`](adr/), each in exactly one ADR. This file gists each one in a line and
  links it. Where they seem to differ, the ADR wins.
- **Vocabulary** is [`GLOSSARY.md`](../GLOSSARY.md). Capitalised terms (Die, Throw, Roll Result, …) are used
  as defined there.
- **The Roll Expression grammar** is [`docs/roll-expression-grammar.md`](roll-expression-grammar.md).
- **Decisions that no ADR records** (the Roll UI, persistence details) are stated here in full, because ticket
  comments aren't a hand-off artifact.

The map that produced this is
[Wayfinder: Andy's Dice v1 spec](https://github.com/evildarkarchon/Andys-Dice/issues/1). Every decision on it
is closed.

## 1. What v1 is

A desktop app that performs animated, realistic-looking D&D dice rolls for one Player at the table. The Player
types a Roll Expression (`4d6kh3`, `1d20+5 adv`, `8d6`), presses Enter, and watches real-looking Dice tumble
through a Tray, collide, clatter and come to rest. The Roll Result appears when they do, and it always matches
the Numerals showing.

- **Dice:** the 7-piece D&D set: d4, d6, d8, d10, percentile d10, d12 and d20. A d100 is a Percentile Roll.
- **Realism:** rounded edges, real numeral layouts, engraved and inked Numerals, selectable Dice Sets
  (including translucent and metal ones), physical tumbling with Dice colliding, a felt, leather or wood Tray,
  soft shadows and reflections, and contact-matched sound.
- **Stack:** OpenJDK 27 and JavaFX 27, with our own OpenGL 3.3 renderer (LWJGL) and Jolt physics (jolt-jni),
  built with Maven.
- **Hardware:** runs at 60 fps on an Iris Xe-class integrated GPU at the default Quality Preset.
- **Platform:** published for Windows x64 as a portable zip. The code stays macOS- and Linux-capable.
- **Use:** single Player, local, no networking. Everything is generated in code: no external 3D art.

## 2. Not in v1

- Multiplayer or shared rolls, VTT integrations (Foundry, Roll20), Discord bots, character sheets and
  streaming overlays.
- Mobile and web targets.
- Novelty dice (d2, d3, Fudge dice, sphere d100).
- Exploding dice (`!`, `!!`, `!p`). Rerolls are in.
- Published macOS and Linux builds (`.app`, signing, notarization, tarballs). The build only stays capable of
  them (ADR-0004).
- Re-watching a past Roll, an installer, code signing, a Custom quality preset, a high-refresh mode, player
  sounds, an in-app Dice Set or Tray editor, and hot reload of Player Files.

## 3. Behaviour

### 3.1 Rolling

**The grammar.** A Roll Expression is a flat, case-insensitive, signed sum of Dice Groups and Modifiers, with
keep rules (`kh`/`kl`), rerolls (`ro` once, `rr` until) and a trailing `adv`/`dis`. Its full definition, its
caps and every error with its span are in [the grammar reference](roll-expression-grammar.md).

**A Roll, from Enter to rest:**

1. **Parse.** An invalid Roll Expression fails as a whole and rolls nothing (see [§3.2](#32-the-roll-ui) for
   how the error shows).
2. **Choose.** The Face Value generator picks every Face Value up front, including each Reroll's next value
   ([ADR-0001](adr/0001-rng-chooses-face-values.md)).
3. **Pre-simulate.** The whole chain of Throws (the first Throw, every Reroll Throw, any Cocked Rethrow) is
   simulated headless on the physics worker before anything moves:
   - Each Throw retries silently, as a whole, for up to 24 Attempts until every Die rests flat in the Tray.
     A Die settling cocked gets up to 3 Settle Nudges within the Attempt
     ([ADR-0003](adr/0003-jolt-pre-simulates-throws-behind-engine-neutral-recording.md),
     [ADR-0013](adr/0013-throws-reach-a-flat-rest-by-release-friction-and-nudges.md)).
   - If all 24 fail, the best Attempt is kept and only its Cocked Dice are thrown again in a visible Cocked
     Rethrow, at most 3 times. Past that, or if no Attempt came to rest at all, the Roll fails. A Failed Roll
     never shows a fudged result and never enters the Roll History ([§3.2](#32-the-roll-ui) shows how it
     appears).
   - Each Die's numbering is turned by its Numbering Rotation so the chosen Numeral is the one that landed up.
4. **Play back.** The Recordings play on JavaFX's 60 Hz pulse, with sound mixed live from their contacts. A
   Die being rerolled glows, then lifts out along an arc, and the other Dice stay where they lie as Frozen Dice
   ([ADR-0006](adr/0006-rerolls-are-reroll-throws-of-the-same-dice.md),
   [ADR-0010](adr/0010-trays-are-data-files-over-one-footprint-under-a-chosen-light-rig.md)).
5. **Reveal.** The Roll Result appears only when the last Throw is at rest. The Roll then joins the Roll
   History. Its Dice stay on the Tray until the next Roll.

**Dice per Throw.** At most 20 physical Dice. A Percentile Roll counts 2, Advantage counts 2, and unkept Dice
count too.

### 3.2 The Roll UI

Layout C, "Notebook sidebar", from the
[Roll UI prototype](https://github.com/evildarkarchon/Andys-Dice/tree/prototype/roll-ui/prototypes/roll-ui)
(screenshots `C-*.png`), as resolved in
[How should the v1 Roll UI look and behave?](https://github.com/evildarkarchon/Andys-Dice/issues/19#issuecomment-6393081238).
Its verification is [ADR-0011](adr/0011-ui-verified-on-javafx-headless-platform-without-testfx.md). The
window title is "Andy's Dice". The remaining behaviours (failed Rolls, runtime failures, keyboard,
accessibility, window sizes, first run, locale, the Cocked Rethrow and glyphs) were settled in
[What are the Roll UI's remaining behaviours?](https://github.com/evildarkarchon/Andys-Dice/issues/24).

**Window**
- **Default size:** 1280 × 800, shrunk to 90% of the primary screen's usable area if it doesn't fit, and
  centred. Saved bounds and the maximised flag then take over ([§3.6](#36-persistence)).
- **Minimum size:** 960 × 600 (the 340 px sidebar plus a Tray at least 620 px wide).
- **Display scaling:** Windows display scaling is honoured. Both sizes are logical pixels. The render target
  follows physical pixels, under the render-target cap ([§4](#4-caps-targets-and-tunables)), and a move
  between monitors with different scales is handled.

**Layout**
- **Left sidebar:** a header (the app name and the Notice bell) above four tabs: **Saved**, **History**
  (selected at start), **Look** and **Settings**.
- **Toolbar** across the top of the main area:
  - the Roll Expression box;
  - a die strip (d4 d6 d8 d10 d12 d20 d%), each button appending `+1dX`, or `1dX` when the box is empty;
  - the Roll button;
  - a "next: …" chip while a Roll is queued.
- **The Tray** fills the rest of the main area.
- **The result strip**, under the Tray, reads "Rolling…" while Dice move. At rest it shows:
  - the total, large;
  - one block per term exactly as written (`4d6rr1kh3`, `1d20 adv`), with its subtotal and its Dice as chips;
  - Modifiers as signed numbers;
  - on the right, the expression, the Saved Roll's name if any, the time and the number of Throws. Every
    Throw counts, Cocked Rethrows included.
- **Breakdown marks:**
  - A rerolled Die stacks its earlier Face Values as faded chips behind the final one, with a ↻ badge (↻×N
    for several rounds).
  - A Die that isn't kept is faded and slashed.
  - The two marks never look alike, so a Die can be both.
  - Hovering a chip spells out its Face Value Chain ("d6: showed [1, 1, 6], the last counts · not kept").

**Entry**
- Enter or the Roll button rolls. The box and every Roll control stay editable while Dice move.
- **Queued Roll:** Enter while Dice move queues the next Roll. There is at most one, and a later Enter
  replaces it. It starts about 0.9 s after the current Roll Result is revealed (a tunable). Rolling from a
  Saved Roll or a Roll History entry queues the same way.
- **Errors** are checked on Enter only, never live:
  - the box selects the error's span (a zero-width span anchors at its position);
  - a red popup under the span's start shows the one message;
  - typing dismisses the popup;
  - nothing is rolled or queued.
- **After a Roll is accepted**, the box keeps its expression with all of it selected, so typing replaces it
  and Enter rolls it again. A die-strip button still appends, even to a selected expression. A click on a
  Saved Roll or a Roll History entry replaces the box's contents.

**Failed Roll**
- A Failed Roll is known before anything moves, because the whole chain is pre-simulated
  ([§3.1](#31-rolling)). Nothing moves, and the Tray keeps the previous Roll's Dice.
- The result strip shows a message where the total would go:
  - past the Cocked Rethrows, or with no Attempt at rest: "The Dice wouldn't settle. Nothing was rolled.
    Press Enter to try again.";
  - past the 100-round reroll backstop: "Too many rerolls. Nothing was rolled."
- It is neither a Notice nor the parse-error popup. The box keeps the expression, the failure is logged, and
  nothing joins the Roll History. A Queued Roll that fails shows the same message.

**Fast-forward**
- There is no skip: every Die is always seen landing.
- Holding **Shift alone** plays the whole chain at ×4: Throws, the reroll pickup and camera easing. Releasing
  Shift returns to normal speed.
- Fast-forward starts only once Shift has been held for about 0.2 s (a tunable). If any other key is pressed
  during the hold, that hold never fast-forwards. Typing `+`, `%`, `<`, `>` or capitals in the box therefore
  never speeds the Dice up.

**Cocked Rethrow**
- It looks exactly like a Reroll Throw: the camera eases out, the Cocked Dice glow and lift, and the camera
  settles in again. It adds nothing to the Breakdown; the number of Throws is its only trace.

**Camera**
- **Settle-in:** the camera frames the whole Tray from a fixed 3/4 view while Dice tumble, then eases in on the
  Dice at rest. Before a Reroll Throw it eases back out.
- **Player control:** drag on the Tray to orbit and use the wheel to zoom, at any time, mid-Throw included.
  When the next Throw starts, any orbit or zoom eases back to the automatic camera over about 0.4 s. A
  double-click on the resting Tray resets it at once.
- **Limits:** yaw is free all the way round; pitch runs from straight down to about 20° above the floor; zoom
  runs from 0.7× to 3× of the automatic framing. The exact numbers are tunables.

**Saved tab**
- The Saved Rolls, in the Player's own order, each showing its name and expression.
- **+ New Saved Roll** opens a dialog pre-filled with the box's expression.
- A click copies a Saved Roll into the box. A double-click rolls it, and the Roll is recorded with the Saved
  Roll's name.
- ↑/↓ reorder.
- ✎ edits the name and expression inline. A bad name ([§3.6](#36-persistence)) or an expression that doesn't
  parse is refused with its message, and an expression error's span is selected.
- 🗑 deletes after a modal confirmation; Cancel is the default.
- A Saved Roll that no longer parses shows ⚠ and its message, won't roll, and stays editable.
- **Empty:** "No Saved Rolls yet. Use + New Saved Roll, or right-click a History entry."

**History tab**
- **Rows:** newest first, grouped under day headers ("Today", "Yesterday", "Wed 21 Oct"). Each row shows:
  - the local time (`HH:mm`), with the full date and time on hover;
  - the expression as written;
  - the total;
  - the Saved Roll's name at the time, if any.
- **Actions:**
  - A click copies the entry into the box, and a double-click rolls it again (queuing if Dice are moving).
  - A right-click offers **Roll again**, **Copy into the box** and **Save as a Saved Roll…**.
- **The foot** shows "n of 100 kept" and **Clear all…**, behind a modal confirmation (Cancel is the default).
- Single entries can't be deleted. Only the latest Roll's Breakdown is shown; a past Roll shows its total.
- **Empty:** "No Rolls yet." The foot reads "0 of 100 kept", and **Clear all…** is disabled.
- **Dates and times** are fixed English, whatever the system locale, as is all UI text. Day headers read
  "Today", "Yesterday", then "Wed 21 Oct", adding the year when it isn't the current one ("Wed 21 Oct 2025").
  Times are 24-hour `HH:mm` in the system time zone. Totals have no grouping separators.

**Look tab**
- **Dice Set:** a swatch grid of the 14 built-ins in order ([Appendix B](#appendix-b-built-in-dice-sets)),
  then the Player's own, marked "yours". Clicking a swatch picks it.
- **Tray:** the built-ins, then the Player's own.
- **Light Rig:** Studio, Lamp or Window.
- Player Files that were skipped stay listed under their section, with the reason, for as long as the app runs.

**Settings tab**
- **Quality Preset:** Low, Medium (the default) or High. There is no Custom and no per-lever control
  ([ADR-0014](adr/0014-performance-is-budgeted-as-scaled-gpu-time-and-checked-by-a-bench.md)). This replaces
  #19's per-lever settings, which came before ADR-0014.
- **Sound:** mute, and volume (default unmuted at 70%).

**Notices and confirmations**
- **Notices never pop up.** They collect behind the header's bell, which shows a count and is highlighted
  while any are unread. Clicking it lists them, with **Dismiss all**. Notices come from:
  - set-aside files;
  - skipped Player Files;
  - an unwritable data folder;
  - no audio device;
  - a slow GPU, suggesting a lower Quality Preset. The preset is never changed automatically.
- **Destructive actions** (deleting a Saved Roll, clearing the history) use modal confirmations with Cancel
  as the default.

**First run**
- The Tray is empty, and the result strip reads "Type a Roll Expression, or click a die above."
- The box is empty, with the prompt text `e.g. 1d20+5`, and has focus. The History tab is selected.
- The exact wording of every message in this section is a tunable.

**Runtime failure**
- v1 is crash-only. An uncaught exception on any of the app's threads, a dead render thread or a lost GL
  context (where the driver reports one) ends the session. Nothing is recovered in-process: ADR-0002 allows one
  GL context per app run.
- The Tray area is replaced by a panel drawn by JavaFX alone: "Andy's Dice hit a problem and has to close",
  the one-line cause, the log file's path, **Copy details** and **Quit**. The sidebar is disabled.
- The same panel is the fail-fast at start-up when a GL 3.3 core context can't be created
  ([ADR-0002](adr/0002-embedded-gl-renderer-public-api-bridge.md)), naming the requirement and the detected
  renderer and driver.
- Nothing persisted is lost: writes are atomic and only finished Rolls join the Roll History.
- Sound failures stay silent ([§3.5](#35-sound)), and persistence failures stay Notices.

**Keyboard**
- **Esc** does the first of these that applies:
  1. closes an open popup or dialog (a dialog's Esc is Cancel);
  2. cancels an inline ✎ edit;
  3. cancels the Queued Roll.

  Otherwise it does nothing. It never skips.
- **Focus order:** the box, the die strip, the Roll button, the sidebar's tabs, the open tab's content, the
  bell. The box has focus at start and after every Roll.
- **Shortcuts:** there are no single-letter shortcuts, because they would collide with typing.
  - Ctrl+1 to Ctrl+4 pick a tab.
  - Ctrl+L focuses the box and selects its contents.
  - Ctrl+S opens **+ New Saved Roll**.
  - Ctrl+R moves focus to the result strip.
- **Lists (Saved, History):**
  - ↑/↓ move the selection.
  - Space copies the selected entry into the box (a click), and Enter rolls it (a double-click).
  - The Menu key or Shift+F10 opens the History context menu.
  - On a Saved Roll, F2 starts ✎ editing, Alt+↑/↓ reorder, and Delete asks to delete it.
- **Look tab:** the swatch grids and lists move with the arrow keys, and Space or Enter picks.
- **The bell** opens with Space or Enter.
- **Keyboard-only floor:** every action reachable by mouse is reachable by keyboard, except orbiting and
  zooming the camera. The automatic camera is complete without them.

**Accessibility**
- **Contrast:** WCAG 2.2 AA on the one fixed dark theme:
  - 4.5:1 for text, the faded chips' Face Values included;
  - 3:1 for icons, focus rings, the ↻ badge and the slash.

  Colour is never the only signal. A pure-Java test computes the ratios from the palette constants. The
  rendered Tray and Dice are outside it. v1 ignores the system's light/dark scheme and has no Windows
  high-contrast mode.
- **Reduced motion** follows the system, through JavaFX's `Platform.Preferences.isReducedMotion()`, with no
  setting of its own. When it is on:
  - the camera cuts instead of easing (settle-in, before a Reroll Throw or Cocked Rethrow, back from orbit);
  - the reroll glow is a steady highlight rather than a pulse;
  - the reroll lift keeps its arc but plays at ×2;
  - UI transitions are instant.

  The Dice still tumble: they are the content, and there is still no skip.
- **Screen readers:** JavaFX's accessibility bridge (UI Automation and Narrator on Windows).
  - Every focusable control has an accessible name, the icon-only ones included ("Delete Saved Roll
    Fireball").
  - Each Saved Roll and Roll History row reads as one sentence.
  - The result strip is focusable. After a reveal, its accessible text is a spoken summary: "Total 17. 4d6
    reroll 1s keep highest 3: 6, 5, 6, 1 not kept. Plus 2."
  - No announcement is pushed at the reveal, because JavaFX has no public live-region API.
  - An ADR-0011 test checks that every focusable node has non-empty accessible text.

**Glyphs**
- Every icon (↻, ✎, 🗑, ⚠, the bell, ↑/↓ and the not-kept slash) is drawn in code as a JavaFX `SVGPath` from
  path data in the source. None depends on a system font, so emoji rendering never matters.
- UI text uses JavaFX's default system font (Segoe UI on Windows).

### 3.3 Dice and Dice Sets

- **Geometry.** These follow Chessex's modern mould, with real numeral layouts. The d10 is a pentagonal
  trapezohedron with its vertices on a sphere, and the percentile d10 reuses the d10 solid. Sizes are in
  [Appendix A](#appendix-a-die-sizes). Edges are rounded at 0.75 mm. Physics and rendering share one solid
  definition per Die Kind.
- **Numerals** are engraved 0.4 mm deep with a thin film of ink, drawn by parallax occlusion from a procedural
  stroke-font atlas. Every Numeral on a Die Kind shares one height, from d6 11.6 mm down to d20 4.2 mm. 6 and
  9 are underlined. The d4 is read from the top.
  ([ADR-0008](adr/0008-numerals-are-parallax-occlusion-engravings-from-a-procedural-atlas.md))
- **Dice Sets** are versioned `.diceset` data files drawn by one uber-shader. The 14 built-ins cover solid,
  swirl, speckle, pearl, metal and translucent looks ([Appendix B](#appendix-b-built-in-dice-sets)).
  Translucent Dice refract the Tray and show their far Numerals through the body. The Dice Set's material
  doesn't change the sound.
  ([ADR-0009](adr/0009-dice-sets-are-data-files-and-translucency-is-analytic-refraction.md))
- **Player Dice Sets** are dropped into `data\dicesets\` and read once at start-up. They are listed after the
  built-ins in file-name order. A bad or clashing file is skipped with a Notice. The app never writes them.

### 3.4 Trays and Light Rigs

- **Trays** are versioned `.tray` data files: an outline (rectangle, hexagon or oval), walls (square or
  padded; wood or leather; at least 30 mm high) and a floor (felt, leather or wood). Every Tray shares one
  300 × 200 mm Footprint. The escape volume is the visible walls.
  ([ADR-0010](adr/0010-trays-are-data-files-over-one-footprint-under-a-chosen-light-rig.md))
- **Built-ins:** green felt in walnut (the default), brown leather in a padded hexagon, and bare walnut
  ([Appendix C](#appendix-c-built-in-trays-and-light-rigs)). Player Trays go in `data\trays\`, under the same
  rules as Player Dice Sets.
- **Light Rigs:** Studio (the default), Lamp and Window. They are code, chosen independently of the Tray, and
  every rig has PCSS shadows from one key light and an analytic reflection environment.

### 3.5 Sound

- **Source:** recorded CC0 impact slices in six categories (die on felt, leather or wood floor; die on wood
  or leather wall; die on die), at least 8 slices each, shuffled with pitch and gain jitter.
- **What makes sound:** contacts only. The release, the reroll pickup and the UI are silent.
- **Sync:** sound follows the live playback clock, so Fast-forward compresses its timing without changing its
  pitch. It is panned by where each contact appears on screen.
- **No audio device:** the app runs silently, with one Notice. If a headset is unplugged mid-run, the default
  device is retried at the next Throw.
- ([ADR-0012](adr/0012-sound-is-our-own-streaming-mixer-over-javax-sound.md))

### 3.6 Persistence

Recorded in [ADR-0007](adr/0007-persisted-state-is-versioned-text-files-of-facts.md). The details below were
settled in
[Does v1 keep a roll history or saved rolls, and what persists?](https://github.com/evildarkarchon/Andys-Dice/issues/14#issuecomment-6391389839).

**Where:** the `data\` folder beside the launcher, or `%LOCALAPPDATA%\AndysDice` if that isn't writable. It is
chosen once at start-up, and an app-owned system property overrides it for dev runs and tests.

**Roll History**
- **Size:** the last 100 Rolls, and it survives a restart.
- **Entries:** each stores a UTC instant, the expression as written, the Saved Roll's name at the time (or
  empty), and each Die's Face Value Chain in Dice Group order. The Roll Result is recomputed from these.
- **When:** a Roll joins the Roll History when its Roll Result is revealed. A Failed Roll, or one cut short by
  closing the app, never joins it.
- **Actions:** Roll again (which appends a new Roll), Copy into the box, Save as a Saved Roll, and Clear all.
- **No re-watching.** Only the latest Roll's Recordings stay in memory, and the Tray starts empty after a
  restart.

**Saved Rolls**
- A flat list in the Player's own order, with new ones at the end. The cap is 100.
- A name is trimmed, 1–40 characters, unique ignoring case, and has no control characters.
- The expression must parse when saved. Both name and expression can be edited.

**Settings**
- The Dice Set, Tray and Light Rig (by identity).
- The window's normal bounds and a maximised flag. If the saved bounds miss every current screen, the default
  size is centred on the primary screen.
- The Quality Preset, mute and volume.
- The Roll Expression box is not persisted.

**Files**
- `settings.properties`, `saved-rolls.txt` (`name⇥expression` lines) and `history.txt` (one tab-separated
  line per Roll).
- All three are UTF-8 with a version header line.
- Writes are atomic, on one writer thread.
- An unreadable or newer-version file is renamed `<name>.bad-<timestamp>`, never overwritten, and a Notice
  says so.

**One instance:** a lock file in the data folder. A second launch says "Andy's Dice is already running" and
exits.

### 3.7 Distribution

- **The zip:** `AndysDice-<version>-windows-x64.zip`, an unsigned `jpackage` app-image folder holding
  `AndysDice.exe`, `app\` and `runtime\`. Unzip it anywhere; delete the folder to uninstall.
- **Known limits:** SmartScreen's "Run anyway" prompt on first launch. Where Smart App Control is enforcing,
  the exe is blocked outright.
- **Releases:** a GitHub Actions job builds and attaches the zip when a `v*` tag matching the POM version is
  pushed. MIT licence.
- ([ADR-0004](adr/0004-portable-unsigned-windows-app-image.md))

## 4. Caps, targets and tunables

**Contracts** are tested or gate a release. **Tunables** may be changed by the build without a new decision.

| What | Value | Kind | Source |
|---|---|---|---|
| Dice per Throw | 20 physical Dice | contract (code constant) | ADR-0014 |
| Roll Expression length | 100 characters | contract | ADR-0014 |
| Modifier | 0–999 | contract | grammar |
| Reroll rounds per Roll Expression | 100 (backstop) | contract | ADR-0006 |
| Silent Attempts per Throw | 24 | contract | ADR-0013 |
| Cocked Rethrows per Throw | 3 | contract | ADR-0013 |
| Cocked tolerance | 4° from vertical | contract | ADR-0013 |
| Settle Nudges per Die per Attempt | 3 | tunable | ADR-0013 |
| Die-on-die friction | 0.2 | tunable | ADR-0013 |
| Physics step / simulated-time cap / recording rate | 240 Hz / 4 s / 120 Hz | tunable | ADR-0014 |
| Soak: Throws per built-in Tray, p95 time to rest, mean Attempts | 1,000 / ≤ 1.5 s / ≤ 2.5 | tunable gates | ADR-0013, ADR-0014 |
| GPU budget, most expensive scene, 1080p, Medium, on an RTX 4070 | ≤ 0.5 ms (`--bench`) | contract (blocks release) | ADR-0014 |
| Render-target cap below High | ≈ 2.07 Mpx | contract | ADR-0014 |
| Frame pacing | JavaFX 60 Hz pulse | contract | ADR-0014 |
| Slow-GPU Notice threshold | > 14 ms per frame across a Throw | tunable | ADR-0014 |
| Enter to first motion, 20 Dice with `rr` | ≤ 150 ms (`--bench`) | contract (blocks release) | ADR-0014 |
| Launch to interactive window | ≤ 3 s (logged, release checklist) | contract | ADR-0014 |
| Mixer block / sink queue | 480 frames (10 ms) / 40 ms | tunable | ADR-0014 |
| A/V sync window | ≤ 15 ms early, ≤ 45 ms late | contract | ADR-0012 |
| Roll History / Saved Rolls | 100 / 100 | contract | ADR-0007 |
| Saved Roll name | 1–40 characters | contract | #14 |
| Queue beat, Fast-forward speed | ≈ 0.9 s, ×4 | tunable | #19 |
| Fast-forward hold before it starts | ≈ 0.2 s of Shift alone | tunable | #24 |
| Window default / minimum (logical px) | 1280 × 800 (≤ 90% of the screen) / 960 × 600 | tunable | #24 |
| Reduced-motion reroll lift | ×2 | tunable | #24 |
| UI contrast | WCAG 2.2 AA (4.5:1 text, 3:1 graphics) | contract (tested) | #24 |
| Reroll pickup | ≈ 0.6 s glow + 0.7 s lift | tunable | ADR-0010 |
| Footprint, wall height | 300 × 200 mm, ≥ 30 mm | contract | ADR-0010 |
| Numeral depth, edge radius | 0.4 mm, 0.75 mm | contract | ADR-0008 |
| Default volume | unmuted, 70% | tunable | ADR-0012 |

## 5. Architecture

A Maven reactor of six modules, each one JPMS module, with dependencies flowing downward only. Module names
are `io.github.evildarkarchon.andysdice.<module>` and artifact IDs `andysdice-<module>`.

| Module | Holds | Depends on | Natives / GL |
|---|---|---|---|
| `core` | the Roll Expression parser and evaluator; Roll Result and Breakdown; Face Value selection; Die Kind solids, face tables and Numbering Rotation; the engine-neutral Recording types and simulator seam; the Throw planner (Attempts, retries, Cocked Rethrows, reroll rounds); the playback clock; `.diceset` and `.tray` parsing | — | none |
| `physics` | the Jolt simulator behind the seam: the four endings, Release, Settle Nudges, contact events; our native loader | `core` | jolt-jni |
| `render` | mesh and numeral-atlas generation (pure packages, tested without GL); the GL renderer and shaders; native context creation; the FBO/PBO bridge behind the transport seam; the gallery and `--bench` | `core` | LWJGL |
| `sound` | the streaming mixer, the sample bank and `SOURCES.md`, the sink seam | `core` | none (`javax.sound`) |
| `app` | the JavaFX shell and Roll UI; persistence; the lock file; `--smoke-test`; wiring | all above | JavaFX |
| `assembly` | the `jpackage` app-image and zip, `app\legal\`, the Failsafe smoke tests | `app` | — |

The fake simulator lives in `core`'s test fixtures, and the stand-in tray (ADR-0011) in `app`'s.

**The decisions:**

- [ADR-0001](adr/0001-rng-chooses-face-values.md): a generator chooses Face Values; physics only animates the
  Throw.
- [ADR-0002](adr/0002-embedded-gl-renderer-public-api-bridge.md): our own LWJGL GL 3.3 core renderer, shown
  through a public-API `PixelBuffer` bridge; one context and one render thread; fail fast without GL 3.3.
- [ADR-0003](adr/0003-jolt-pre-simulates-throws-behind-engine-neutral-recording.md): Jolt pre-simulates
  behind an engine-neutral Recording, in centimetres, with a fresh system per Attempt.
- [ADR-0004](adr/0004-portable-unsigned-windows-app-image.md): a portable, unsigned Windows x64 app-image from
  one `jpackage` call; natives in `app\natives`; `--illegal-native-access=deny` everywhere.
- [ADR-0005](adr/0005-verified-by-invariants-gl-on-mesa-in-ci.md): tests check invariants, never trajectories
  or pixels; GL runs on Mesa in CI; `--smoke-test`, a gallery and a release checklist.
- [ADR-0006](adr/0006-rerolls-are-reroll-throws-of-the-same-dice.md): a Reroll is a Reroll Throw of the same
  Dice with the rest frozen; the whole chain is pre-simulated.
- [ADR-0007](adr/0007-persisted-state-is-versioned-text-files-of-facts.md): three versioned text files of
  facts; Recordings are never kept.
- [ADR-0008](adr/0008-numerals-are-parallax-occlusion-engravings-from-a-procedural-atlas.md): Numerals are
  parallax-occlusion engravings from a procedural atlas.
- [ADR-0009](adr/0009-dice-sets-are-data-files-and-translucency-is-analytic-refraction.md): Dice Sets are data
  files for one shader; translucency is analytic refraction.
- [ADR-0010](adr/0010-trays-are-data-files-over-one-footprint-under-a-chosen-light-rig.md): Trays are data
  files over one Footprint, under a separately chosen Light Rig.
- [ADR-0011](adr/0011-ui-verified-on-javafx-headless-platform-without-testfx.md): UI tests run on JavaFX's
  headless platform, without TestFX.
- [ADR-0012](adr/0012-sound-is-our-own-streaming-mixer-over-javax-sound.md): our own streaming mixer over
  `javax.sound.sampled`, driven by the playback clock.
- [ADR-0013](adr/0013-throws-reach-a-flat-rest-by-release-friction-and-nudges.md): flat rest through the
  Release, friction and Settle Nudges, with a Cocked Rethrow as the fallback.
- [ADR-0014](adr/0014-performance-is-budgeted-as-scaled-gpu-time-and-checked-by-a-bench.md): performance is
  budgeted as scaled GPU time and checked by `--bench`.

**Threads:**
- the JavaFX Application Thread (UI, frame presentation);
- one render thread (every GL call);
- one physics worker (Jolt start-up and every pre-simulation);
- one audio thread (the mixer and sink);
- one persistence writer thread.

**Generators:** three independent `RandomGenerator`s: Face Values (ADR-0001), Releases (ADR-0003) and sound
variation (ADR-0012). Tests inject fixed seeds.

## 6. Left to the build

These are reversible and unsurprising, so they have no ADR. The builder may change the stated default without
reopening anything.

- **Logging:** `System.Logger` over `java.util.logging`, writing to `data\logs\andysdice.log` with size-based
  rotation and no third-party framework. Jolt's trace output and the chosen data folder go there.
- **Seeding:** each of the three generators is seeded independently from `SecureRandom` at start-up.
- **Math library:** JOML, as the prototypes use it and ADR-0004's notice list names it.
- **Licence files:** an MIT `LICENSE` at the repo root, added in S0. The CC0 sounds' `SOURCES.md` ships in
  `app\legal\` beside the other notices.
- **Formats and names:**
  - the syntax of the version header line;
  - the `settings.properties` and `.tray` key names;
  - the gallery flag;
  - the natives-directory and data-folder override property names.
- **Tuning:**
  - each Quality Preset's lever values (tuned against `--bench`);
  - the Release numbers;
  - the sound-shaping numbers;
  - the camera limits;
  - the die-on-die friction combine method (ADR-0013).

## 7. Build plan

The split as decided at hand-off. **Once the build issues are filed, they supersede this section.** Each slice
is vertical and verifiable on its own under ADR-0005.

| # | Slice | Depends on | Verified by |
|---|---|---|---|
| S0 | **Build skeleton:** parent POM and the six modules with `module-info`s; `javac -Xlint:all -Werror`; the shared native-access flag set under `deny`; JUnit 6 + AssertJ; CI (Windows with pinned Mesa, plus Ubuntu without GL); `LICENSE` | — | CI green with one test per module |
| S1 | **Roll Expression engine** (`core`): parser, validation, error spans, caps; the Roll Result and Breakdown from given Face Value Chains, keeps and rerolls included | S0 | [the grammar reference's](roll-expression-grammar.md) example table |
| S2 | **Solids and Face Values** (`core`): the 7 solids and face tables, symmetry groups, the Numbering Rotation, Face Value selection, reading the up face from a pose, the cocked test | S0 | exhaustive geometry tests, chi-square fairness |
| S3 | **Walking skeleton:** Jolt start-up; one hand-made cube Thrown to rest in a flat box; its Recording; a native WGL context; the FBO/PBO bridge; flat-shaded playback in a JavaFX window; Enter rolls `1d6` and shows the result; `--smoke-test`; the `jpackage` zip; Failsafe smoke tests and the no-Mesa fail-fast job | S0 | smoke tests in CI |
| S4 | **The full Throw pipeline:** all Die Kinds in `physics`; the four endings, Release, Settle Nudges, contact events; the Throw planner in `core` (silent retries, Cocked Rethrows, Reroll Throws with Frozen Dice); the `soak` profile and the nightly job | S1, S2, S3 | fake simulator, real-Jolt scenarios, soak |
| S5 | **Opaque Dice look** (`render`): rounded meshes; the numeral atlas and parallax engraving; the uber-shader for solid, swirl, speckle, pearl and metal; `.diceset` loading with the opaque built-ins and Player Dice Sets; the gallery | S2, S3 | mesh and atlas pure tests, `gl` shader tests, gallery |
| S6 | **Translucent Dice Sets:** analytic refraction, the volume march, the opaque-pass copy, the remaining built-ins | S5 | `gl` tests, gallery |
| S7 | **Trays, Light Rigs and camera:** `.tray` files and the 3 built-ins; the escape volume; PCSS; analytic reflections; the 3 rigs; the settle-in camera, orbit and zoom; the reroll glow and lift | S4, S5 | pure and `gl` tests, soak per Tray |
| S8 | **Roll UI:** the Layout C notebook; the die strip; the result strip and Breakdown chips; the Queued Roll; Fast-forward with its Shift-alone hold; the error popup and the Failed Roll message; the bell; the runtime-failure panel; keyboard, focus order and shortcuts; contrast, reduced motion and accessible text; the code-drawn icons; the headless UI test base | S1, S3 | headless UI tests, the palette contrast test |
| S9 | **Persistence:** the three files, the atomic writer thread, the lock file, the data-folder fallback; the History and Saved tabs wired up | S8 | pure round-trip and UI tests |
| S10 | **Sound** (`sound`): the mixer, the CC0 sample bank, the sink seam; rendering against the playback clock, Fast-forward included; mute and volume | S4 | pure mixer tests on synthetic events |
| S11 | **Performance and release:** the Low, Medium and High presets; `--bench` with timer queries; the slow-GPU Notice; the release checklist in `docs/`; the tag-triggered release workflow; `app\legal\` | all | `--bench`, a release dry run |

S1, S2 and S3 run in parallel once S0 lands. S3 uses a hand-made cube so that it doesn't wait for S2.

## Appendix A: Die sizes

The inradius (half the face-to-face distance) of each Die Kind's rounded solid. These are the research's
suggested model sizes, used by the
[numerals prototype](https://github.com/evildarkarchon/Andys-Dice/blob/7691171b3c1583d03b7c513f32a27d89962a39e6/prototypes/numerals/src/main/java/andysdice/prototype/numerals/DieKind.java),
and ADR-0008's numeral heights are derived from them. Changing a size changes those heights.

| Die Kind | d4 | d6 | d8 | d10 | percentile d10 | d12 | d20 |
|---|---|---|---|---|---|---|---|
| Inradius (mm) | 4.5 | 8 | 7.75 | 8 | 8 | 9.5 | 10 |
| Numeral height (mm) | 4.3 | 11.6 | 7.9 | 7.6 | 5.4 | 7.2 | 4.2 |

Face tables, the d20 layout and the d10's shape come from the
[real-dice-geometry research](https://github.com/evildarkarchon/Andys-Dice/blob/f902743a99bf4c69da3fa5c8383b9f10e4b89ec0/docs/research/real-dice-geometry.md)
§3, with the numeral orientation rules of ADR-0008.

## Appendix B: Built-in Dice Sets

In this order, which is also the swatch order. The first, Onyx, is the fallback when the chosen Dice Set is
missing. The files ship verbatim from the
[materials prototype](https://github.com/evildarkarchon/Andys-Dice/tree/2f808fe2f23f87e3f445dc0b3133bc0f4ced38bd/prototypes/materials/dicesets).

| # | File | Name | Look |
|---|---|---|---|
| 1 | `01-onyx` | Onyx / white | solid |
| 2 | `02-ivory` | Ivory / black | solid |
| 3 | `03-crimson` | Crimson / gold | solid |
| 4 | `04-vortex-blue` | Vortex blue / white | swirl |
| 5 | `05-jade-marble` | Jade marble / gold | swirl |
| 6 | `06-speckled-stone` | Speckled stone / black | speckle |
| 7 | `07-pearl-violet` | Pearl violet / silver | pearl |
| 8 | `08-steel` | Steel / black | metal |
| 9 | `09-gold` | Polished gold / black | metal |
| 10 | `10-gunmetal` | Gunmetal / gold | metal |
| 11 | `11-smoke` | Smoke / white | translucent |
| 12 | `12-ruby` | Ruby / gold | translucent |
| 13 | `13-emerald` | Emerald / white | translucent |
| 14 | `14-nebula` | Nebula / white | translucent |

## Appendix C: Built-in Trays and Light Rigs

The prototype's three looks, refitted to the 300 × 200 mm Footprint and 30 mm walls (ADR-0010). Colours,
textures and physics materials come from the
[tray prototype](https://github.com/evildarkarchon/Andys-Dice/tree/5d07bfacbc02ca317c58443269bf780dfe1e829e/prototypes/tray)
(`Params.java`, `TrayGeom.java`, `TrayTextures.java`).

| Tray | Outline | Walls | Wall bounce | Floor |
|---|---|---|---|---|
| Green felt **(default)** | rectangle | walnut, square top | 0.5 | green felt |
| Leather hexagon | hexagon | padded leather, rounded top | 0.2 | brown leather |
| Walnut board | rectangle | walnut, square top | 0.5 | bare walnut |

| Light Rig | Key light (the only shadow caster) | Room and reflections |
|---|---|---|
| Studio **(default)** | directional, from two softboxes | neutral grey room, softbox reflections |
| Lamp | one warm spot overhead | dark room, the lamp's bright opening |
| Window | low, cool, directional daylight from the side | blue sky fill, a window |

## Appendix D: Reference branches

The research and prototype branches hold data the build copies, and the evidence behind the ADRs. They are
never merged, and they are **kept pushed until v1 ships**, so the commit permalinks above stay valid. They may
be deleted only after that.

| Branch | Pinned commit | Holds |
|---|---|---|
| `research/real-dice-geometry` | `f902743` | face tables, d20 layout, d10 shape, sizes |
| `research/dice-sound` | `d8a75ce` | sound sources and the impulse method |
| `prototype/numerals` | `7691171` | stroke font, atlas, Die sizes |
| `prototype/materials` | `2f808fe` | the 14 `.diceset` files, the uber-shader |
| `prototype/tray` | `5d07bfa` | Trays, Light Rigs, PCSS, camera, pickup |
| `prototype/flat-rest` | `c7b900a` | Release, friction and Settle Nudge numbers |
| `prototype/roll-ui` | `5250566` | Layout C, the headless capture tour |

The other branches are linked from their tickets on the map: `research/javafx-27-3d`,
`research/java-physics-engines`, `research/embedded-renderers` and `prototype/renderer-bakeoff`.
