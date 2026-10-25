# Dice Sets are data files for one shader, and translucent ones refract a copy of the opaque pass

A Dice Set is a small, versioned text file of material parameters, not code, and one uber-shader draws
every Dice Set from those parameters. A solid resin is simply a Dice Set whose swirl, flakes, pearl and
transmission are all zero. v1 ships the 14 candidate Dice Sets from the
[materials prototype](https://github.com/evildarkarchon/Andys-Dice/tree/prototype/materials/prototypes/materials):
solid, swirl, speckle, pearl, metal and translucent. Players can add their own Dice Sets as files.
Translucent and gemstone looks, the stretch goal, are in. The prototype showed that they fit in GL 3.3
with no extension, no extra scene pass and no blending, at about 0.1 ms over the opaque worst case on
the driving dev's GPU. This was decided by eye and by timing in the prototype for
[How are Dice Set materials defined, and which Dice Sets ship in v1?](https://github.com/evildarkarchon/Andys-Dice/issues/16).

## Decisions

- **Format:** a flat `java.util.Properties` file with the extension `.diceset`, in UTF-8, with a
  `format=1` key, in the spirit of ADR-0007. Colours are sRGB hex. The keys are `name`; `base`,
  `roughness` and `metallic`; `swirl.color`, `.scale`, `.warp`, `.bands`, `.amount` and `.softness`;
  `flakes.color`, `.density`, `.size` and `.metallic`; `pearl`; `transmission`, `transmission.color` (the
  colour of white light after 10 mm in the body), `ior` and `cloudiness`; and `ink`, `ink.metallic` and
  `ink.roughness`. As with `settings.properties`, an unknown key is ignored and an invalid value falls
  back to that key's default without discarding the rest. A Dice Set's identity is its file name
  without the extension. The chosen Dice Set in `settings.properties` stores that identity, and a
  missing one falls back to the first built-in Dice Set.
- **Built-in and player Dice Sets:** the built-in files ship inside the app. Player files go in
  `dicesets\` inside ADR-0004's data folder and are read once at start-up. They are listed after the
  built-ins, in file-name order. A player file that is unreadable, has a newer or missing `format`, or
  reuses a built-in Dice Set's name is skipped and named in a one-time notice that doesn't block. The
  app never writes or renames these files, because they are the player's. v1 has no in-app editor and
  no reload while running.
- **Patterns:** swirls and flakes are solid textures in model space, so they run on across edges as in
  cast resin. Each Die gets its own offset into them, so no two Dice in a set match. The swirl is a
  periodic, domain-warped noise field. It is baked on the GPU into a 128³ single-channel 3D texture per
  Dice Set when the Dice Sets load. The texture holds exactly one period, so it wraps seamlessly and an
  offset is all that per-Die variation needs. The shader never evaluates the noise itself. Flakes are
  one hash per cell and stay in the shader.
- **Ink** is opaque paint over any body, including a translucent one, in ADR-0008's recess. Each Dice
  Set gives the ink a colour, a metallic value and a roughness.
- **Translucency:** the refracted view ray's path through the Die is found analytically against the
  Die Kind's face planes. A Die is convex, so it leaves through the nearest plane ahead of it.
  Absorption along that path follows Beer-Lambert, from `transmission.color`. The ray is marched in 8
  steps through the body: the swirl becomes milky clouds that scatter light, and metallic flakes become
  glitter. Where it leaves, the far face's ink blocks the view, so the back Numerals show through,
  mirrored and tinted. Otherwise the exit ray refracts onto the tray, read from a resolve copy of the
  frame taken after the opaque Dice are drawn. Past the critical angle, it reflects the room instead.
  Translucent Dice are drawn last, with no blending and no sorting.

## Considered Options

- **No translucent Dice Sets in v1** (opaque fallback only). Rejected. The cost is small, and
  translucent dice are a staple of real D&D sets.
- **Thickness only**, with Beer-Lambert absorption and nothing real behind the Die. Rejected. With no
  tray showing through, a thick, dark Dice Set such as Ruby goes almost black, and looks worse than
  drawing it opaque.
- **Evaluating the swirl in the shader.** Rejected. It costs about 130 hashes per sample, and the
  translucent march takes 8 samples per pixel. A translucent swirl filling the frame took 1.81 ms
  against 0.32 ms baked (RTX 4070, 1280×800). It looked the same.
- **Swirls baked into 2D per-face textures.** Not tried. They would show seams at edges, and the march
  could not sample inside the body.
- **Dice Sets as Java code.** Rejected. Player Dice Sets need a file format anyway, and one loader for
  built-in and player files is one code path.
- **Built-in Dice Sets only, or an in-app editor.** Rejected for v1. Drop-in files cost almost nothing
  beyond the loader, and an editor would be a feature of its own.

## Consequences

- **The format is a player-facing contract.** New keys must come with defaults that leave existing
  files looking the same. A breaking change bumps `format` and migrates older files forward in code,
  as in ADR-0007.
- **Fragment cost.** Translucent Dice add the march and one full-frame resolve copy per frame in which
  any are visible, and a baked swirl costs 2 MB of texture per Dice Set that has one. GPU-time headroom
  on integrated graphics (still unmeasured) must include this alongside ADR-0008's parallax occlusion
  mapping.
- **Known v1 limits.** Translucent Dice cast full, uncoloured shadows and can't see each other through
  themselves. The thickness ignores the rounded edges. Lighter, tinted shadows belong to
  [What do the tray, surface, camera and lighting look like?](https://github.com/evildarkarchon/Andys-Dice/issues/17)
  if they're wanted.
- **The solid definition grows.** ADR-0003's one solid per Die Kind also supplies the face planes and
  the per-face atlas mapping (ADR-0008) that the translucency shader reads.
- **Testing (ADR-0005).** Pure-Java tests cover the loader: defaults, ignored keys, fallbacks, skipped
  player files and name clashes. A GL test on Mesa can check that a baked swirl volume matches the field
  it was baked from. The gallery shows every built-in Dice Set.
- **Sound.** Metal Dice Sets exist, so if v1's sound distinguishes materials, the Dice Set file gains
  a key for it, with resin as the default.
