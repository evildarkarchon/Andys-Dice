# The Roll UI is verified on JavaFX's own headless platform, without TestFX

UI tests drive the real JavaFX controls in-process on JavaFX's built-in headless Glass platform
(`-Dglass.platform=Headless`, Prism's `sw` pipeline). They use the public `javafx.scene.robot.Robot` for input,
or call the controls directly, under the same JUnit stack as everything else (ADR-0005). No window, display or
GPU is involved, and there is no TestFX and no Monocle. The usual route, TestFX with Monocle for headless runs,
has gone stale: TestFX 4.0.18 and the testfx `openjfx-monocle` 21.0.2 both date from February 2024, and that
Monocle does not track JavaFX 27. OpenJFX integrated its own headless platform in July 2025 (JDK-8324941) to
replace Monocle for its own tests. The Roll UI prototype's whole capture tour ran on it unchanged, error popup
and selected span included
([prototype](https://github.com/evildarkarchon/Andys-Dice/tree/prototype/roll-ui/prototypes/roll-ui)).

## Considered Options

- **TestFX with Monocle.** Rejected. Both are dormant, Monocle would have to be built or pinned against
  JavaFX 27 ourselves, and TestFX's main value (robot input plus lookups) is now covered by the public `Robot`
  and plain node lookups.
- **Real windows on the CI runner.** Rejected as the default. `windows-latest` has a desktop, but focus and
  timing in a real window make UI tests flaky. It stays the fallback (see below).
- **No automated UI tests; the release checklist only.** Rejected. The Roll UI has many states that are cheap to
  get wrong and tedious to check by hand: a queued Roll, fast-forward, a reroll chain's breakdown, an invalid
  Saved Roll, the error span.

## Consequences

- **Upstream risk.** The headless platform lives in `com.sun.glass.ui.headless`, is chosen by a system property
  rather than an API, and was integrated under a "POC" title. One test base class owns those flags, so if a
  JavaFX update breaks or removes the platform, the same tests move to real windows on the Windows runner by
  changing that class.
- **Error span.** The geometry is pure Java and tested without a toolkit: span to selection range, and span to
  the popup's anchor. A UI test then checks that a bad Roll Expression leaves the box's selection on exactly the
  #7 span, shows the one message in the popup, and starts no Roll.
- **No GL in UI tests.** UI tests mount a stand-in tray behind ADR-0002's transport seam, which reports Throw
  phases and rest on an injectable playback clock. Tests advance that clock and never sleep. The 3D pixels stay
  with ADR-0005's `gl` tests, gallery and checklist. Because they need no GL, UI tests run in the Windows push job
  and are meant for the no-GL `ubuntu-latest` job too. Only Windows was tried in the prototype.
- **Still by eye.** Layout, legibility and feel stay on the release checklist. UI tests assert behaviour and
  state (what is enabled, what is queued, what the breakdown and history contain), never pixels.
