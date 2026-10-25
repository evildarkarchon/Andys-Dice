# PROTOTYPE: the tray, surface, camera and lighting (throwaway)

> Lives only on the `prototype/tray` branch. Never merge it. It answers one question for
> [What do the tray, surface, camera and lighting look like?](https://github.com/evildarkarchon/Andys-Dice/issues/17)
> and is kept as a primary source. It is built on the `prototype/materials` code: ADR-0008's numerals and
> ADR-0009's Dice Sets (translucency technique C, swirls baked), unchanged.

**Verdict (2026-10-24).** The player chooses the Tray: Trays are `.tray` data files like Dice Sets, over one
fixed 300 x 200 mm footprint with walls at least 30 mm high. The default is green felt in walnut, and the
leather hexagon and walnut board also ship. The escape volume is the visible walls. The Light Rig is its own
setting: studio (default), lamp and window ship, all with PCSS. Translucent shadows stay full. The camera
settles in, and a rerolled Die glows, then lifts. Recorded in
[ADR-0010](https://github.com/evildarkarchon/Andys-Dice/blob/main/docs/adr/0010-trays-are-data-files-over-one-footprint-under-a-chosen-light-rig.md).
The 20-Dice cap's leaning dice became
[How does a 20-Dice Throw reach a flat rest?](https://github.com/evildarkarchon/Andys-Dice/issues/21).

**Question.** What do the tray, its surface, the camera and the lighting look like in v1, in our GL 3.3
renderer? That covers the felt or wood floor, the walls and the tray size (against the 20-Dice cap), whether
the escape volume (ADR-0003) is the visible walls, the camera during and between Throws, how a rerolled Die is
picked up (ADR-0006), the light rig, the shadow technique, the reflection environment, and how translucent
Dice Sets cast shadows.

**Dice really move.** Enter pre-simulates a Throw headless on **Jolt** (jolt-jni 6.2.0, ADR-0003 roughly: a
centimetre world, rounded convex hulls with the 0.75 mm edge radius, a fresh single-threaded system per
attempt, 240 Hz steps, a recording at 60 Hz) and plays it back. An attempt ends at rest, cocked (up face more
than 4 degrees off level), escaped or timed out (12 s); anything but rest is re-simulated, up to 12 attempts.
Face Values are read off whatever lands: this prototype does not choose them first (ADR-0001), because the
look of the tray does not depend on it. E rerolls the low dice as a Reroll Throw: the resting dice become
static and only the rerolled ones are thrown again.

**The switcher** flips between three whole looks, radically different on purpose. Every dimension also has
its own key, so "the tray from A under the light of B" is two key presses.

| Key | Look | Surface, tray | Camera, pickup | Light, shadows |
|---|---|---|---|---|
| A | Felt tray | green felt; walnut rectangle, 30 mm walls, 300 x 200 mm | fixed 3/4; lift | studio softboxes; PCF |
| B | Leather hex tray under a lamp | brown leather; padded hexagon, 30 mm | settle-in; glow, then lift | one warm spot, dark room; PCSS |
| C | Open wooden board by a window | bare walnut; 15 mm lip, 400 x 260 mm | follow; pop | low cool daylight; PCSS |

## Run

From the repo root, with JDK 27 (Windows paths for `mvn.cmd`):

```
JAVA_HOME='C:\OpenJDK\jdk-27' mvn.cmd -q -f prototypes/tray/pom.xml package exec:exec
```

Options:

- `-Dtray.look=B`: start on a look (default A).
- `-Dtray.capture=target/capture`: an unattended tour. It saves 54 PNGs, each captioned with what the Throw
  took and the GPU time averaged over 30 frames, then exits. `-Dtray.shots=01,07` limits it to shot names
  starting with those prefixes.
- `-Dtray.main=andysdice.prototype.tray.ThrowStats -Dtray.runs=20`: no window. It throws the 20-Dice cap and
  a 7-piece set into every tray size, wall height, surface and escape volume, and writes
  `target/throw-stats.txt` (a copy from 2026-10-24 is [`throw-stats.txt`](throw-stats.txt)).

Keys:

| Key | Does |
|---|---|
| Enter / E / P | Throw / Reroll Throw of the low dice / replay the last segment (to watch the camera again) |
| `<-` / `->` | look A / B / C |
| D | dice: 1d20, 2d20, 4d6, the 7-piece set, 8d6, 20 mixed (the cap) |
| M / N | next / previous Dice Set (try Ruby, Emerald and Smoke for translucent shadows) |
| S, W, J, H, G | surface, tray shape, wall material, wall height (15 / 30 / 50 mm), tray size |
| X / Y | escape volume (visible walls / 200 mm glass) / draw it |
| V, K | camera mode, reroll pickup |
| L, O, A, U | light rig, shadow technique, contact AO, translucent shadows (full / tinted) |
| Q | render scale (1.0 / 0.75 / 0.5) |
| drag, wheel, Space | orbit (fixed and top cameras), zoom, reset the view |

## What to judge

1. **Surface.** Felt (green or navy), leather or bare wood? Felt and leather swallow bounces; wood clatters
   and Throws run a little longer.
2. **Tray.** Shape (rectangle, hexagon, oval), walls (square walnut, padded leather), wall height and size.
   Does it read as a dice tray? Is 300 x 200 mm right for 20 dice, or 400 x 260 mm?
3. **Escape volume.** Visible walls (dice that clear the wall top count as escaped and the Throw is redone) or
   taller invisible glass (dice bounce off air above a low lip)? Look at `11-escape-*`.
4. **Camera.** Fixed, follow, settle-in or top-down; and what it does between a Throw and its Reroll Throw
   (follow and settle-in pull back for the pickup, then frame the dice again).
5. **Reroll pickup.** Lift, pop, or glow-then-lift?
6. **Light and shadows.** Studio, lamp or window? PCF or PCSS? Contact AO on? Tinted or full translucent
   shadows?

## Findings from the build (2026-10-24, RTX 4070, JavaFX 27.0.1, JDK 27, 1280x800, MSAA 4x)

Whole-frame GPU time (shadow maps, table, tray, dice), averaged over 30 frames:

| Scene | GPU ms |
|---|---|
| Look A, 7-piece set / 20 dice | 0.21 / 0.28 |
| Look B (lamp, PCSS), 7-piece set / 20 dice | 0.32 / 0.32 |
| Look C (window, PCSS), 7-piece set / 20 dice | 0.28 / 0.32 |
| Close-up, PCF / PCSS: studio | 0.25 / 0.30 |
| Close-up, PCF / PCSS: window | 0.24 / 0.42 |
| Close-up, translucent Ruby, full / tinted shadows | 0.27 / 0.36 |
| Render scale 1.0 / 0.5, whole tray | 0.21 / 0.10 |

- **Everything fits in GL 3.3**, with no extension: PCSS reads the shadow map's raw depths through a
  sampler object, and tinted shadows are a second small depth-plus-colour map. With the rough 10-15x scale
  from the materials prototype, the dearest scene here (0.42 ms) would be 4-6 ms on integrated graphics.
- **PCSS is worth it only for a big, low light.** Under the studio softboxes it looks almost the same as PCF;
  under the window it gives the long, soft-ended shadows that sell the light, for about 0.18 ms more at a
  close-up. Its light sizes were tuned down after the first pass: a window treated as an 11-degree light
  washed the dice shadows out.
- **Tinted translucent shadows are barely visible on dark sets.** A 16-20 mm Ruby body absorbs nearly all the
  light, so its tinted shadow is close to the full one. They show on light sets like Smoke. They cost a
  second shadow pass (about 0.05-0.09 ms here).
- **Surface detail.** At the whole-tray view (about 4 px per mm) felt fibres and leather pebbles are below a
  pixel, and the floors read through their colour, mottling and sheen. Leather grain and wood figure show at
  close range. Render scale 0.5 halves the cost and mostly blurs the numerals, not the tray.
- **The 20-Dice cap rarely lands flat on the first try.** Leaning dice, not escapes, are what get Throws
  redone (see [`throw-stats.txt`](throw-stats.txt), 20 Throws per row):

  | 20 dice, 30 mm walls | attempts per Throw (mean / max) | Throws that used all 12 attempts | pre-simulation (mean / max) |
  |---|---|---|---|
  | 220 x 150 mm (felt) | 8.8 / 12 | 12 of 20 | 59 / 110 ms |
  | 300 x 200 mm (felt) | 4.9 / 12 | 1 of 20 | 26 / 65 ms |
  | 400 x 260 mm (felt) | 3.0 / 7 | 0 | 15 / 38 ms |

  A 7-piece set takes about 1.1 attempts in any tray. Pre-simulating even 12 attempts takes about 0.1 s, so
  retries cost no visible delay. The problem is the 20-Dice Throws that use every attempt and still come out
  cocked. The small tray can't hold the cap. The cocked dice measured were mostly leaning on a neighbour (a
  d6 tilted 35-45 degrees, its centre about 3 mm up), or stacked on one.
- **Wall height and escapes.** 15 mm walls let bouncy (wood) Throws escape often under the visible-walls rule
  (22 of 80 attempts in the medium tray); 30 mm walls almost never do. With the glass volume nothing escapes.
- **Throw duration.** From release to the last die asleep, Throws took 0.7-0.85 s on average (Jolt's 0.35 s
  sleep wait included). The release was tuned down from a harder throw that piled every die against the far
  wall.

## Prototype shortcuts

- openglfx embeds the canvas (v1 uses its own public-API bridge, ADR-0002).
- Face Values are not chosen first (ADR-0001), and the numbering is never rotated.
- The release is a canned handful over the near wall. There is no hand, no drag-to-throw, and no varying
  strength.
- The environments are analytic (softboxes, a lamp disc, a window rectangle), not cube maps.
- Contact AO is a sphere per die, not screen-space AO.
- The walls are drawn in the shadow map, but the floor and table never cast.
- The tray and its walls are one fixed material each. There is no wear, no stitching and no inlay.
- A roll's dice all share one Dice Set.

## Screenshots

A curated set is in [`screenshots/`](screenshots/) (JPEG). Run the capture tour for all 54 PNGs.
