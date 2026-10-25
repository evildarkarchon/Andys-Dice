# PROTOTYPE: engraved, inked numerals on every Die Kind (throwaway)

> Lives only on the `prototype/numerals` branch. Never merge it. It answers one question for
> [How are engraved, inked numerals made and laid out on every Die Kind?](https://github.com/evildarkarchon/Andys-Dice/issues/15)
> and is kept as a primary source.

**Verdict (2026-10-24).** Technique **B** (parallax occlusion mapping plus self-shadow). Engraving
0.4 mm deep, with a thin film of ink. Chessex-style font, bold, 6/9 underlined. Numeral size capped:
the human picked 11.6 mm for the d6, which is 0.80 of its flat-face inscribed diameter, and the same
cap applies to every kind (`DieModel.CAP_OF_INSCRIBED`). d12/d20 orientation **tops toward the top**.
The percentile d10 runs perpendicular, apex to equator, and the edge radius is 0.75 mm (both
delegated to the agent). The prototype now starts with these settings. Recorded in
[ADR-0008](https://github.com/evildarkarchon/Andys-Dice/blob/main/docs/adr/0008-numerals-are-parallax-occlusion-engravings-from-a-procedural-atlas.md).

**Question.** How should engraved, inked numerals be made and laid out on all seven Die Kinds? That
covers the engraving technique, the procedural font, numeral size and 6/9 marks, per-face orientation
(the d12, d20, percentile d10 and top-read d4), and the edge radius and engraving depth.

All seven Die Kinds sit on the bake-off's felt tray and are drawn by its GL 3.3 PBR renderer. Each solid
is built from the research's face-normal tables (intersection of half-spaces `n·x <= r_in`), rounded by
a Minkowski sum with a sphere (the same shape Jolt's convex radius gives), and numbered per the Chessex
layouts. The numerals come from a **procedural stroke font** (centre-line strokes stroked and unioned in
Java2D, no font files). They are rasterised into one atlas per kind, already rotated into each face's
frame.

The switcher flips the **engraving technique**. Everything else is a key, and the HUD shows every
setting at all times.

| Key | Variant | What it is |
|---|---|---|
| A | Normal map + one-step parallax | The bake-off baseline: the slope-derived normal plus a single parallax offset from one atlas. Cheapest. No occlusion inside the recess. |
| B | Parallax occlusion mapping + self-shadow | A ray march through the engraving profile (12-48 steps by angle), then a march toward the key light so the recess shadows itself. Same atlas, fragment cost only. |
| C | Real recessed geometry | The flat faces are re-meshed at 0.09 mm spacing (about 4M triangles for the set) and pushed in by the vertex shader. The shadow map does the self-shadowing. This stands in for "real geometry"; v1 would extrude glyph outlines instead. |

## Run

From the repo root, with JDK 27 (Windows paths for `mvn.cmd`):

```
JAVA_HOME='C:\OpenJDK\jdk-27' mvn.cmd -q -f prototypes/numerals/pom.xml package exec:exec
```

Options:

- `-Dnumerals.variant=B`: start on a variant.
- `-Dnumerals.capture=target/capture`: an unattended tour. It saves about 50 PNGs (every die, every
  technique at macro, every orientation rule, mark, font, depth and ink setting), then exits. Add
  `-Dnumerals.shots=03,04` to limit the tour to shot names that start with those prefixes.

Keys:

| Key | Does |
|---|---|
| `<-` / `->` | technique A / B / C |
| `0`, `1`-`7` | whole set; focus d4, d6, d8, d10, d%, d12, d20 |
| `Z` | close-up / macro (while focused) |
| `N` | next face up (the focused die, or every die) |
| drag, wheel, `Space`, `T` | orbit and tilt, zoom, auto-orbit, slow tumble (shows every face) |
| `M` | Dice Set: opaque black/white, crimson/white, ivory/black, gunmetal/gold |
| `E` | edge radius 0.5 / 0.75 / 1.0 mm |
| `X` | engraving depth 0.3 / 0.4 / 0.5 mm |
| `I` | ink fill: film / half / brim (visible depth = depth x (1 - fill)) |
| `F` | font: Chessex-style (track 0, hooked 7, flag 1) / plain (oval 0, straight 7, based 1) |
| `W` | stroke weight: regular / bold / heavy |
| `S` | numeral size: 70-100% of the largest that fits every face of the kind |
| `U` | 6/9 mark: underline / dot / none |
| `O` | d12/d20 orientation rule (four candidates, below) |
| `P` | percentile direction: perpendicular apex-to-equator (Chessex photo) / perpendicular equator-to-apex / parallel (D&G) |

## What to judge

1. **Technique.** Flip A/B/C at macro (`Z`) on the d20 and d6, at several orbit angles. Does A hold up
   close, or do you want B's or C's occlusion? Then check whether you can tell them apart in the
   whole-set view.
2. **Ink.** With `I` and `X`, decide how deep the recess is and how full of ink it is.
3. **Font.** Compare the Chessex marks and the plain style (`F`), the stroke weight (`W`), the 6/9
   mark (`U`), and the size (`S`). Under "as large as possible", the d6 numeral comes out 12.5 mm on a
   16 mm face.
4. **Orientation.** On the d12 and d20, only the top face and its neighbours come from photos. Cycle
   `O` and use `N` or `T` to see every face:
   - *away from the top*: baselines toward the neighbour nearest the 20/12, so tops flow away from it.
     This extends the d20 photo's rule (2, 8 and 14 have their baselines toward the 20).
   - *lowest* / *highest neighbour*: arbitrary but consistent, for contrast.
   - *tops toward the top*: tops point at the edge toward the 20/12. This matches the research's
     reading of the Chessex d12 photo, "an underlined 6/9 below the 12" (a 9 that reads upright with the
     12). The d20's 2, 8 and 14 keep the photo's rule.
5. **Percentile d10** (`P`) and the **top-read d4** (focus `1`).
6. **Edge radius** (`E`). Physics shares it as Jolt's convex radius.

## Findings from the build (2026-10-24, RTX 4070, JavaFX 27.0.1, JDK 27)

- **One generic solid builder covers all seven kinds.** It intersects the research tables' half-spaces
  and merges vertices within 0.2% of the inradius, because the tables carry 4 decimals and the five d10
  apex planes miss each other by about 1e-4. Topology checks: d4 4 vertices / 4 triangles, d6 8 / 6
  squares, d8 6 / 8 triangles, d10 and d% 12 / 10 kites, d12 20 / 12 pentagons, d20 12 / 20 triangles.
- **Layouts check out against the research.** The d20 shows 20 with the 8 directly below, 2 to the
  upper left and 14 to the upper right. On the d10 with 9 up, the near faces are 2 and 6, both
  upside-down (evens point to the other apex). The d4 shows its up corner's numeral upright on all
  three visible faces. `DebugFaces` prints each face's world normal and numeral-up vector at rest.
- **Numeral size rule.** "As large as possible" (Chessex) is applied as one shared cap height per
  kind: the largest that fits every face 0.45 mm inside the fillet, times the size factor. At 92% the
  heights are d4 4.3, d6 12.5, d8 8.3, d10 7.8, d% 5.4, d12 7.2 and d20 4.2 mm. On a square face the
  width never binds, so the d6 numeral grows very tall.
- **Cost.** All seven models and atlases (640 px cells, RG8 mask plus RG16F slope) build in about
  0.9 s on one thread. C's dense meshes add about 0.1 s and are about 4.0M triangles. Depth and ink
  fill are uniforms, so they cost nothing to change. GPU time is in the HUD. **No integrated GPU was
  measured.**
- **A vs B vs C.** At the whole-set distance the three are hard to tell apart. At macro, A's recess
  catches the key light's highlight on the wall nearest the light, so it reads faintly embossed. B and
  C shadow that wall instead and look alike in the d20 and d6 macro shots. Grazing angles were not
  compared.
- **Prototype shortcuts:** openglfx embeds the canvas (v1 uses its own public-API bridge, ADR-0002).
  C's dense mesh stands in for real glyph geometry. The edge radius is uniform, so the d10 apices and
  d4 tips get no extra rounding. Wall slope (0.035 mm blur) and ink coverage (the deeper part of each
  wall) are fixed.

## Screenshots

A curated set is in [`screenshots/`](screenshots/). Run the capture tour for the full 51.
