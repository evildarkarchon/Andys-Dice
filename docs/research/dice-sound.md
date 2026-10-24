# Collision-matched dice sound on JDK 27

Research for [issue #18](https://github.com/evildarkarchon/Andys-Dice/issues/18). Three questions:
which audio API should play the clatter of a **Throw**, where the sounds should come from, and how
the recording's contact events (time, the Dice involved, impulse; ADR-0003) should drive them.

Each claim carries a tag:

- **[V]**: verified against the cited primary source (source code at a pinned tag, official docs,
  licence text on the hosting page).
- **[M]**: measured on the development machine with a throwaway probe. The probe ran on OpenJDK 27
  (build 27+35) under `--illegal-native-access=deny`, on Windows 11 with a USB headset as the
  default device. See [Method](#method). Latency figures depend on the device and driver.
- **[D]**: derived by computation or by reading code paths together.
- **[I]**: inference or design suggestion. No primary source settles it, so treat it as a default to
  tune by ear.

The project context is fixed and is not re-argued here: JDK 27 + JavaFX 27, a `jlink`ed
`jpackage` app-image, LWJGL natives laid into `app\natives` and loaded through
`-Dorg.lwjgl.librarypath`, `--illegal-native-access=deny` with per-module grants (ADR-0004), and
Throws pre-simulated by Jolt and played back from a recording (ADR-0003, ADR-0006).

---

## 1. Short answers

| Question | Answer |
|---|---|
| Playback API | **OpenAL Soft through LWJGL 3.4 (`org.lwjgl.openal`).** It loads under `deny` with only the existing `org.lwjgl` grant [M], gives 256 sources [M], per-source gain and pitch [V/M], reports its own output latency (32 ms here) [M], and can start a source at an exact device-clock time (`AL_SOFT_source_start_delay`) [V/M]. The cost is a 2 MB LGPL-2 `OpenAL.dll` that needs its own notice in `app\legal` (§2.3). |
| Fallback API | `javax.sound.sampled`. It needs no natives, no grant and no new module, because JavaFX already requires `java.desktop` [V/M]. On Windows it is the legacy DirectSound path: one Java thread per `Clip` [V/M], gain and mute only on mono lines (no pitch) [V/M], and 30–40 ms from `start()` until the frame position moves [M]. It works if each Throw is premixed into one buffer (§4.5). |
| Not recommended | JavaFX `AudioClip`. On desktop it builds a **new GStreamer `MediaPlayer` for every `play()`** [V], caps playback at **16 concurrent sounds** with priority stealing [V/M], ignores `pan` [V], can't play from memory [V], and OpenJDK still has "AudioClip needs native implementation" open because of play latency [V]. It also needs an extra `--enable-native-access=javafx.media` grant [M]. |
| Sourcing | **Recorded CC0 samples, sliced into single impacts.** Kenney's *Casino Audio* (CC0) has 4 `die-throw` clips holding about 3 hits each [V/D]. Freesound has CC0 polyhedral-dice recordings on felt trays, wooden desks and pleather trays, including a per-Die-Kind pack [V]. Avoid the Sonniss GDC bundle: its licence forbids supplying the files as sound effects, so they can't sit in a public repo [V]. |
| Procedural or modal? | Not for v1. A 16 mm die's own vibration modes lie near or above the top of human hearing [I], so the audible clack is mostly the broadband contact click and the surface's response. Research shows that click needs a separate "acceleration noise" model on top of modal synthesis, especially for small objects [V]. That is research-grade work with nothing to tune against. |
| How many variants | Shipped dice apps use 4–15 recordings per category: Dice So Nice has 7 per surface and 12–15 per dice material [V]; Owlbear Rodeo has 4 per weight class and surface, round-robin [V]. Suggested: **at least 8 single-impact slices per category**, shuffled with no immediate repeat, plus small random pitch and gain jitter [I] (§3.5). |
| Impulse to volume | Both open-source dice rollers map speed **linearly** to volume, clamped (Dice So Nice: `max(min(v/vmax, 1), 0.2)`; Owlbear: `min(v/5, 1)`) [V]. A linear modal model makes amplitude proportional to impulse [D]. Suggested: amplitude ∝ impulse, clamped at a per-category maximum, culled below about −36 dB of it [I] (§4.3). |
| Bursts | Dice So Nice suppresses a die–die sound in the same physics step as, or within 2 steps of, an earlier sound, and its replay buffer holds one sound per step [V/D]. Owlbear Rodeo allows one per die per 200 ms [V]. Suggested: merge events of the same body pair within 30 ms, keep the loudest, and cap new impacts per 50 ms window, dropping the quietest first [I] (§4.4). |
| Sync with playback | Trigger from the recording's **event times**, not from render frames. Dice So Nice plays only the collision of the frame it lands on and admits in a comment that a lagging frame drops collisions [V]. Because a Throw is fully pre-simulated before playback (ADR-0006), its whole soundtrack can be **premixed** and started once, at a scheduled device-clock time [I] (§4.5). |
| Getting the impulse | Jolt's `OnContactAdded` runs before the solver, so the impulse isn't known yet. Jolt's own docs point to `EstimateCollisionResponse` for impact sounds [V], and jolt-jni 6.2.0 exposes it as `MiscUtil.estimateCollisionResponse` [V] (§4.1). |

---

## 2. Playback

### 2.1 JavaFX 27 `AudioClip`

**How it works on desktop.** `AudioClipProvider` first tries a native `AudioClip` stack. If that
fails it falls back to `NativeMediaAudioClip` [V]
([AudioClipProvider.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/AudioClipProvider.java)).
In the jfx27 tree the native side (`nacInit` and friends) exists only for iOS
(`platform/ios/jni/com_sun_media_jfxmediaimpl_NativeAudioClip.m`) [V], so Windows, macOS and Linux
use the fallback [D]. The fallback:

- **Builds a new player per play.** Each `play()` posts a `NativeMediaAudioClipPlayer` to a
  single scheduler thread. That player calls `MediaManager.getPlayer(source())` to make a fresh
  `MediaPlayer`, and only applies volume, balance and rate and calls `play()` in `onReady` [V]
  ([NativeMediaAudioClipPlayer.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/NativeMediaAudioClipPlayer.java)).
  On Windows that player is a GStreamer pipeline ending in `directsoundsink` [V]
  ([GstPipelineFactory.cpp](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/native/jfxmedia/platform/gstreamer/GstPipelineFactory.cpp)).
  A new `MediaPlayer` on a short WAV took 32 ms cold and about 7 ms warm to reach READY [M]. That
  is before any audio is queued to the sink.
- **Caps playback at 16 sounds.** `MAX_PLAYER_COUNT = 16`. When it is full, a new play stops an
  active player of lower or equal priority, or is dropped if none exists [V]. With 24 overlapping
  plays, the internal counter read 16 of 16 [M].
- **Drops duplicates.** A play whose clip, volume, balance, rate, pan and priority all equal an
  already-queued play is silently dropped [V] (`schedule.contains(entry)`).
- **Ignores pan.** `pan` is stored but never passed to the player. Only volume, balance and rate
  are set [V].
- **Changes pitch only through rate.** `rate` is clamped to 0.125–8.0 [V]
  ([AudioClip.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/javafx/scene/media/AudioClip.java)).
  The pipeline applies a non-1.0 rate as a flushing GStreamer seek with that rate [V]
  ([GstAudioPlaybackPipeline.cpp](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/native/jfxmedia/platform/gstreamer/GstAudioPlaybackPipeline.cpp)).
  There is no `scaletempo` element in the tree, so a rate change should shift the pitch [I].
- **Can't play from memory.** It accepts only a URL ("HTTP, HTTPS, FILE or JAR source") [V].
  `create(byte[] …)` throws `UnsupportedOperationException` in the fallback [V]
  ([NativeMediaAudioClip.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/NativeMediaAudioClip.java)),
  so a premixed Throw can't be handed to it without writing a file.
- **Has no Ogg Vorbis or FLAC support.** Audio support is AAC, MP3 and PCM in WAV or AIFF [V]
  ([package-info.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/javafx/scene/media/package-info.java)).

**Latency is a known upstream problem.**
[JDK-8090414](https://bugs.openjdk.org/browse/JDK-8090414), "AudioClip needs native
implementation", says: "The current implementation of AudioClip was a stop-gap solution. It uses
NativeMediaPlayer to play sounds which introduces an inordinate amount of latency." It is open, fix
version "tbd" [V]. [JDK-8087996](https://bugs.openjdk.org/browse/JDK-8087996), "Timing problems
when playing multiple AudioClips consecutively", was closed as its duplicate [V].

**JPMS, `jlink` and native access.** `javafx.media` is a normal module in the JavaFX jmods. It
needs `glib-lite`, `gstreamer-lite` and `jfxmedia` DLLs, loaded through `javafx.graphics`'
`NativeLibLoader` [V]
([NativeMediaManager.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/NativeMediaManager.java)).
JEP 472 restricts both loading a library (charged to the caller's module) and binding a `native`
method (charged to the module that declares it) [V] ([JEP 472](https://openjdk.org/jeps/472)).
JavaFX's own release notes list `javafx.media` among the modules that need native access when used
[V] ([JavaFX 24 release notes](https://github.com/openjdk/jfx/blob/jfx24/doc-files/release-notes-24.md)).
Under `deny`, with only `javafx.graphics` granted, `new AudioClip(...)` failed with
`IllegalCallerException: Illegal native access from module javafx.media`. With
`--enable-native-access=javafx.graphics,javafx.media` it worked [M]. So `AudioClip` would add a
module, its DLLs and a launcher grant to ADR-0004's list.

### 2.2 `javax.sound.sampled`

**Already in the image.** `javafx.base` and `javafx.graphics` both `requires java.desktop` [V]
([javafx.base module-info](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.base/src/main/java/module-info.java)),
so `jlink` already includes `java.desktop` [D]. The probe ran under `deny` with **no**
`--enable-native-access` at all and opened 32 Clips [M]. So the JDK's own sound library needs no
grant [M]. JEP 472 itself doesn't spell out an exemption for JDK modules, so this rests on the
measurement.

**Windows implementation.** `java.desktop` provides two mixer providers, `DirectAudioDeviceProvider`
and `PortMixerProvider` [V]
([java.desktop module-info, jdk-27-ga](https://github.com/openjdk/jdk/blob/jdk-27-ga/src/java.desktop/share/classes/module-info.java)).
On Windows the data lines are DirectSound buffers [V]
([PLATFORM_API_WinOS_DirectSound.cpp](https://github.com/openjdk/jdk/blob/jdk-27-ga/src/java.desktop/windows/native/libjsound/PLATFORM_API_WinOS_DirectSound.cpp)).
The probe listed only "Direct Audio Device: DirectSound Playback" mixers and Port mixers [M].

- **Buffers.** Each line's DirectSound buffer is 2 s long (`2 * sampleRate * frameSize`) [V]. A
  `SourceDataLine` opened without a size gets a 500 ms Java-side buffer
  (`DEFAULT_LINE_BUFFER_TIME`) [V]. A `Clip` streams through a 1000 ms buffer (`CLIP_BUFFER_TIME`)
  fed by its own thread [V]
  ([DirectAudioDevice.java](https://github.com/openjdk/jdk/blob/jdk-27-ga/src/java.desktop/share/classes/com/sun/media/sound/DirectAudioDevice.java)).
  With a 0.5 s 48 kHz mono clip, `getBufferSize()` returned 96 000 bytes, i.e. 1 s [M].
- **Latency.** From `Clip.start()` until `getLongFramePosition()` first moved: min 29.6 ms, median
  39.5 ms, max 40.3 ms over 10 starts [M]. That is time to the play cursor moving, not time to sound
  at the ear. Windows mixes shared-mode streams in its audio engine, with a 10 ms default buffer
  [V] ([Microsoft Learn, Low Latency Audio](https://learn.microsoft.com/windows-hardware/drivers/audio/low-latency-audio)).
- **Polyphony.** `getMaxLines` for a Clip returns `-1` (unlimited) on the DirectSound mixer [V/M].
  32 Clips ran at once, backed by 32 "Direct Clip" threads [M]. The Clip thread is created per
  line [V].
- **Controls.** A mono line gets only `MASTER_GAIN` and `MUTE`. A stereo line adds `BALANCE` and
  `PAN` [V/M]. Gain is computed in Java as linear left and right factors and applied by the native
  write as data is copied into the DirectSound buffer [V], so a change is heard only after the
  audio already buffered [I]. There is **no `SAMPLE_RATE` control**, so pitch variation must be
  done by resampling in our code [V/M].
- **Formats.** The built-in readers are WAV (PCM, float and extensible), AU and AIFF [V]. There is
  no Ogg Vorbis, FLAC or MP3 reader.
- **Scheduling.** There is no "start at time T" API. Sync has to come from starting on time or from
  reading the line's frame position [I].

### 2.3 OpenAL Soft through LWJGL 3.4

**Version.** LWJGL's latest release is 3.4.3 (2026-08-23) [V]
([releases](https://github.com/LWJGL/lwjgl3/releases)). Its `lwjgl-openal` Windows natives jar
holds `windows/x64/org/lwjgl/openal/OpenAL.dll` (2.0 MB) and a `.git` stamp naming OpenAL Soft
commit `7f9c6de1`, 11 commits after the 1.25.2 tag [V/D]. The probe reported
`AL_VERSION = 1.1 ALSOFT 1.25.2` [M].

**JPMS and native access.** `module org.lwjgl.openal { requires transitive org.lwjgl; exports
org.lwjgl.openal; }` [V]
([module-info](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/openal/src/main/resources/module-info.java)).
The OpenAL bindings declare no `native` methods. They call the core's `invoke*` helpers [V]
([AL10.java](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/openal/src/generated/java/org/lwjgl/openal/AL10.java)),
and the core module loads `OpenAL.dll` through `Library.loadNative(ALC.class, "org.lwjgl.openal",
...)` [V]
([ALC.java](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/openal/src/main/java/org/lwjgl/openal/ALC.java)).
So JEP 472 charges everything to `org.lwjgl`, which ADR-0004 already grants [D]. Confirmed: with
`--illegal-native-access=deny --enable-native-access=org.lwjgl` and
`-Dorg.lwjgl.librarypath=<dir>` holding `lwjgl.dll` and `OpenAL.dll`, LWJGL logged "Loaded from
org.lwjgl.librarypath" for both and the probe ran [M]. On JDK 27 LWJGL 3.4.3 used FFM downcalls
[M]. Note that `ALC` is created in a static initialiser, so an explicit `ALC.create()` throws
"ALC has already been created" [M].

**Latency.** OpenAL Soft's defaults are a 512-frame update period ("~10.7ms") and 3 periods [V]
([alsoftrc.sample, 1.25.2](https://github.com/kcat/openal-soft/blob/1.25.2/alsoftrc.sample)).
`ALC_DEVICE_LATENCY_SOFT` reported **32.00 ms** on this machine, with the device at 96 kHz and a
100 Hz refresh [M]. Both can be tuned from an `alsoft.ini` or context attributes. A shorter mix-ahead
risks glitches under load [V] (same file).

**Polyphony.** The default limit is `sources = 256` [V]. The probe generated 256 sources before an
error and ran 64 silent voices at once with distinct gain and pitch [M]. `ALC_MONO_SOURCES`
reported 255 [M].

**Per-sound control.** Each source has `AL_GAIN`, `AL_PITCH` (resampling), position and velocity
for 3D panning [V/M]. Optional HRTF or stereo panning comes from the config file [V].

**Exact scheduling.** `AL_SOFT_source_start_delay` adds `alSourcePlayAtTimeSOFT`. It starts a
source when "the context device's clock time reaches the specified start_time, expressed in
nanoseconds". A time already in the past starts at once, and the extension requires
`ALC_SOFT_device_clock` [V]
([spec](https://openal-soft.org/openal-extensions/SOFT_source_start_delay.txt)). LWJGL 3.4.3 binds
both (`SOFTSourceStartDelay`, `SOFTDeviceClock`) [V], and both were present at run time [M].

**Licence.** OpenAL Soft is under the GNU **Library** GPL v2 [V]
([COPYING](https://github.com/kcat/openal-soft/blob/1.25.2/COPYING)). LWJGL keeps the text as
`modules/lwjgl/openal/openal_soft_license.txt` in its repo [V]. Neither the `lwjgl-openal` class jar
nor its natives jar contains a licence or notice file [M]. Shipping the unmodified DLL as a separate,
replaceable file in `app\natives` is the ordinary dynamic-linking case [I]. The release must still
carry the LGPL text, and it should say where to get the matching source (the commit hash above) [I].
ADR-0004's "build fails if a bundled artifact has no notice" check will catch this only if
`OpenAL.dll` is on its list.

### 2.4 Side by side

| | `AudioClip` (JavaFX 27) | `javax.sound.sampled` | OpenAL Soft (LWJGL 3.4.3) |
|---|---|---|---|
| Path on Windows | new GStreamer pipeline per play → DirectSound [V] | DirectSound buffer per line [V] | OpenAL Soft mixer → one OS stream [V] |
| Latency | per-play pipeline setup (≈7 ms warm to READY here) plus sink buffering; upstream bug open [V/M] | 30–40 ms `start()` → cursor moves [M] | 32 ms reported device latency [M] |
| Voices | 16, priority stealing [V/M] | unlimited lines, one thread per Clip [V/M] | 256 sources [V/M] |
| Volume / pitch / pan | volume, balance, rate (pitch via rate [I]); pan ignored [V] | gain, mute (+balance/pan on stereo); no pitch [V/M] | gain, pitch, 3D position [V/M] |
| Play from memory | no [V] | yes (`Clip.open(format, byte[], …)`, `SourceDataLine`) [V] | yes (`alBufferData`) [V] |
| Scheduled start | no | no | yes, nanosecond device clock [V/M] |
| New module / natives | `javafx.media` + 3 DLLs [V] | none (`java.desktop` already linked) [V/D] | `org.lwjgl.openal` + `OpenAL.dll` [V] |
| Native-access grant | adds `javafx.media` [M] | none [M] | none beyond `org.lwjgl` [M] |
| Licence burden | GPL+CE, already shipped with JavaFX [I] | none new | LGPL-2 text and source pointer [V/I] |

### 2.5 Recommendation

Use **OpenAL Soft through `org.lwjgl.openal`** [I]. LWJGL is already a dependency and is already
granted native access, so the new cost is one module, one DLL and one notice. It is the only option
with enough voices, per-voice pitch and a scheduled start. Keep the sound code behind a small seam
(play this premixed buffer at time T, set master volume, mute), so `javax.sound.sampled` can
stand in if the LGPL notice or the DLL ever becomes a problem [I]. Behind that seam
`javax.sound.sampled` is a workable fallback once Throws are premixed (§4.5). Don't use `AudioClip`.

Packaging consequences (all [I], following ADR-0004's existing pattern):

- Add `lwjgl-openal` (class jar) to the module path, and unpack its natives jar into `app\natives`
  next to `lwjgl.dll`. Never put the natives jar on the module path.
- No launcher change: `org.lwjgl` is already in `--enable-native-access`.
- Copy `openal_soft_license.txt` into `app\legal\` and add `OpenAL.dll` to the notice check.
- Open the device lazily on the first Throw, or on a worker at start-up. A machine with no audio
  device must degrade to silence, not fail fast as GL and Jolt do: sound is optional.

---

## 3. Sourcing

### 3.1 What a die impact sounds like, physically

- **The die's own modes are probably inaudible** [I]. Approximating a 16 mm acrylic die as a free
  sphere: its lowest vibration mode sits at roughly f ≈ 0.4 · c_s / r. With PMMA's shear-wave speed
  of about 1.0–1.4 km/s and r ≈ 8 mm, that gives several tens of kHz. A cube's lowest mode is lower
  than a sphere's, but not by enough to fall well below 20 kHz. This is an order-of-magnitude
  estimate, not a measurement.
- **Size scaling** [D]. For geometrically similar dice of one material, every modal frequency
  scales as 1/size. So a d4 (about 22 mm edge) and a d6 (16 mm) differ in pitch by their size ratio,
  whatever the absolute frequencies are. This justifies a small size-based pitch offset per Die
  Kind.
- **The click matters most for small objects.** Chadwick, Zheng and James (SIGGRAPH 2012) model
  "acceleration noise", the sound radiated by abrupt rigid-body acceleration in a collision. They
  estimate contact force profiles from rigid-body impulses with Hertz contact theory, and find that
  it "significantly complements the standard modal sound algorithm, especially for small objects"
  [V] ([project page](https://www.cs.cornell.edu/Projects/Sound/impact/)).
- **The surface shapes the colour** [I]. On felt the click is damped into a dull thud. On a wooden
  tray or table the board's own modes ring. That is why both open-source rollers key their sample
  sets by surface (§3.5).

### 3.2 CC0 recordings

Freesound licences are per sound, and a pack may mix licences. Every file below was checked on its
own page and shows "Creative Commons 0" [V].

| Source | What it is | Format | Licence |
|---|---|---|---|
| [Kenney, *Casino Audio* 1.1](https://kenney.nl/assets/casino-audio) | 50 files. Dice: `die-throw-1…4` (0.47–0.73 s), `dice-throw-1…3`, `dice-shake-1…3`, `dice-grab-1…2` [V] | Ogg Vorbis, 44.1 kHz stereo [V] | CC0, `License.txt` in the zip [V] |
| [ksigloch, *Dice Set: D4 – D100*](https://freesound.org/people/ksigloch/packs/33397/) | Per-Die-Kind single and multiple throws (D6, D8, D12, D20, …), e.g. [D8 – Single – 1](https://freesound.org/people/ksigloch/sounds/596051/): metal and acrylic d8s "thrown into pleather dice tray atop soft fabric", 1.27 s [V] | WAV 44.1 kHz / 24-bit [V] | CC0 [V] (checked on the D8 file; check each file) |
| [Vanalosswen, *Percentile Dice Rolling*](https://freesound.org/people/Vanalosswen/sounds/572451/) | Percentile dice shaken and "thrown into a felt-lined dice tray", 1.6 s [V] | WAV 44.1 kHz / 16-bit [V] | CC0 [V] |
| [2BACH, *Dice (6) pour on felt*](https://freesound.org/people/2BACH/sounds/185982/) and its pack [*Dice sounds (yatzy style)*](https://freesound.org/people/2BACH/packs/11753/) | d6s on felt: pour, slam, sweep, cup (Sennheiser MKH 416), 18.8 s [V] | WAV 48 kHz / 24-bit [V] | CC0 [V] (checked on the pour file) |
| [vartian, *Rolling Single and Dual 20-Sided Dice*](https://freesound.org/people/vartian/sounds/560085/) | A plastic d20 on a wooden desk, then two together (Zoom H6), 67 s [V] | WAV 96 kHz / 24-bit [V] | CC0 [V] |
| [WallyDM, *Dice Clacking Together and Rolling into a Soft Tray*](https://freesound.org/people/WallyDM/sounds/815672/) | Two d6s clacked together in hand (die on die), then rolled into a soft tray, 2.4 s [V] | MP3 48 kHz [V] | CC0 [V] |

A Freesound search for "dice" with the CC0 filter returned 1,011 sounds [V]
([search](https://freesound.org/search/?q=dice&f=license%3A%22Creative+Commons+0%22)). Few are
clean single impacts. Most are whole rolls with several hits, handling noise and room tone, so they
need slicing, trimming and fades [I]. Kenney's `die-throw` clips are already short and clean.
Silence detection at −40 dB finds about three separated hits in each (e.g. `die-throw-1`: hits near
0.16, 0.28 and 0.38 s) [D], so the four files give roughly 12 single impacts. Their surface isn't
stated [V]. Die-on-die material is the scarcest. WallyDM's clacks and the multi-die takes are the
best CC0 candidates found.

**Licences to avoid.**

- **Sonniss GDC Game Audio Bundle.** Royalty-free and no attribution, but the licence says the
  licensee "may not distribute, publish, sub-license or otherwise supply the sound effects as sound
  effects", whether on their own or as part of a library or pack. It adds that "Re-designed and
  manipulated sounds remain licensed material" [V]
  ([licence](https://sonniss.com/gdc-bundle-license/)). Raw files in a public MIT repo would break
  this [I].
- **Dice So Nice** (AGPL [V], [package page](https://foundryvtt.com/packages/dice-so-nice/)) and
  **Owlbear Rodeo dice** (GPL-3.0 [V], [repo](https://github.com/owlbear-rodeo/dice)). Study their
  technique, but don't copy their sound files or code into an MIT app [I].

**Formats.** Neither the JDK nor JavaFX reads Ogg Vorbis [V] (§2.1, §2.2). Convert to 16-bit PCM WAV
at asset-authoring time with ffmpeg or Audacity, and commit the WAVs [I]. Audio tools are fine under
the "no external 3D art tools" rule. Decoding Ogg at run time would need `lwjgl-stb`
(`STBVorbis` [V]; stb_vorbis is MIT or public domain [V],
[source](https://github.com/nothings/stb/blob/master/stb_vorbis.c)) plus another native, which
isn't worth it for under a megabyte of short slices [I]. Keep a `SOURCES.md` beside the samples
with each file's URL, author, licence and retrieval date, even though CC0 doesn't require it [I].

### 3.3 Procedural and modal synthesis

- **The classic method.** Modal synthesis renders an impact as a sum of damped sinusoids excited by
  the contact force. It was popularised for interactive simulation by FoleyAutomatic (van den Doel,
  Kry and Pai, SIGGRAPH 2001; [paper](https://www.cs.mcgill.ca/~kry/pubs/foleyautomatic/foleyautomatic.pdf),
  doi:10.1145/383259.383322).
- **Material parameters are the bottleneck.** Ren, Yeh and Lin (ACM TOG 2013) name the lack of
  automatic, satisfactory material parameters as a key obstacle to linear modal synthesis. They
  estimate those parameters from an example recording, then compensate for the residual that the
  linear model can't produce [V]
  ([project page](https://gamma.cs.unc.edu/AUDIO_MATERIAL)).
- **For dice the die's modes contribute little** (§3.1), and the click needs a separate
  acceleration-noise model [V]. A believable procedural die is therefore "click model + surface
  model + tuning", each part researched separately, with no ground truth in the repo [I].
- **Verdict** [I]. Recordings give believable clatter with far less effort and risk. Procedural
  sound is an interesting later experiment, perhaps a synthetic tray body driven by recorded
  clicks. It shouldn't be on the v1 path.

### 3.4 Avoiding audible repetition

Shipped practice [V]:

- **Dice So Nice** keeps two sprite sheets. `surfaces.json` has 7 felt, 7 wood-table, 7 wood-tray
  and 9 metal surface hits. `dicehit.json` has 15 plastic, 12 metal, 12 wood and 6 coin die hits.
  It picks one uniformly at random per event
  ([SoundManager.js @ a21ede8b](https://gitlab.com/riccisi/foundryvtt-dice-so-nice/-/blob/a21ede8b21a322b25fbf0d99dc89470f2ebe2711/module/engine/SoundManager.js)).
- **Owlbear Rodeo** has 4 files per (weight class × material) for dice, leather and wood, with
  light, medium and heavy weight classes. It cycles them round-robin
  ([getAudioBuffer.ts @ ccc32bec](https://github.com/owlbear-rodeo/dice/blob/ccc32beceee0888c0a48129fbb23f4a636c710ee/src/audio/getAudioBuffer.ts)).
- **Unity's Audio Random Container** is the engine-level version of the same idea. *Shuffle*
  "avoids repetition until all audio clips have played", *Avoid Repeating Last* sets "how many
  items can play before an object plays again", and pitch and volume get min/max randomisation [V]
  ([Unity manual](https://docs.unity3d.com/Manual/AudioRandomContainer-UI.html)).

Suggested defaults [I]:

- **Categories.** Die → tray floor, die → tray wall, and die → die. If Dice Sets vary in material
  (plastic vs metal), a die-material axis too. The tray surface is whatever the renderer draws (felt
  or wood), not a user setting.
- **At least 8 slices per category.** Dice So Nice's 7–15 per category is the best real reference,
  and a Throw of up to about 20 Dice produces far more impacts per second than one footstep stream.
- **Variation.** Shuffle with no repeat of the last 3. Add ±1 semitone random pitch, ±1.5 dB random
  gain, and a deterministic pitch offset per Die Kind from its size (§3.1). With 8 slices,
  continuous jitter and overlapping hits, exact repeats are rare.
- **Determinism.** Draw variant and jitter choices from a generator seeded per Throw, never from the
  Face Value generator (ADR-0001). A replayed Throw then sounds the same, and sound can never shift
  a result.

---

## 4. Triggering

### 4.1 Getting a usable impulse out of Jolt

- **No impulse at `OnContactAdded`.** "When contacts are added, the constraint solver has not run
  yet, so the collision impulse is unknown at that point." The body velocities are pre-resolution
  velocities, which "you can use … to estimate the collision impulse to e.g. determine the volume of
  the impact sound to play (see: EstimateCollisionResponse)" [V]
  ([ContactListener.h](https://github.com/jrouwe/JoltPhysics/blob/master/Jolt/Physics/Collision/ContactListener.h)).
- **The estimator.** `EstimateCollisionResponse` returns estimated contact and friction impulses
  and the resulting velocities. It "can be used in the ContactListener::OnContactAdded to determine
  the strength of the collision to e.g. play a sound". It is "accurate when two bodies collide but
  will not be accurate when more than 2 bodies collide at the same time" [V]
  ([EstimateCollisionResponse.h](https://github.com/jrouwe/JoltPhysics/blob/master/Jolt/Physics/Collision/EstimateCollisionResponse.h)).
  jolt-jni 6.2.0 wraps it as `MiscUtil.estimateCollisionResponse(body1, body2, manifold,
  storeResult, combinedFriction, combinedRestitution[, minVelocity[, numIterations]])`, with
  `CollisionEstimationResult.getContactImpulses()` [V]
  ([MiscUtil.java @ 6.2.0](https://github.com/stephengold/jolt-jni/blob/6.2.0/src/main/java/com/github/stephengold/joltjni/MiscUtil.java)).
- **A unit trap.** jolt-jni documents `minVelocity` as "in meters per second, default=1" [V]. The
  physics world is in centimetres (ADR-0003), so pass the same value the `PhysicsSettings` use, not
  the default [I].
- **Tumbling dice keep their contacts** [I]. Jolt sends at most one Added or Persisted callback per
  body/sub-shape pair per collision step [V] (ContactListener.h). A die tumbling across the tray
  floor without fully lifting off keeps one persisted contact while its faces slap down, so only
  watching `OnContactAdded` would miss most of the clatter. Estimate in `OnContactPersisted` too,
  and emit an event when the normal approach speed at the contact rises above a threshold, i.e.
  each new slap.
- **Record the event types the mix needs** [I]: time (the collision step's simulated time), the two
  bodies (Die, tray floor, tray wall, or a frozen Die in a Reroll Throw), the summed normal impulse,
  and optionally a contact point for stereo placement.

### 4.2 What other dice rollers do

**Dice So Nice** (cannon-es, pre-simulated in a web worker, then replayed)
([PhysicsWorker.js @ a21ede8b](https://gitlab.com/riccisi/foundryvtt-dice-so-nice/-/blob/a21ede8b21a322b25fbf0d99dc89470f2ebe2711/module/web-workers/PhysicsWorker.js),
[ThrowEngine.js](https://gitlab.com/riccisi/foundryvtt-dice-so-nice/-/blob/a21ede8b21a322b25fbf0d99dc89470f2ebe2711/module/engine/ThrowEngine.js)) [V]:

- It listens to cannon's `collide` event and classifies each event as die–die or die–table.
- It uses the **speed** of the moving body as the strength, not the impulse.
- It culls below 250 (die–die) and 100 (die–table) legacy units/s.
- Volume is `calculateStrength(speed, max, 0.2) = max(min(speed / max, 1), 0.2)`, with max 550 for
  die–die and 500 for die–table. That is linear with a 0.2 floor.
- It rate-limits with `lastSoundStep` and `soundDelay = 2` world steps. Inside that window
  `shouldSkipSound` drops a die–die event but always lets a die–table event through.
- In simulate mode it stores the sound per iteration in `detectedCollides[iteration]`. That is one
  slot per step, so a later event in the same step overwrites an earlier one [D]. Playback
  advances `iteration` per frame and plays only `detectedCollides[this.iteration]`. Its comment:
  "if the game is lagging, it means some collisions won't be played."

**Owlbear Rodeo dice** (Rapier, live simulation)
([PhysicsDice.tsx @ ccc32bec](https://github.com/owlbear-rodeo/dice/blob/ccc32beceee0888c0a48129fbb23f4a636c710ee/src/dice/PhysicsDice.tsx)) [V]:

- Each die fires on `onCollisionEnter`, with a 200 ms cooldown per die (`AUDIO_COOLDOWN`).
- Volume is `Math.min(speed / 5, 1)` from the die's linear velocity, plus `THREE.PositionalAudio`
  for placement.
- The sample set is chosen by the die's weight class and the other body's physical material.

Neither uses the true impulse. Both clamp a linear map, both rate-limit with a fixed cooldown, and
both select samples by (surface, die class). The 200 ms per-die cooldown would swallow fast
die-on-die chatter [I].

### 4.3 Impulse-to-volume curve

- **Physics** [D]. A linear (modal) model's output scales linearly with its input. So for one
  contact type the radiated amplitude is proportional to the impulse J, and the gain should be
  linear in J, not in J². The ear's own compression takes care of perceived loudness.
- **A sensible scale** [D]. An acrylic 16 mm d6 weighs about 4.8 g (4.1 cm³ × 1.18 g/cm³). Dropped
  10 cm, it lands at about √(2 · 981 · 10) ≈ 140 cm/s. With restitution e ≈ 0.5 the normal impulse
  is J ≈ m(1+e)v ≈ 0.01 N·s. That is ≈ 1.0 kg·cm/s in the centimetre world, if masses are in
  kilograms. Use the release conditions' largest expected impact to set J_max per category.
- **Suggested curve** [I]:
  - gain = clamp(J / J_max, 0, 1) · jitter.
  - Cull events with J < J_max / 64 (about −36 dB). They are inaudible under the rest of the
    clatter, and culling them stops resting jitter from producing ticks.
  - Use separate J_max for die–die (lighter bodies, so smaller impulses) and die–tray.
  - Consider J / m_die (that is, Δv) for choosing the sample's "hard vs soft" variant, and J itself
    for gain.
- **Floor or no floor** [I]. Dice So Nice's 0.2 floor makes every surviving event at least −14 dB.
  That suits a speed proxy, but with a true impulse plus a cull threshold no floor is needed.
- **A master limiter** [I]. On the premixed buffer (§4.5), a soft limiter or simple normalisation
  stops 20 simultaneous first impacts from clipping.

### 4.4 Culling bursts

The first landing of a large Throw produces many contacts in one or two steps, and resting dice
produce micro-contacts. Suggested rules [I], applied when the recording is built or when the mix is
rendered:

1. **Per pair.** Merge events of the same body pair within 30 ms, keeping the largest J. This is
   shorter than Owlbear's 200 ms, so a die rattling against a wall stays audible.
2. **Global.** Allow at most about 8 new impacts per 50 ms window, keeping the loudest. This is the
   same idea as Unreal's concurrency *Max Count* with *Stop Quietest*, "Stop the quietest sound" [V]
   ([Unreal sound concurrency](https://dev.epicgames.com/documentation/en-us/unreal-engine/sound-concurrency-reference-guide)).
3. **Rest.** Drop every event after a Die is at rest, as sleep-detected by the recording.
4. **Retries.** Only the accepted pre-simulation attempt contributes. Cocked, escaped or timed-out
   attempts are discarded with their events (ADR-0003).

With a premixed Throw, voice count is no longer a constraint. These rules exist for clarity and
loudness, not for the mixer [I].

### 4.5 Keeping sound in sync with recorded playback

- **Trigger by time, not by frame** [I]. Dice So Nice plays a collision only when the displayed
  iteration lands exactly on its index, so a skipped frame silently drops sounds [V] (§4.2). Each
  event carries a simulated time, and playback maps the recording's clock to wall time anyway.
  Sound should use that same mapping.
- **Premix per Throw (recommended)** [I]. ADR-0006 pre-simulates the whole chain of Throws before
  playback starts. So after a Throw's accepted attempt, the worker can render its soundtrack:
  - Mix every surviving event's sample into one float buffer at its event time.
  - Apply the gain and pitch (by resampling) per event.
  - Pan by the contact's x position.
  - Add the limiter.

  The cost is tiny (20 Dice × 40 events × 0.25 s at 48 kHz is under 10 M multiply-adds), and the
  buffer is about 0.6 MB for 3 s of mono float. Playback then starts one source:
  - **OpenAL:** `alSourcePlayAtTimeSOFT(source, T0 + offset)` against `ALC_DEVICE_CLOCK_SOFT` [V],
    where T0 is the device-clock time matching the Throw's first presented frame.
  - **javax.sound fallback:** start a `Clip` holding the mix (or stream it through a
    `SourceDataLine`) at the first frame. Optionally slave the playback clock to the line's frame
    position.

  This removes voice limits, per-event scheduling jitter and dropped events in one step.
- **What premixing gives up** [I]. Changing playback speed or skipping mid-Throw needs a re-render
  or a stop. That is acceptable, because a Throw is a fixed animation. A future "drag the dice"
  interactive mode would need live per-event voices, which OpenAL also supports (256 sources [M]).
- **Latency budget** [I]:
  - **Video.** ADR-0002's async readback adds about one frame, plus the JavaFX pulse that presents
    it, so roughly 17–35 ms at 60 Hz.
  - **Audio.** OpenAL Soft reported 32 ms of device latency here [M], plus Windows' engine buffer.
  - **Compensation.** The two land in the same range. Expose one audio-offset constant, measured
    once with a click-and-flash test, and schedule audio at T0 + (video latency − audio latency).
- **Tolerance** [V]. ATSC IS-191 recommends that audio lead video by no more than 15 ms and lag by
  no more than 45 ms. ITU-R BT.1359-1's detectability threshold is 45 ms lead to 125 ms lag (as
  summarised by [Wikipedia](https://en.wikipedia.org/wiki/Audio-to-video_synchronization); the ITU
  page, [BT.1359](https://www.itu.int/rec/R-REC-BT.1359/en), doesn't summarise the numbers). A dice
  impact is a sharp visual event, so aim at the stricter ATSC window and, if in doubt, let audio be
  slightly late rather than early [I].

### 4.6 Reroll Throws

Each Reroll Throw has its own recording (ADR-0006), so it gets its own premix and its own scheduled
start [I]. Dice not being rerolled are static bodies there, but they are still Dice. A rerolled Die
hitting one should use the **die → die** category, not the tray category [I]. The pick-up between
Throws is silent unless a "grab" sample is added (Kenney ships `dice-grab-1…2` [V]).

---

## 5. Open questions and inferences to revisit

- **Acoustic latency wasn't measured.** The figures are API-side (cursor movement, reported device
  latency). A loopback or microphone click test would give the true end-to-end numbers for both
  audio and video.
- **The die's modal frequencies** (§3.1) are an order-of-magnitude estimate from a sphere
  approximation. They are never used as a number, only as the argument against modal synthesis of
  the die itself.
- **The sample slices haven't been auditioned.** Whether Kenney's or the Freesound recordings sound
  right on the tray's surface needs a listen. The tray's material (felt or wood) isn't decided yet.
- **The LGPL obligations** for shipping `OpenAL.dll` are inferred from the licence type, not
  reviewed. The source-availability wording for the release notes should be checked.
- **Jolt persisted-contact behaviour** for a tumbling die (§4.1) is inferred from the callback
  contract. It should be confirmed by logging Added and Persisted events on a real Throw before the
  recording format is fixed.
- **The `minVelocity` argument** of jolt-jni's estimator is documented in m/s but follows the
  world's units. Check it against the centimetre world.

---

## Method

- **Source reading.** Read at pinned tags: OpenJFX `jfx27` (`javafx.media`, `javafx.base`,
  `javafx.graphics`), OpenJDK `jdk-27-ga` (`java.desktop` sound), LWJGL `3.4.3` (`openal`
  module), OpenAL Soft `1.25.2`, Jolt `master`, jolt-jni `6.2.0`, Dice So Nice at `a21ede8b`, and
  Owlbear Rodeo dice at `ccc32bec`.
- **Licences.** Read on each sound's own Freesound page, in Kenney's `License.txt`, and on Sonniss'
  licence page.
- **Natives jar.** Listed `lwjgl-openal-3.4.3-natives-windows.jar` and read its `OpenAL.dll.git`
  stamp. Compared that commit with the `1.25.1` and `1.25.2` tags through the GitHub compare API
  (11 commits ahead of 1.25.2, 0 behind).
- **Kenney clips.** Probed with `ffprobe` for codec and duration. Counted hits with `ffmpeg -af
  silencedetect=noise=-40dB:d=0.03`.
- **Probe.** A throwaway named module (`requires java.desktop, javafx.media, org.lwjgl.openal`)
  compiled with OpenJDK 27 against Maven Central's `javafx-*-27-win` jars and `lwjgl{,-openal}`
  3.4.3. It was not committed.
  - **Run flags.** Every run used `--illegal-native-access=deny`. LWJGL natives were unpacked into
    a directory passed as `-Dorg.lwjgl.librarypath`, never as natives jars on the module path, as
    in ADR-0004. Native-access grants were varied per run.
  - **Silence only.** Every buffer it played was silence, or played at volume 0.
  - **`OalProbe`** opened the default device, read version, frequency, refresh, mono-source count,
    extension flags and `ALC_DEVICE_LATENCY_SOFT`. It generated sources until an error, and played
    64 silent sources with distinct gain and pitch.
  - **`JsProbe`** listed mixers and Clip controls. It timed `Clip.start()` until
    `getLongFramePosition()` became non-zero, 10 runs, and started 32 Clips at once, counting
    "Direct Clip" threads.
  - **`FxProbe`** built an `AudioClip` from a silent WAV and fired 24 overlapping plays, then read
    `NativeMediaAudioClipPlayer.getPlayerCount()`/`getPlayerLimit()` by reflection (`--add-opens`).
    It ran with and without the `javafx.media` grant.
  - **`FxPlayerReady`** timed `new MediaPlayer(media)` until READY, 12 runs, as the per-play setup
    an `AudioClip` pays.

## Sources

Primary source code and specs:

- OpenJFX, `jfx27` branch: `javafx.media`
  ([AudioClip.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/javafx/scene/media/AudioClip.java),
  [AudioClipProvider.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/AudioClipProvider.java),
  [NativeMediaAudioClip.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/NativeMediaAudioClip.java),
  [NativeMediaAudioClipPlayer.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/NativeMediaAudioClipPlayer.java),
  [NativeMediaManager.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/NativeMediaManager.java),
  [package-info.java](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/java/javafx/scene/media/package-info.java),
  [GstPipelineFactory.cpp](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/native/jfxmedia/platform/gstreamer/GstPipelineFactory.cpp),
  [GstAudioPlaybackPipeline.cpp](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.media/src/main/native/jfxmedia/platform/gstreamer/GstAudioPlaybackPipeline.cpp)),
  [javafx.base module-info](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.base/src/main/java/module-info.java),
  [javafx.graphics module-info](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.graphics/src/main/java/module-info.java)
- OpenJFX release notes 24 (native access):
  https://github.com/openjdk/jfx/blob/jfx24/doc-files/release-notes-24.md
- OpenJDK bugs: https://bugs.openjdk.org/browse/JDK-8090414 , https://bugs.openjdk.org/browse/JDK-8087996
- OpenJDK `jdk-27-ga`:
  [java.desktop module-info](https://github.com/openjdk/jdk/blob/jdk-27-ga/src/java.desktop/share/classes/module-info.java),
  [DirectAudioDevice.java](https://github.com/openjdk/jdk/blob/jdk-27-ga/src/java.desktop/share/classes/com/sun/media/sound/DirectAudioDevice.java),
  [PLATFORM_API_WinOS_DirectSound.cpp](https://github.com/openjdk/jdk/blob/jdk-27-ga/src/java.desktop/windows/native/libjsound/PLATFORM_API_WinOS_DirectSound.cpp)
- JEP 472, Prepare to Restrict the Use of JNI: https://openjdk.org/jeps/472
- LWJGL 3.4.3:
  [releases](https://github.com/LWJGL/lwjgl3/releases),
  [openal module-info](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/openal/src/main/resources/module-info.java),
  [ALC.java](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/openal/src/main/java/org/lwjgl/openal/ALC.java),
  [AL10.java](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/openal/src/generated/java/org/lwjgl/openal/AL10.java),
  [SOFTSourceStartDelay.java](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/openal/src/generated/java/org/lwjgl/openal/SOFTSourceStartDelay.java),
  [SOFTDeviceClock.java](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/openal/src/generated/java/org/lwjgl/openal/SOFTDeviceClock.java),
  [openal_soft_license.txt](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/openal/openal_soft_license.txt),
  natives jar: https://repo1.maven.org/maven2/org/lwjgl/lwjgl-openal/3.4.3/
- OpenAL Soft 1.25.2:
  [alsoftrc.sample](https://github.com/kcat/openal-soft/blob/1.25.2/alsoftrc.sample),
  [COPYING](https://github.com/kcat/openal-soft/blob/1.25.2/COPYING),
  [AL_SOFT_source_start_delay](https://openal-soft.org/openal-extensions/SOFT_source_start_delay.txt)
- stb_vorbis licence: https://github.com/nothings/stb/blob/master/stb_vorbis.c
- Jolt Physics:
  [ContactListener.h](https://github.com/jrouwe/JoltPhysics/blob/master/Jolt/Physics/Collision/ContactListener.h),
  [EstimateCollisionResponse.h](https://github.com/jrouwe/JoltPhysics/blob/master/Jolt/Physics/Collision/EstimateCollisionResponse.h);
  jolt-jni 6.2.0 [MiscUtil.java](https://github.com/stephengold/jolt-jni/blob/6.2.0/src/main/java/com/github/stephengold/joltjni/MiscUtil.java)
- Microsoft Learn, Low Latency Audio:
  https://learn.microsoft.com/windows-hardware/drivers/audio/low-latency-audio

Applications studied (technique only; copyleft licences):

- Dice So Nice (AGPL), commit `a21ede8b`:
  [SoundManager.js](https://gitlab.com/riccisi/foundryvtt-dice-so-nice/-/blob/a21ede8b21a322b25fbf0d99dc89470f2ebe2711/module/engine/SoundManager.js),
  [PhysicsWorker.js](https://gitlab.com/riccisi/foundryvtt-dice-so-nice/-/blob/a21ede8b21a322b25fbf0d99dc89470f2ebe2711/module/web-workers/PhysicsWorker.js),
  [ThrowEngine.js](https://gitlab.com/riccisi/foundryvtt-dice-so-nice/-/blob/a21ede8b21a322b25fbf0d99dc89470f2ebe2711/module/engine/ThrowEngine.js),
  [package page](https://foundryvtt.com/packages/dice-so-nice/)
- Owlbear Rodeo dice (GPL-3.0), commit `ccc32bec`:
  [PhysicsDice.tsx](https://github.com/owlbear-rodeo/dice/blob/ccc32beceee0888c0a48129fbb23f4a636c710ee/src/dice/PhysicsDice.tsx),
  [getAudioBuffer.ts](https://github.com/owlbear-rodeo/dice/blob/ccc32beceee0888c0a48129fbb23f4a636c710ee/src/audio/getAudioBuffer.ts)
- Unity, Audio Random Container: https://docs.unity3d.com/Manual/AudioRandomContainer-UI.html
- Unreal Engine, Sound Concurrency:
  https://dev.epicgames.com/documentation/en-us/unreal-engine/sound-concurrency-reference-guide

Sample sources and licences:

- Kenney, Casino Audio (CC0): https://kenney.nl/assets/casino-audio
- Freesound (each CC0, checked per file): https://freesound.org/people/ksigloch/packs/33397/ ,
  https://freesound.org/people/ksigloch/sounds/596051/ ,
  https://freesound.org/people/Vanalosswen/sounds/572451/ ,
  https://freesound.org/people/2BACH/sounds/185982/ ,
  https://freesound.org/people/2BACH/packs/11753/ ,
  https://freesound.org/people/vartian/sounds/560085/ ,
  https://freesound.org/people/WallyDM/sounds/815672/
- Sonniss GDC bundle licence (rejected): https://sonniss.com/gdc-bundle-license/

Research papers:

- K. van den Doel, P. G. Kry, D. K. Pai, "FoleyAutomatic: Physically-based Sound Effects for
  Interactive Simulation and Animation", SIGGRAPH 2001, doi:10.1145/383259.383322:
  https://www.cs.mcgill.ca/~kry/pubs/foleyautomatic/foleyautomatic.pdf
- J. N. Chadwick, C. Zheng, D. L. James, "Precomputed Acceleration Noise for Improved Rigid-Body
  Sound", ACM TOG 31(4), SIGGRAPH 2012: https://www.cs.cornell.edu/Projects/Sound/impact/
- Z. Ren, H. Yeh, M. C. Lin, "Example-Guided Physically Based Modal Sound Synthesis", ACM TOG
  32(1), 2013: https://gamma.cs.unc.edu/AUDIO_MATERIAL

Secondary (labelled where used):

- Wikipedia, Audio-to-video synchronization (BT.1359 and IS-191 figures):
  https://en.wikipedia.org/wiki/Audio-to-video_synchronization ; ITU-R BT.1359 record:
  https://www.itu.int/rec/R-REC-BT.1359/en
