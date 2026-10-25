# PROTOTYPE: Dice Set materials and the v1 catalogue (throwaway)

> Lives only on the `prototype/materials` branch. Never merge it. It answers one question for
> [How are Dice Set materials defined, and which Dice Sets ship in v1?](https://github.com/evildarkarchon/Andys-Dice/issues/16)
> and is kept as a primary source. It is built on the `prototype/numerals` code, with ADR-0008's numerals
> as they were locked.

**Verdict:** pending. The human's reaction is recorded on the ticket.

**Question.** How is a Dice Set's material defined and fed to our GL 3.3 PBR shaders, and which Dice Sets
ship in v1? Can translucent and gemstone looks be reached in GL 3.3 within the integrated-GPU budget?

**What a Dice Set is here.** A versioned, flat `java.util.Properties` text file in [`dicesets/`](dicesets/),
in the spirit of ADR-0007 (UTF-8, a `format` key, no JSON library). Colours are sRGB hex. Every set uses the
same parameters, and one uber-shader (`die.frag`) draws every look:

| Key | Meaning |
|---|---|
| `base`, `roughness`, `metallic` | The body. For metal, `base` is the metal's reflectance colour. |
| `swirl.color`, `.scale`, `.warp`, `.bands`, `.amount`, `.softness` | A second colour from a domain-warped noise field in model space (a solid texture, so it runs across edges like cast resin). `bands > 0` folds it into ribbons (marbled); `0` gives soft clouds. |
| `flakes.color`, `.density`, `.size`, `.metallic` | Speckle (matte flakes) or glitter (metallic flakes at random angles that each catch the light). |
| `pearl` | A moving, colour-shifting sheen where the swirl is (lustrous/mica). |
| `transmission`, `transmission.color`, `ior`, `cloudiness` | Translucency. `transmission.color` is white light after 10 mm in the body. Inside a translucent body the swirl becomes milky clouds and the flakes become glitter. |
| `ink`, `ink.metallic`, `ink.roughness` | The numeral ink. Ink is opaque paint over any body, including a translucent one. |

Unknown keys and bad values become warnings in the HUD and fall back to defaults, and the rest of the file
still loads. `R` re-reads the folder, so you can edit a set in a text editor while the app runs.

**The switcher** flips how a *translucent* Dice Set is drawn (opaque sets look the same under all three):

| Key | Variant | What it is |
|---|---|---|
| A | Opaque only | Translucent sets fall back to a solid resin in their `base` colour. This is what v1 looks like if the stretch goal is ruled out. |
| B | Thickness | The refracted view ray's path length through the die, found analytically against the face planes (the die is convex), drives Beer-Lambert absorption and cloud scattering. A constant felt colour stands in for what is behind. |
| C | Refraction | As B, but the ray is marched 8 steps through the body (swirl clouds, glitter), the far face's numeral shows through (mirrored, tinted by the body), and the exit ray refracts onto the real tray from a copy of the opaque pass, or reflects the room on total internal reflection. |

## Run

From the repo root, with JDK 27 (Windows paths for `mvn.cmd`):

```
JAVA_HOME='C:\OpenJDK\jdk-27' mvn.cmd -q -f prototypes/materials/pom.xml package exec:exec
```

Options:

- `-Dmaterials.variant=A`: start on a variant (default C).
- `-Dmaterials.capture=target/capture`: an unattended tour. It saves 55 PNGs, each captioned with its GPU
  time averaged over 30 frames, then exits. Add `-Dmaterials.shots=01,06` to limit the tour to shot names
  that start with those prefixes.

Keys:

| Key | Does |
|---|---|
| `<-` / `->` | translucency technique A / B / C |
| `C` | catalogue: one d20 per Dice Set (the start view) |
| `0`, `1`-`7` | whole set; focus d4, d6, d8, d10, d%, d12, d20 (in the current Dice Set) |
| `M` / `N` | next / previous Dice Set |
| `R` | reload `dicesets/` |
| `Z` | close-up / macro (while focused) |
| `F` | next face up |
| `K` | swirl source: procedural in the shader / baked 128^3 3D texture |
| `V` | per-die variation: each Die gets its own offset into the swirl and flake fields |
| `I`, `X` | ink fill (film / half / brim), engraving depth |
| drag, wheel, `Space`, `T` | orbit and tilt, zoom, auto-orbit, slow tumble |

## What to judge

1. **The catalogue.** Which of the 14 candidates ship in v1, and how many? Which looks are missing?
2. **Translucency.** Flip A/B/C on Smoke, Ruby, Emerald and Nebula (`M` to the set, `7` for the d20). Is C
   worth shipping? Is B ever good enough, or is it C or nothing?
3. **Swirl.** Do the marbled (Vortex, Pearl) and cloudy (Jade) swirls read as resin? The parameters are the
   knobs, so edit the files and press `R`.
4. **Ink.** Gold or silver metallic ink against white or black paint (`I` for how full the recess is).
5. **Custom Dice Sets.** Should players be able to drop in their own `.diceset` files?

## Findings from the build (2026-10-24, RTX 4070, JavaFX 27.0.1, JDK 27, 1280x800, MSAA 4x)

Whole-frame GPU time (shadow map, tray and dice), averaged over 30 frames:

| Scene | A | B | C |
|---|---|---|---|
| Catalogue, 14 d20s, swirl in shader | 0.18 ms | 0.18 ms | 0.27 ms |
| Catalogue, swirl baked | | | 0.20 ms |
| Nebula d20 filling the frame (macro), swirl in shader | 0.30 ms | 0.31 ms | **1.81 ms** |
| Nebula d20 macro, swirl baked | 0.21 ms | 0.23 ms | **0.32 ms** |
| Ruby d20 close-up (no swirl) | 0.15 ms | 0.16 ms | 0.19 ms |

- **Translucency fits in GL 3.3.** C needs no extension, no second scene pass and no blending, only one
  full-frame copy (a resolve blit) and a longer fragment shader. With a baked swirl, its worst case (a
  translucent swirl filling the frame) costs about 0.1 ms over the opaque baseline on this GPU.
- **Bake the swirl.** Evaluating the warped noise per sample is what is expensive: it costs about 130 hashes
  per sample and C takes 8 samples per pixel, so the shader source is 5.7x slower than the baked one at
  macro. Baked looks the same (compare the two 06-cost-macro-nebula-C shots): both run the same periodic field (`noise.glsl`), and the texture holds
  exactly one period, so it wraps seamlessly and per-die variation is just an offset. A 128^3 R8 volume is
  2 MB per swirled Dice Set and bakes on the GPU in about 3 ms.
- **No integrated GPU was measured.** For scale: integrated graphics run very roughly 10-15x slower than an
  RTX 4070 in fill rate, so the baked worst case of 0.32 ms would land around 3-5 ms, inside a 16.7 ms frame.
  The shader-swirl C at 1.81 ms would be around 18-27 ms, which does not fit.
- **B looks worse than A on thick, dark sets.** With nothing real behind it, a thick Ruby d20 under B goes
  almost black. The light-coloured Smoke and Emerald fare better. B is not a useful middle ground.
- **C sells translucency through the far face's numerals.** Seeing the numerals on the back of a Ruby die
  through the body, mirrored and tinted red, is the cue that reads as "translucent die".
- **Swirl tuning.** Thresholding the warped noise directly gives camouflage-like blotches. Running it through
  a few sine bands (`swirl.bands` of about 2.5-3.5) gives the folded ribbons of marbled resin.

## Prototype shortcuts

- openglfx embeds the canvas (v1 uses its own public-API bridge, ADR-0002).
- Translucent dice cast full, uncoloured shadows, and they can't see each other (each one sees only the
  opaque pass behind it).
- The far-face lookup and thickness use the flat face planes, not the rounded edges.
- Volume glitter is point-sampled 8 times along the ray, so it shimmers a little as the camera moves.
- "Gemstone" here means translucent resin with a high IOR. Faceted, sharp-edged stone was not tried.
- The tray's look is unchanged from the bake-off; [What do the tray, surface, camera and lighting look like?](https://github.com/evildarkarchon/Andys-Dice/issues/17) owns it.

## Screenshots

A curated set is in [`screenshots/`](screenshots/). Run the capture tour for the full 55.
