# Release checklist

Work this list before pushing every `v*` tag. The tests and CI check what a machine can check. This list covers
what only an eye or an ear can judge, and the few contracts that need real hardware (spec §4, ADR-0005,
ADR-0012, ADR-0014).

Copy the list into the release's tracking issue and tick it there. Any box that can't be ticked blocks the tag:
fix the problem, or record why it's accepted, before you tag.

## 1. Prepare the release build

- [ ] **Set the release version.** Change `1.0.0-SNAPSHOT` (or the current snapshot) to the release's SemVer
  version, such as `1.0.0`, in the parent `pom.xml` and in the `<parent>` block of each module's POM. Commit it on
  `main`. The tag must be `v` plus exactly this version, or the release workflow fails at its first job. Check it
  locally first:

  ```
  mvnw.cmd validate -N -Dandysdice.release.tag=v1.0.0
  ```

- [ ] **Build and test everything, with the soak**, from a clean checkout of that commit, on a Windows machine with
  a real GPU:

  ```
  mvnw.cmd clean verify -Psoak
  ```

  This builds `andysdice-assembly\target\AndysDice-<version>-windows-x64.zip` and runs the tests of the built image
  (the smoke test, the GL fail-fast and `app\legal\`). Use that zip for everything below.

- [ ] **Unzip it as a user would:** copy the zip somewhere that isn't the build tree (the Downloads folder, say),
  then extract it with File Explorer's "Extract All" into a new folder whose path has a space in it.

## 2. The first launch from the unzipped folder

- [ ] Run `AndysDice\AndysDice.exe` by double-clicking it. Expect SmartScreen's "Windows protected your PC": More
  info, then Run anyway. This is the known limit `docs/release-notes.md` describes. Nothing else may prompt.
- [ ] The window opens titled "Andy's Dice", with no console window, and focus is in the Roll Expression box.
- [ ] `AndysDice\data\` was created beside the exe, holding `logs\andysdice.log`. The log names that data folder
  and shows no `WARNING` from the JVM (native access, Unsafe) and no exception.
- [ ] Nothing was written outside the folder: no `%USERPROFILE%\.openjfx\cache` and no extracted natives in
  `%TEMP%` (`lwjgl*`, `joltjni*`) with the time of this launch.
- [ ] A second `AndysDice.exe` while the first runs says "Andy's Dice is already running" and exits.
- [ ] `AndysDice\app\legal\` holds `LICENSE`, `THIRD-PARTY-NOTICES.md`, every notice the index names, and
  `SOURCES.md`.

## 3. Launch within 3 s

- [ ] Quit and launch again, three times. Each time the log's `Window shown <n> ms after launch` line says
  n ≤ 3000. The time runs from the process's start, so SmartScreen's prompt isn't in it.
- [ ] After a reboot (a cold disk cache), the first launch is also within 3 s.

## 4. Looks

Throw a range of expressions: `1d4`, `1d6`, `1d8`, `1d10`, `1d%`, `1d12`, `1d20`, then a mixed handful such as
`3d20+2d12+d10+d%+3d8+6d6+3d4` (the 20-Dice cap), on each built-in Tray and Light Rig.

- [ ] **Numerals are legible and match the Roll Result**, on every Die Kind, the percentile pair included, in each
  of the 14 built-in Dice Sets (Look tab). The underline tells a lone 6 or 9 from its upside-down twin.
- [ ] **No interpenetration:** no Die sinks into the floor, a wall or another Die, at any point of a Throw.
- [ ] **No visibly Cocked Die:** every Die comes to rest flat on a face, within the 4° Cocked tolerance; none
  is seen leaning on a wall or another Die. A Cocked Rethrow, when one happens, looks like a fresh throw rather
  than a glitch.
- [ ] **Shadows:** each Die casts a soft shadow from the Light Rig's key light that sits under it on the floor,
  with no acne, peter-panning or shimmer as the camera moves.
- [ ] **Reflections:** metal (Steel, Polished gold, Gunmetal), pearl and translucent Dice (Smoke, Ruby, Emerald,
  Nebula) reflect and refract the Light Rig's room plausibly, under each of Studio, Lamp and Window.
- [ ] **The Release reads as a throw:** the gentler Release still looks like Dice thrown into the Tray, not
  dropped or slid in.
- [ ] **The Settle Nudge looks natural:** a Die that teeters settles onto a face without a visible jump, twitch or
  push.

## 5. Sound

Use headphones for this section, then speakers for a final check.

- [ ] **Every Tray:** throw `8d6` and the cap mix on Green felt, Leather hexagon and Walnut board. The floors sound
  like felt, leather and bare wood, the walls like wood or padded leather, and each Die-on-Die click is
  distinct. Nothing machine-gun-like repeats, nothing clips, and there are no crackles or dropouts.
- [ ] **Against a Frozen Die:** throw `6d6rr<3` until a Reroll Throw sends a rerolled Die into a Frozen Die. Each
  hit clicks as Die on Die, and the Frozen Dice stay silent otherwise. The reroll pickup itself is silent.
- [ ] **Shift held mid-Throw:** hold Shift alone partway through a Throw, then release it. At ×4 the clatter's
  timing compresses but its pitch doesn't change, and sound stays in step with the Dice through both changes of
  speed.
- [ ] **Panning:** contacts on the left of the screen are heard on the left, and on the right on the right.
- [ ] **Mute and volume** act at once and are kept across a restart.

## 6. The A/V offset: a click-and-flash test

The A/V offset (ADR-0012) is the `Duration` that `app.Clatter` passes to `SoundOutput.start`; it is
`Duration.ZERO` until this test says otherwise. The aim is the spec's window: audio no more than **15 ms early**
and no more than **45 ms late** against the picture, and slightly late when in doubt.

- [ ] Film the screen and a pair of wired headphones or a speaker held next to it, with a phone's slow-motion
  video at 240 fps (about 4 ms a frame) or faster, while throwing `1d20` onto Walnut board a dozen times. Wired:
  a Bluetooth device adds its own latency, which the Player's own setup decides.
- [ ] In a video editor, for each first floor contact, find the first frame where the Die visibly touches the
  floor (the flash) and the start of its click in the recorded sound. Audio after the picture is late, before it
  early.
- [ ] Every measured contact is within 15 ms early and 45 ms late, and their median is between 0 and 30 ms late.
  If not, change the offset by the median's error, rebuild, and measure again. Record the measured median and
  spread, and the offset chosen, in the tracking issue.

## 7. Unplugging the audio device mid-run

- [ ] With headphones (USB, or in the jack) as the default device, start a Throw, then unplug them mid-Throw. The
  app keeps running and rendering, with no hang, no exception in the log and no error popup. Sound failures stay
  silent (spec §3.5).
- [ ] The next Throw plays on the new default device (the speakers), and so does every Throw after it.
- [ ] Plug the headphones back in and make them the default again: the next Throw is heard on them.
- [ ] Start the app with no audio device at all (disable every output device in Sound settings): it runs
  silently, with one Notice behind the bell.

## 8. Resizing and Quality Presets

- [ ] Drag a window corner continuously during a Throw, and between Throws: the Tray rescales with no hang, no
  black or stretched frames that persist, and the Throw carries on in step.
- [ ] Maximise, restore and minimise, during a Throw and between them. Drag the window from one monitor to
  another with a different scale factor, if there is one. Shrink it to its 960 × 600 minimum.
- [ ] Switch the Quality Preset (Low, Medium, High) during a Throw and between Throws, and throw the cap mix at
  each. Each one looks right, Low visibly cheaper and High visibly richer, with no hang or flicker.
- [ ] The window's size and position, and the preset, are kept across a restart.

## 9. `--bench`

- [ ] On the reference machine (an RTX 4070 at 1080p), run `AndysDice.exe --bench` from the unzipped folder. It
  must report the most expensive scene at **≤ 0.5 ms** of GPU time on Medium, and Enter to first motion for 20
  Dice with `rr` at **≤ 150 ms**. Both are contracts that block the release (ADR-0014). Record the numbers in the
  tracking issue.
- [ ] On an Iris Xe-class integrated GPU, if one is to hand: a Throw at the default preset (Medium) runs at 60 fps
  without the slow-GPU Notice.

## 10. Tag and publish

- [ ] Push the release commit, and wait for its CI run to pass.
- [ ] Tag that commit and push the tag:

  ```
  git tag -a v1.0.0 -m "Andy's Dice 1.0.0"
  git push origin v1.0.0
  ```

- [ ] The Release workflow passes: the tag check, the whole CI (with the tests of the built image), the soak, and
  the publish job. Its Release has `AndysDice-<version>-windows-x64.zip` attached and `docs/release-notes.md` as
  its notes.
- [ ] Download the zip from the Release page and repeat §2 with it. This is the first launch from the unzipped
  folder that users will have.
- [ ] Set the next snapshot version (such as `1.0.1-SNAPSHOT`) on `main`.

If the workflow fails after the tag was pushed, fix the cause on `main`, then delete the tag (on GitHub and
locally) and tag the fixed commit. Never move a tag that already has a published Release.

A dry run of the workflow, which publishes nothing, is a `workflow_dispatch` of "Release" from the Actions tab or
with `gh workflow run release.yml --ref <branch or tag>`. It leaves the zip as the run's `release-zip` artifact.
