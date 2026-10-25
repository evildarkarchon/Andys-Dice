# Sound is our own streaming mixer over `javax.sound.sampled`, driven by the playback clock

v1's dice sound is mixed by our own pure-Java mixer. It renders small blocks (about 10 ms) of PCM from the recording's contact events (ADR-0003) and the *current* playback clock, and streams them to one `javax.sound.sampled` `SourceDataLine`. The research for [issue #18](https://github.com/evildarkarchon/Andys-Dice/issues/18) recommended premixing each Throw into one buffer and starting it on OpenAL Soft at a scheduled device-clock time. That works only if a Throw is a fixed animation. The Roll UI ([issue #19](https://github.com/evildarkarchon/Andys-Dice/issues/19)) later let the player hold Shift to play at ×4 at any moment, mid-Throw, and a premixed buffer can't follow that. Rendering block by block against the live clock follows a speed change within one buffer. Owning the mixer also makes OpenAL's advantages (256 voices, per-voice pitch, scheduled start) our code's job, so the sink only has to accept a stream. `javax.sound.sampled` is already in the image (JavaFX requires `java.desktop`), needs no native-access grant, and adds no native or notice.

## Considered Options

- **Premix per Throw (the research's recommendation).** Rejected: it can't follow Shift ×4 mid-Throw.
- **Premix, re-rendered on every speed change.** Rejected: there's an audible restart on every Shift press and release.
- **Per-event OpenAL voices**, scheduled with `alSourcePlayAtTimeSOFT` a little ahead on the playback clock, and re-timed when the speed changes. Rejected: it's the least code of our own, but it only works on OpenAL. The `javax.sound` fallback has no scheduling or pitch, so the two paths would behave differently.
- **OpenAL Soft streaming source as the sink.** It's the named fallback behind the sink seam, for use only if the release checklist finds `SourceDataLine` latency or glitches out of budget. It would add `lwjgl-openal`, a 2 MB `OpenAL.dll` in `app\natives`, and an LGPL-2 notice plus a source pointer in `app\legal` under ADR-0004's notice check.
- **JavaFX `AudioClip`.** Rejected, as in the research: a new GStreamer player per play, 16 voices, no play from memory, and an extra `javafx.media` grant.
- **Procedural or modal synthesis.** Not for v1. A die's own modes are near or above hearing, and the click needs a separate research-grade model.

## Consequences

- **Samples.** The sounds are recorded CC0 single-impact slices (Kenney *Casino Audio* and per-file-checked Freesound recordings). They're converted to mono 16-bit PCM WAV when authoring and committed as resources of the sound module, beside a `SOURCES.md` that gives each file's URL, author, licence and retrieval date. There are no Sonniss, Dice So Nice or Owlbear Rodeo assets. They're built-in only: unlike Dice Sets and Trays, players can't add sounds.
- **Categories.** There are six, each with at least 8 slices:
  - die → floor, per floor kind (felt, leather, wood);
  - die → wall, per wall material (wood, leather);
  - die → die.

  The surface follows the Tray file's floor kind and wall material (ADR-0010). A frozen Die in a Reroll Throw counts as a Die (ADR-0006). The Dice Set's material does **not** change the sound, so `.diceset` files gain no key (ADR-0009). Only contacts make sound: the reroll pickup, the release and the UI are silent.
- **Variation.**
  - Each category's slices are shuffled with no repeat of the last 3.
  - ±1 semitone of pitch jitter and ±1.5 dB of gain jitter are added.
  - Each Die Kind gets a fixed pitch offset from its size.

  All of these choices draw from a **third** injected `RandomGenerator`, never the Face Value one (ADR-0001) or the release one (ADR-0003), so sound can never shift a result.
- **Contact events.** The physics worker estimates each impulse with jolt-jni's `MiscUtil.estimateCollisionResponse`, in both `OnContactAdded` and `OnContactPersisted`. It emits an event when the normal approach speed at a contact rises past a threshold, so a Die tumbling on a persisted contact still clatters. `minVelocity` is passed in cm/s for the centimetre world. Each event carries:
  - the simulated time;
  - the two bodies (Die, floor, wall or frozen Die);
  - the summed normal impulse;
  - the contact point.

  Only the accepted attempt's events survive. The persisted-contact behaviour is confirmed by logging a real Throw during the build, before the recording format is fixed.
- **Shaping lives in the sound module, at play time.** The recording keeps every event above a tiny physical floor. Speed changes the density per wall-clock window, so the mixer applies the rules as it plays:
  - gain = clamp(J / J_max, 0, 1) × jitter, with separate J_max values for die–die and die–tray contacts;
  - cull below J_max / 64;
  - merge events of the same body pair within 30 ms, keeping the loudest;
  - at most 8 new impacts per 50 ms of wall-clock playback, dropping the quietest first;
  - nothing after a Die is at rest;
  - a soft limiter on the output.

  The numbers are tunables, not contracts.
- **Sync.** The one playback clock (the one ADR-0011's stand-in tray drives) is the master. Each block is rendered for the moment it will be heard: now plus the sink's queued latency, at the clock's current speed. At ×4 only the timing compresses, and samples keep their natural pitch. One tunable A/V offset is set on the release checklist with a click-and-flash test. The aim is the ATSC window (audio no more than 15 ms early or 45 ms late), with audio slightly late when in doubt.
- **Output.** The mixer outputs 48 kHz stereo 16-bit. Each impact is equal-power panned, with modest width, by its contact point's screen-space x at mix time, so an orbited camera still pans correctly.
- **Lifecycle.**
  - One audio thread, owned by the sound module, pulls blocks from the mixer. The JavaFX, render and physics threads never touch the sink.
  - Samples load into memory on that thread at start-up.
  - The line opens for a Throw, hidden behind the pre-simulation's lead time, and closes after the last impact's tail. No idle stream keeps Windows from sleeping.
  - Reroll Throws and a queued Roll are just later events on the same clock.
- **Failure means silence.** Sound is optional, so unlike GL (ADR-0002) and Jolt (ADR-0003) it never fails fast. If no device can be opened, the app runs silently, logs it and posts one notice behind the bell. If the sink fails mid-run (say a headset is unplugged), the current sound is dropped and the default device is re-tried silently at the next Throw.
- **Controls.** Mute and volume sit in the Settings tab and persist in `settings.properties` (ADR-0007). The defaults are unmuted at 70%. Volume changes apply to sound already playing. Mute has no keyboard shortcut, because the Roll Expression box is always editable.
- **Testing (ADR-0005).**
  - *Pure-Java tests run the mixer without a device.* They check that:
    - every impact started maps to a contact event of the accepted attempt, nothing comes from discarded attempts, and nothing starts after its Die's rest;
    - the cull, merge and window cap hold, including at ×4;
    - muted output is all zeros, and volume scales the output;
    - a 20-Dice first landing never exceeds full scale;
    - a Shift press mid-Throw re-maps later start times;
    - a fixed-seed Roll sequence gives identical Face Values with sound on and off;
    - every category has at least 8 slices in the expected format, and `SOURCES.md` and the files match both ways.
  - *Jolt tests* check that a real Throw's recording has floor, wall and die events, each timed within release and rest with a positive impulse.
  - *The smoke test.* CI has no audio device, so `--smoke-test` passes silently and logs the audio state.
  - *The release checklist* covers listening on every built-in Tray, die-on-die contact (including against a frozen Die), holding and releasing Shift mid-Throw, the A/V offset, unplugging the device mid-run, and mute and volume.
