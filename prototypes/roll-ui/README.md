# PROTOTYPE: the v1 Roll UI (throwaway)

> Lives only on the `prototype/roll-ui` branch. Never merge it. It answers one question for
> [How should the v1 Roll UI look and behave?](https://github.com/evildarkarchon/Andys-Dice/issues/19)
> and is kept as a primary source.

**Verdict (2026-10-24).** The layout is variant C, the notebook sidebar. The box stays editable while dice
move, and Enter queues one next Roll. Shift fast-forwards ×4, and there is no skip; the mouse-hold
fast-forward is dropped because a drag on the tray now orbits. The camera settles in, and the player can drag to
orbit and wheel to zoom. Errors keep C's display: on Enter only, the span is selected and a popup shows the
message. The settings, Roll History and Saved Rolls take C's tabs. UI tests run on JavaFX's own headless
platform, without TestFX, recorded in
[ADR-0011](https://github.com/evildarkarchon/Andys-Dice/blob/main/docs/adr/0011-ui-verified-on-javafx-headless-platform-without-testfx.md).
The full answer is the [resolution](https://github.com/evildarkarchon/Andys-Dice/issues/19#issuecomment-6393081238).

**Question.** How should the v1 Roll UI look and behave around the 3D tray? That covers entry (a text box, die
buttons, or both), how a Roll is triggered and what input does while a Throw plays, where and when the Roll
Result and its breakdown appear (unkept Dice, reroll chains, terms as written), how a parse error and its span
are shown, skipping and fast-forwarding, where the Dice Set, Tray, Light Rig, quality, sound, Roll History and
Saved Rolls go, the one-time notices and confirmations, the player's camera, and how the UI is verified.

**What is real and what isn't.** The parser is real: the #7 grammar plus #13's reroll forms, with the messages
and spans a player would see (`ParserCheck` prints them all). Face Values are chosen first and whole reroll
chains are worked out up front (ADR-0001, ADR-0006). The tray is a **2D placeholder** for the GL renderer: the
motion is fake, but the timings are the measured ones (about 0.85 s from release to rest, ADR-0010's glow then
lift of about 1.3 s before each Reroll Throw, and the settle-in camera). State lives in memory, seeded with fake
history, Saved Rolls (one of them invalid), two player Dice Sets and two skipped Dice Set files.

## The switcher

The magenta bar flips between three layouts, radically different on purpose. Left/Right arrows also flip, unless
a text box has focus. Behaviour is a separate dimension: the bar's **Skip**, **Input** and **Camera** boxes put
any behaviour on any layout ("B's layout with A's skip" is one click). Each variant starts with its own defaults,
and **variant defaults** restores them. **+ notice** raises a one-time notice, as when a file is set aside.

| | A: Command bar over the tray | B: Workbench | C: Notebook sidebar |
|---|---|---|---|
| Layout | Tray fills the window; everything floats over it | Menu bar + three columns | Tabbed sidebar + toolbar + strip under the tray |
| Entry | One keyboard-first box; Up/Down recall past expressions | Die buttons that write the box (click +1, right-click −1), Modifier spinner, Normal/Adv/Dis; the box stays editable | Box plus a compact die strip that appends `+1dX` |
| Validation | Live, 300 ms after typing stops | On Roll only | On Enter only |
| Parse error | Red squiggle under the span + message below the box | Compiler style: the expression echoed, `^^` under the span, message | Span selected in the box + a popup anchored under it |
| Roll Result | Card over the top of the tray; one-line breakdown | Receipt in the right column: a row per term, Dice as chips, subtotals, total | Strip under the tray: big total, die chips with rerolled-away values stacked behind |
| Rerolled vs unkept | `1↻ 5` (amber old values) vs struck grey | `1↻` before the chip vs dashed `~2~` chip | Stacked ghost chips + ↻ badge vs faded, slashed chip |
| Saved Rolls | Drawer (☰): ▶ ✎ ↑ ↓ ✕ per row | Left column: one button each, click rolls; right-click edits, moves, deletes | Saved tab: click copies, double-click rolls, ✎ edits in place |
| Roll History | Same drawer; relative times ("9 min ago", full time on hover) | Right column; clock times (day shown if not today); hover shows ⟳ ⧉ ★; clicking an entry shows its breakdown | History tab grouped under day headers; click copies, double-click rolls again, right-click for more |
| Dice Set, Tray, Light Rig | ⚙ popover with combos; skipped files listed under the Dice Set box | Menus; skipped files greyed out in the Dice Set menu | Look tab: swatch grid, radios; skipped files listed under the swatches |
| Quality, sound | Same popover | Quality and Sound menus | Settings tab |
| Notices | Toasts, bottom-left, gone after 12 s | Yellow banners until closed | A bell with a count; nothing pops up |
| Confirmations | Inline ("Clear all 9 Rolls? [Clear] [Cancel]"; ✕ turns into "Delete?") | Modal dialogs | Modal dialogs; Saved Roll edits are inline |
| Default Skip | Skip all | Skip one Throw (the Roll button becomes Skip) | Fast-forward ×4 while held |
| Default Input | Interrupt: Enter skips to the result, then rolls | Locked while dice move | Queue: Enter queues the next Roll |
| Default Camera | Settle-in + drag orbit + wheel zoom | Settle-in only | Settle-in + wheel zoom |

The behaviour options:

- **Skip.** *Skip all*: Esc or a click on the tray snaps every Die to its final rest, even mid-chain, and reveals
  the Roll Result (and the Roll joins the history, ADR-0007). *Skip one Throw*: the same keys end only the current
  Throw, and the chain carries on. *Fast-forward*: hold Shift (or hold the mouse on the tray) for ×4. *None*.
- **Input while dice move.** *Interrupt*: the box stays live, and Enter skips the current Roll to its result and
  starts the new one. *Queue*: Enter queues one Roll, which starts 0.9 s after the current one is revealed.
  *Type ahead*: the box stays live, but Roll waits. *Locked*: the box and the Roll controls grey out.
- **Camera.** *Settle-in only* is ADR-0010's camera. *Zoom* adds the wheel. *Orbit* adds drag-to-orbit (yaw, plus a
  fake tilt). Any player orbit or zoom eases back to the automatic camera over 0.4 s when the next Throw starts.
  Double-click the resting tray to reset. The tray's top-left label shows what the camera and the Throw are doing.

## Run

From the repo root, with JDK 27 (Windows paths for `mvn.cmd`):

```
JAVA_HOME='C:\OpenJDK\jdk-27' mvn.cmd -q -f prototypes/roll-ui/pom.xml package exec:exec
```

Options:

- `-Drollui.variant=B`: start on a variant (default A).
- `-Drollui.capture=target/capture`: an unattended tour. For each variant it saves: at start, mid-Throw, the
  pickup glow before a Reroll Throw, the Roll Result, panels open, and a parse error (popups as extra files). Then
  it exits. The Roll is `4d6rr1kh3+1d20+5 adv`, seeded so it needs Reroll Throws.
- `-Drollui.main=andysdice.prototype.rollui.ParserCheck`: no window. It prints about 40 sample expressions with their
  messages and spans, or one roll of each.

Things to try: `4d6rr<3kh3` (long `rr` chains), `2d%kh1` (Percentile Rolls as two dice), `1d20ro1 adv`, `21d6`,
`4d6kh3rr1`, `1d20 adv dis`, `2 d6`, `d7`. Roll the invalid **Old homebrew** Saved Roll. Clear the history.
Switch Dice Set, Tray and Light Rig mid-Roll.

## Headless

The capture tour also runs on JavaFX 27's built-in headless Glass platform, with no TestFX and no Monocle (both
are dormant: TestFX 4.0.18 and testfx Monocle 21.0.2 date from Feb 2024). With the classes built:

```
java --module-path=target/fx --add-modules javafx.controls,javafx.swing --enable-native-access=javafx.graphics \
     -Dglass.platform=Headless -Dprism.order=sw -Drollui.capture=target/headless \
     -cp target/classes andysdice.prototype.rollui.Launcher
```

On 2026-10-24 that produced the same 19 captures, including variant C's selected error span.

## Screenshots

`screenshots/` holds one windowed capture tour from 2026-10-24: `<variant>-<n>-<moment>.png`.
