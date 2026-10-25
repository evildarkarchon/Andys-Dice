# Numerals are parallax-occlusion engravings from one procedural atlas per Die Kind

Every Numeral is an engraving drawn by parallax occlusion mapping, not real recessed geometry. The
shader traces the view ray through an engraving profile, and a second march toward the key light lets
each recess shadow itself. The profile comes from one atlas per Die Kind, generated in code, with one
cell per face. Each cell holds that face's Numerals already rotated into the face's frame, so a face's
UVs are a plain projection. Ink fills only the bottom of each recess, as on a die that is dipped and
then tumbled clean. The rest of the layout follows the modern Chessex mould from the research. It was
chosen by eye in the [numerals prototype](https://github.com/evildarkarchon/Andys-Dice/tree/prototype/numerals/prototypes/numerals)
for [How are engraved, inked numerals made and laid out on every Die Kind?](https://github.com/evildarkarchon/Andys-Dice/issues/15).

## Decisions

- **Engraving:** 0.4 mm deep with a thin film of ink on the floor, so about 0.36 mm of recess shows.
  The walls slope across about 0.14 mm.
- **Font:** a procedural stroke font in Chessex style: a running-track 0, a hooked 7, a 1 with an
  upstroke, bold (0.16 cap heights) and condensed. It is built from centre-line strokes, with no font
  files. A 6 or 9 shown alone is underlined, on every Die Kind except the d4 and the percentile d10
  (so 16 and 19 are not marked).
- **Size:** all Numerals on one Die Kind share one height. The height is the smaller of 92% of the
  largest that fits every face 0.45 mm inside the fillet, and 0.80 of the flat face's
  inscribed-circle diameter. At a 0.75 mm edge radius that gives d4 4.3, d6 11.6, d8 7.9, d10 7.6,
  percentile d10 5.4, d12 7.2 and d20 4.2 mm.
- **Orientation:** on the d8 and d10, each Numeral's top points at its own half's apex. The percentile
  d10's two-digit Numerals run perpendicular to the equator, reading from apex to equator (the Chessex
  photo). The d4 is top-read: each face shows the labels of its three corners, each Numeral with its
  top toward its corner. On the d20, the 20's baseline faces the 8, and the 2, 8 and 14 have their
  baselines toward the 20 (photo). Every other d20 and d12 Numeral points its top at the edge it
  shares with its neighbour nearest the 20 or 12. On the d12 that puts an upright, underlined 9 below
  an upright 12, which is how the research reads the Chessex photo.
- **Edge radius:** 0.75 mm, uniform, shared with Jolt's convex radius (ADR-0003). It splits the
  research's suggestions of about 0.5 mm for edges and 0.8-1.0 mm for vertices, because one uniform
  radius has to serve both.

## Considered Options

- **Normal map plus one-step parallax** (the bake-off baseline). It is the cheapest, but up close
  nothing inside the recess is occluded. The env highlight lands on the near wall, so the Numerals
  read faintly embossed.
- **Real recessed geometry.** The prototype's stand-in, a dense displaced mesh of about 4M triangles
  for the set, looked no better than parallax occlusion mapping at macro distance. True glyph meshes
  would need outline extrusion and triangulation with holes for every Numeral, and would multiply the
  vertex load on integrated graphics.
- **"Baseline toward the neighbour nearest the top"** for the d12 and d20. This extends the d20
  photo's rule to every face, but it shows the 9 below the 12 upside-down, against the reading of the
  d12 photo.

## Consequences

- **Fragment cost.** Parallax occlusion mapping takes 12-48 profile samples per die pixel, plus 12 for
  the shadow march. GPU-time headroom on integrated graphics (still unmeasured) must include it. A
  silhouette doesn't show the recess, which is invisible at 16-20 mm.
- **Testable layout.** Atlas and face-frame generation is pure Java, so ADR-0005's mesh/atlas tests
  can check that every Numeral fits inside its face, that the orientation rules hold, and that 6 and 9
  marks appear exactly where they should.
- **Still assumed, not confirmed:** the percentile d10 layout (the d10 layout ×10), and every d12/d20
  orientation beyond the photographed faces. A physical Chessex set would confirm or correct both
  without changing this design, only the tables.
