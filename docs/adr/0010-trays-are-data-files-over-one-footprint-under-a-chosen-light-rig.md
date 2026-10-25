# Trays are data files over one fixed footprint, shown under a separately chosen Light Rig

The player chooses the Tray and the Light Rig separately, and neither is hard-wired. A Tray is a small,
versioned text file, like a Dice Set (ADR-0009): its outline shape, its walls and its floor surface. Every
Tray shares one inner footprint, 300 × 200 mm, with walls at least 30 mm high, so physics behaves the same
whichever one is chosen. Three Light Rigs ship: studio, lamp and window. All of them use PCSS shadows and an
analytic reflection environment. The camera stays on the whole Tray while the Dice tumble, then eases in on
them at rest. A rerolled Die glows briefly, then lifts out before its Reroll Throw. This was decided by eye,
by timing and by throw statistics in the
[tray prototype](https://github.com/evildarkarchon/Andys-Dice/tree/prototype/tray/prototypes/tray) for
[What do the tray, surface, camera and lighting look like?](https://github.com/evildarkarchon/Andys-Dice/issues/17).

## Decisions

- **Tray files:** a flat `java.util.Properties` file with the extension `.tray`, in UTF-8, with a
  `format=1` key, following ADR-0009 in every rule. An unknown key is ignored, and an invalid value falls
  back to its default. A Tray's identity is its file name. Built-in Trays ship inside the app, and player
  Trays go in `trays\` in ADR-0004's data folder and are read once at start-up. A bad or clashing player
  file is skipped, with a one-time notice that doesn't block. A Tray file describes only:
  - **the outline:** rectangle, hexagon or oval, inside the fixed footprint;
  - **the walls:** a square or padded (rounded-top) profile, a material (wood or leather) with a colour,
    and a height of at least 30 mm;
  - **the floor:** a surface kind (felt, leather or wood) with a colour.

  The exact key names are left to the build. The footprint and wall thickness are not keys: they are
  fixed.
- **Built-in Trays:** these come from the prototype's three looks, refitted to the footprint and 30 mm
  walls:
  - green felt in a walnut rectangle (**the default**);
  - brown leather in a padded hexagon;
  - bare walnut in a walnut rectangle.
- **Surfaces are procedural:** felt fibres, pebbled leather and walnut grain are generated in code as
  seamlessly tiling textures, with a height channel that gives a bump normal. There is no external art,
  in keeping with the Notes on the
  [map](https://github.com/evildarkarchon/Andys-Dice/issues/1). Each surface kind and wall material also
  sets its physics material: felt swallows bounces, wood clatters, and padded walls give back less than
  wooden ones.
- **Escape volume:** the visible walls. A Die that clears the wall top and leaves the outline is an
  Escaped Die, and the attempt is silently re-simulated (ADR-0003). There are no invisible walls above
  the visible ones, so nothing ever bounces off air.
- **Light Rigs:** studio (two softboxes, a neutral room), lamp (one warm spot in a dark room) and window
  (low cool daylight from the side, a blue sky fill). The rig is its own setting, independent of the
  Tray, and the default is studio. Rigs are code, not data files. The key light is the only shadow
  caster. It is directional for studio and window and a spot for the lamp. Each rig also defines its
  reflection environment analytically, with no cube maps: softboxes, the lamp's bright opening, a window.
- **Shadows:** one shadow map from the key light, filtered by PCSS (contact-hardening) under every rig.
  Each rig sets its own light size. ADR-0002's shadow-quality setting scales the map and the tap count.
  Each Die also darkens the floor beneath it with a cheap contact occlusion. Translucent Dice Sets keep
  full, uncoloured shadows, as ADR-0009 left them.
- **Camera (settle-in):** the camera frames the whole Tray from a fixed 3/4 view while Dice tumble, then
  eases in to frame the Dice at rest. For a Reroll Throw it eases back out to the whole Tray, then eases
  in again once everything is at rest. Whether and how the player can orbit or zoom belongs to
  [How should the v1 Roll UI look and behave?](https://github.com/evildarkarchon/Andys-Dice/issues/19).
- **Reroll pickup:** each Die about to be rerolled glows for about 0.6 s, so the player sees which Dice go
  back. It then lifts out along an arc to the release point (about 0.7 s), and the Reroll Throw's
  recording starts from there (ADR-0006).
- **Persistence:** the chosen Tray (its file name) and Light Rig are kept in `settings.properties`
  (ADR-0007). A missing Tray falls back to the default.

## Considered Options

- **One fixed tray.** Rejected: the player wanted a choice. Once there is a choice, one loader for built-in
  and player files is cheaper than a growing list of hard-coded trays.
- **Mix-and-match settings** (surface, shape and walls chosen separately). Rejected. Some combinations look
  wrong, and a Tray file already lets anyone make the combination they want.
- **A size and wall height per Tray** (allowing a 15 mm lip, or a small tray). Rejected. With the
  20-Dice cap, a 220 × 150 mm tray used all 12 attempts in 12 of 20 Throws. 15 mm walls let bouncy Throws
  escape in up to a quarter of attempts. A fixed footprint means physics is tuned once. 400 × 260 mm
  rested in fewer attempts (3.0 against 4.9) but made a single die look lost.
- **Invisible glass walls** (the escape volume taller than the visible walls). Rejected. Nothing escapes,
  but a die can visibly bounce off air above the wall.
- **A Light Rig fixed by each Tray.** Rejected: any Tray under any light is free once both are settings.
- **PCF, or a different technique per rig.** Rejected. Under the studio light PCSS looks like PCF for
  about 0.05 ms more. Under the window it gives the long, soft-ended shadows that sell the light. One code
  path is simpler than two.
- **Tinted translucent shadows** (a second depth-plus-colour shadow map). Rejected. Dark translucent Dice
  Sets absorb nearly all the light, so a tinted shadow barely differed from a full one, and it costs a
  second shadow pass.
- **Follow, fixed or top-down cameras; pop or plain lift for a reroll.** All were tried in the prototype,
  and settle-in and glow-then-lift were chosen by eye.

## Consequences

- **The Tray format is a player-facing contract**, versioned and migrated like ADR-0009's Dice Sets.
- **The 20-Dice cap rarely rests flat on the first attempt.** In the 300 × 200 mm footprint, 20 Dice took
  4.9 attempts per Throw on average, and 1 Throw in 20 used all 12 attempts and still had a Cocked Die. The
  Dice were mostly leaning on each other. Re-simulating is cheap (under about 0.1 s for 12 attempts), but
  running out of attempts is not acceptable. That is now its own question:
  [How does a 20-Dice Throw reach a flat rest?](https://github.com/evildarkarchon/Andys-Dice/issues/21).
  *Resolved by ADR-0013:* a one-layer, gentler release, die-on-die friction of 0.2 and settle nudges bring
  this to 1.3–1.7 attempts per Throw. The cap is 24 silent attempts, with a visible Cocked Rethrow as the
  fallback.
- **Every Tray must keep 20 Dice workable:** a hexagon or oval inside the footprint has less floor than
  the rectangle. The loader can't check that a player's Tray is playable beyond the fixed footprint and
  minimum height.
- **Cost.** The dearest scene in the prototype was 0.42 ms on an RTX 4070 at 1280×800 (window light,
  PCSS, a close-up). GPU-time headroom on integrated graphics (still unmeasured) must include PCSS
  alongside ADR-0008's parallax mapping and ADR-0009's translucency.
- **Reroll Throws take longer:** the pickup adds about 1.3 s before each Reroll Throw starts.
- **Surface detail is mostly sub-pixel** at the whole-Tray view. The floors read through colour, mottling
  and sheen, and their grain shows when the camera eases in.
- **Sound.** If v1's sound varies by surface, it follows the Tray's floor kind and wall material, which
  the file already names. *Amended by ADR-0012:* it does. There is one sample category per floor kind
  and per wall material, and the reroll pickup is silent.
- **Testing (ADR-0005).** Pure-Java tests cover the Tray loader as they do the Dice Set loader. The
  invariant tests on real Jolt run in the default Tray. The gallery shows every built-in Tray under every
  Light Rig.
