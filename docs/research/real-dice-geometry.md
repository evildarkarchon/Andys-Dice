# How real D&D dice are shaped and numbered

Research for [issue #5](https://github.com/evildarkarchon/Andys-Dice/issues/5). The goal is enough
detail to generate each **Die Kind** (d4, d6, d8, d10, percentile d10, d12, d20) procedurally:
the solid, its real-world size, edge rounding, the numeral-to-face layout, numeral styling, and how
the resting **Face Value** is read.

Each claim carries a tag:

- **[V]**: verified against the cited source.
- **[D]**: derived by computation from verified facts. The scripts are described under
  [Method](#method-and-cross-checks), and every derived layout was checked against at least one
  independent source.
- **[I]**: inference or modelling suggestion. No primary source was found, so treat it as a default
  to tune by eye.

The reference layout is the **modern Chessex mould** (Opaque and Speckled lines made in Denmark,
plus the German acrylic lines). Chessex is the de facto reference D&D 7-die set, and its layout is
the best documented: it appears in a peer-reviewed paper, in Chessex's own catalogue photos, and in
two independent collector databases. There is **no industry-wide standard layout** for any die
beyond the opposite-faces rule (see [§3.1](#31-conventions-that-hold-across-manufacturers)).

---

## 1. Short answers

| Question | Answer |
|---|---|
| Solids | d4 regular tetrahedron, d6 cube, d8 regular octahedron, d12 regular dodecahedron, d20 regular icosahedron, d10 and percentile d10 a **pentagonal trapezohedron** (10 congruent kites) [V]. The trapezohedron has one free shape parameter. Use the "all vertices on a sphere" member, whose kite angles are 90°, 90°, 51.83° and 128.17° [V/D] (§2.2). |
| Typical sizes | d6 16 mm [V]; d20 about 19–20 mm face to face [V]; d10 about 16 mm face to face [V] and about 21–22 mm tip to tip (measured) [V, hobbyist]; d8 about 15–16 mm, d12 about 18–19 mm face to face; d4 about 18 mm tip to base (measured) [V, hobbyist] (§2.3). |
| Rounding | Mass-market dice are tumbled after moulding, which rounds every edge and corner unevenly [V]. Chessex says its polyhedrals now have "sharper edges" [V]. Gamescience "precision" dice are left untumbled and razor-edged [V]. No manufacturer publishes a radius. Suggested model: about 0.5–1.0 mm fillets [I] (§2.4). |
| Opposite faces | d6 sums to 7, d8 to 9, d12 to 13, d20 to 21, and a 0–9 d10 to 9 (0 opposite 9) [V]. The d4 has no opposite faces. |
| Balanced vs spindown d20 | A "balanced" (standard) d20 has opposite faces summing to 21. Its vertex sums are still uneven (Chessex: 41 to 64, ideal 52.5) [V]. A spindown puts consecutive numbers on adjacent faces. It is a life counter, not a fair-looking die [V] (§3.7). |
| d4 styles | **Top-read** (corner): each face shows three numbers near its corners, and the result is the number at the top vertex. **Bottom-read** (base-line): the numbers run along the edges, and the result is the number on the bottom edges [V]. Chessex is top-read [V]. Nearly all d4s are right-handed [V] (§3.2). |
| Percentile d10 | Same solid. Faces read 00, 10, … 90. Chessex prints its two-digit numerals **perpendicular to the equator**, i.e. along the kite's long axis [V]. Its layout appears to be the d10 layout ×10 [I, consistent with a photo] (§3.5). |
| d6 pips vs numerals | The D&D 7-die set uses a **numbered** d6 [V]. Pipped d6s are sold separately and have more rounded corners [V]. Western dice are right-handed: 1-2-3 run counter-clockwise around their shared corner [V] (§3.3). |
| 6/9 marks | Chessex **underlines** 6 and 9 (it dropped the older dots) [V]. Koplow and Dice & Games use a dot [V]. On a Chessex d20 only 6 and 9 are marked, not 16 or 19 [V]. |
| Numeral styling | Numerals are moulded recesses filled with paint: the die is dipped, then tumbled clean [V]. Chessex makes the numerals "as large as possible on each face" [V] in its own sans-serif font: underlined 6/9, hooked 7, 1 with an upstroke, "running-track" 0 [V]. Engraving depth is not published; about 0.3–0.5 mm is a reasonable guess [I] (§4). |
| Reading the result | Every kind except the d4 comes to rest on a face, and its opposite face points straight up. The Face Value is the number on that up-facing face [V/D]. A top-read d4 shows the number at its upward vertex [V] (§5). |

---

## 2. Geometry

### 2.1 Platonic solids (d4, d6, d8, d12, d20) [D]

The numbers below were computed from the canonical coordinates. They are standard values. "Face to
face" is twice the inradius and is the size quoted for dice. The d4 is quoted tip to base instead
(edge × √(2/3) = 0.816497 × edge).

| Die | Solid | V / F / E | Face | Dihedral angle | Inradius / edge | Circumradius / edge | Face to face / edge |
|---|---|---|---|---|---|---|---|
| d4 | regular tetrahedron | 4 / 4 / 6 | equilateral triangle | 70.5288° | 0.204124 | 0.612372 | n/a (tip to base 0.816497) |
| d6 | cube | 8 / 6 / 12 | square | 90° | 0.5 | 0.866025 | 1.0 |
| d8 | regular octahedron | 6 / 8 / 12 | equilateral triangle | 109.4712° | 0.408248 | 0.707107 | 0.816497 |
| d12 | regular dodecahedron | 20 / 12 / 30 | regular pentagon | 116.5651° | 1.113516 | 1.401259 | 2.227033 |
| d20 | regular icosahedron | 12 / 20 / 30 | equilateral triangle | 138.1897° | 0.755761 | 0.951057 | 1.511523 |

Canonical vertex sets, used by the layout tables in §3 (φ = (1+√5)/2):

- d4: (1,1,1), (1,−1,−1), (−1,1,−1), (−1,−1,1). Edge 2√2.
- d6: (±1, ±1, ±1). Edge 2.
- d8: (±1,0,0), (0,±1,0), (0,0,±1). Edge √2.
- d12: (±1,±1,±1) plus the cyclic permutations of (0, ±1/φ, ±φ). Edge 2/φ.
- d20: the cyclic permutations of (0, ±1, ±φ). Edge 2.

A frame-independent construction: every die here is centrally symmetric (except the d4) and
face-transitive. So each die equals the intersection of half-spaces **n·x ≤ r_in**, one per face
normal n, with r_in the inradius. The tables in §3 give the outward normal for every numbered face.
That is enough to build the die and its numbering together.

### 2.2 d10 and percentile d10: pentagonal trapezohedron

**Verified facts.**

- The d10 is a pentagonal trapezohedron: 10 congruent kites, 20 edges, 12 vertices. It has two
  "sharp" apices where 5 kites meet and ten "blunt" equatorial vertices where 3 kites meet. It is
  face-transitive with symmetry D5d, and its dual is the pentagonal antiprism [V]
  ([Wikipedia](https://en.wikipedia.org/wiki/Pentagonal_trapezohedron),
  [MathWorld](https://mathworld.wolfram.com/PentagonalTrapezohedron.html)).
- The "canonical" trapezohedron, the dual of the uniform antiprism, has kite angles 108°, 108°, 108°
  and 36° [V] ([McCooey](https://dmccooey.com/polyhedra/PentagonalTrapezohedron.html)).
- The **gaming-die version** adds the constraint that all 12 vertices lie on a sphere. As a
  consequence, each kite has two 90° interior angles [V]
  ([Wikipedia](https://en.wikipedia.org/wiki/Pentagonal_trapezohedron)).

**Construction [D].** Put the apices at N = (0, 0, +h) and S = (0, 0, −h), an upper zig-zag ring
Uₖ = (r cos 72k°, r sin 72k°, +c), and a lower ring Lₖ = (r cos(36+72k)°, r sin(36+72k)°, −c),
for k = 0…4.

- Upper kites: Tₖ = (N, Uₖ, Lₖ, Uₖ₊₁).
- Lower kites: Bₖ = (S, Lₖ, Uₖ, Lₖ₋₁).
- Tₖ is opposite (antipodal to) Bₖ₊₃, and Tₖ borders Bₖ and Bₖ₊₁.

Each kite is planar only when **c = h·(1 − cos 36°)/(1 + cos 36°) = 0.105573·h**. This does not
depend on r, so the ring radius r/h is the single free shape parameter.

| Variant | r/h | Kite angles (apex, two sides, equator) | Tip to tip ÷ face to face |
|---|---|---|---|
| Canonical (dual of the uniform antiprism) | 0.552786 | 36°, 108°, 108°, 108° | 2.236 (very pointy) |
| **All vertices on a sphere (gaming die)** | **0.994412** | **51.83°, 90°, 90°, 128.17°** | **1.4953** |

This computation reproduces the 90° angles Wikipedia states for the sphere variant.

**Which variant matches real dice?** Scale the sphere variant to the usual 16 mm face to face
([The Dice Lab](https://mathartfun.com/thedicelab.com/DiceDesign.html): "16 mm for typical d10s")
and it gives **23.9 mm** sharp tip to tip. Chessex d10s measure 21–22 mm tip to tip
([Printables measurements](https://www.printables.com/model/658134-polyhedral-dice-storage-parametric-gridfinity)),
and that author notes the rounded tips make dice "measure too small". A sharp 23.9 mm minus about
1 mm of tip rounding at each end fits the measured 21–22 mm. The canonical variant would be about
36 mm tip to tip and cannot match.

**Recommendation [I]:** use the sphere variant (r = 0.994412 h, c = 0.105573 h). For 16 mm face to
face, h = 11.96 mm. Round the two apices more heavily than the other vertices.

Not every maker uses a pure trapezohedron:

- Gamescience's d10 is truncated at the equator, with small triangular faces there [V]
  ([Dice Collecting Wiki](https://dice.miraheze.org/wiki/Gamescience_Precision_Dice_Identification)).
- The Armory's d10 has truncated apices [V] (same source).
- Alea Kybos notes Chessex d10s have "all vertices normal" [V]
  ([Alea Kybos](https://aleakybos.ch/Configurations.htm)).

### 2.3 Real-world sizes

| Die | Figure | Source and trust |
|---|---|---|
| d6 | 16 mm edge | [V] Chessex: "standard sized 16mm dice" ([16mm Dice Block](https://www.chessex.com/16mm-dice-block)). Chessex's own Amazon listing calls the 7-die set "standard size 16mm dice". |
| d20 | 19–20 mm face to face | [V] The Dice Lab (a manufacturer): "most d20s … are typically 19-20 mm" ([page](https://www.mathartfun.com/thedicelab.com/BalancedStdPoly.html)). One Gamescience d20 measured about 19.0 mm ([geneome](https://geneome.com/2013/02/18/gamescience-dice-shape/)). |
| d10 | 16 mm face to face; about 21–22 mm tip to tip | [V] The Dice Lab gives 16 mm face to face. [V, hobbyist] Printables measured Chessex at 21 and 22 mm tip to tip. |
| d4 | about 18 mm tip to base | [V, hobbyist] Printables (Chessex 18 mm). This implies about a 22 mm sharp edge [D]. |
| d8 | about 15–16 mm face to face | [V, hobbyist] Printables (Chessex 15 mm). |
| d12 | about 18–19 mm face to face | [V, hobbyist] Printables (Chessex 19 mm). |

Retailer surveys give much wider ranges for resin and metal dice. Dice Dragons measured d20s at
23–29 mm, for example ([Dice Dragons](https://www.dicedragons.co.uk/blogs/dice-advice/dice-size-comparison)).
The figures above describe the classic acrylic 7-die set.

**Suggested model sizes [D/I]** (sharp-solid edge lengths, before rounding):

| Die | Edge length |
|---|---|
| d4 | 22.0 mm |
| d6 | 16.0 mm |
| d8 | 19.0 mm (15.5 mm face to face) |
| d10 | h = 11.96 mm (16 mm face to face) |
| d12 | 8.5 mm (19 mm face to face) |
| d20 | 13.2 mm (20 mm face to face) |

### 2.4 Edge and corner rounding

- Mass-market (non-precision) dice are injection-moulded. They are then tumbled to remove the sprue
  nub, dipped in paint, and tumbled again to strip paint from the faces. "This process also produces
  the smoother, rounded edges" [V] ([Wikipedia, Dice → Manufacturing](https://en.wikipedia.org/wiki/Dice);
  [GMDICE](https://www.gmdice.com/blogs/dnd/how-are-dice-made) says tumbling takes 72–96 h).
- Lou Zocchi (Gamescience, a competitor) claims tumbling leaves "every one of these edges [with] a
  unique radius". That is, the rounding is uneven [V as his claim; treat as biased]
  ([GenCon 2015 talk](https://www.youtube.com/watch?v=MRzg_M8pQms)).
- Chessex's "Chessex Standard" says "all polyhedral dice have sharper edges for easier shape
  recognition". It also says "rounded corners on our pipped dice enable the dice to tumble well" [V]
  ([Chessex Catalog 6, 2019, p. 5](https://www.chessex.com/images/2019%20CAT06.pdf)).
- Gamescience precision dice "have not been tumbled or sanded" and are "razor-edged (with sharp
  points!)". Each has a visible sprue nub [V]
  ([Noble Knight / Gamescience](https://www.nobleknight.com/Products/Dice---Precision-Edge-High-Impact-Plain-Lou-Zocchi)).
- Casino (precision) d6 edges are left sharp, or evenly bevelled [V] (Wikipedia, Dice).

**Modelling suggestion [I]:**

- Chessex-style dice: edge fillets of about 0.5 mm and vertex rounding of about 0.8–1.0 mm at the
  16–20 mm scale. The d10 apices and the d4 tips need more, to match the measured tip-to-tip
  shortfall in §2.2.
- Pipped d6: about 1.5–2 mm corner radius.
- A Gamescience look: fillets of about 0.1 mm.
- A tiny per-edge random variation in radius would mimic tumbling.

---

## 3. Numbering

### 3.1 Conventions that hold across manufacturers

- **Opposite faces sum to N+1** on the d6 (7), d8 (9), d12 (13) and d20 (21). A 0–9 d10 sums to
  N−1 = 9 (0 opposite 9) [V]
  ([Wikipedia, Dice table](https://en.wikipedia.org/wiki/Dice);
  [Bosch et al.](http://dicecollector.com/docs/diceinfo_2016_NUMERICALLY_BALANCED_DICE_JANUARY.pdf);
  [Alea Kybos](https://aleakybos.ch/Configurations.htm)).
- Bosch, Fathauer and Segerman explain why: if a die is squashed along one axis, the two
  now-favoured opposite faces still average N+1/2, so the expected roll is unchanged [V].
- Beyond that rule, **arrangements differ by mould**. The collector databases list dozens of d20
  layouts, and The Dice Lab's "standard d20" vertex sums differ from the Chessex sums below. Any one
  manufacturer's layout is "realistic"; there is no single correct one
  ([Kevin Cook, d20 layouts](http://www.dicecollector.com/d20face.html);
  [boardgames.SE summary](https://boardgames.stackexchange.com/questions/16676/is-there-a-standard-way-to-number-the-faces-of-a-20-sided-die)).
- The modern Chessex mould IDs are cross-checked in two collector databases [V]. Kevin Cook lists
  "Chessex England, Chessex Denmark, Chessex Germany" under **d4_3, d6_2, d8_2, d10_1, d12_3** and
  **d20_4** ([d4](http://www.dicecollector.com/d4face.html), [d6](http://www.dicecollector.com/d6face.html),
  [d8](http://www.dicecollector.com/d8face.html), [d10](http://www.dicecollector.com/d10face.html),
  [d12](http://www.dicecollector.com/d12face.html), [d20](http://www.dicecollector.com/d20face.html)).
  Alea Kybos independently labels the same d8, d10 and d20 layouts "Chessex".

**How the tables below read.** Each table gives every face's number, its outward unit normal in the
stated frame, and its neighbours in counter-clockwise order **as seen from outside the die**. That
fixes chirality: a mirror-image die would list its neighbours clockwise.

### 3.2 d4

- **Two styles [V].**
  - *Corner / top-read*: each face shows three numbers, one near each corner, and the result is the
    number at the top vertex.
  - *Base-line / bottom-read*: the numbers run along the edges, and the result is the number at the
    bottom edges.

  Both styles show the result on all three visible faces
  ([Alea Kybos](https://aleakybos.ch/Configurations.htm); [Wikipedia, Dice](https://en.wikipedia.org/wiki/Dice)).
- **Chessex is top-read.** "All of our dice read from the top", shown on a d4 [V]
  ([Chessex Catalog 6 p. 5](https://www.chessex.com/images/2019%20CAT06.pdf)). Koplow and Dice &
  Games d4s are usually bottom-read [V]
  ([Critical Fail, Dice ID 101](https://www.virtual-dreams.org/dice/dice-identification-101/)).
- **Handedness.** Nearly all commercial d4s are *right-handed*: seen from outside, 1-2-3 and 1-3-4
  run clockwise, and 1-2-4 and 2-3-4 run counter-clockwise. Alea Kybos found only two left-handed
  examples [V] ([Alea Kybos](https://aleakybos.ch/Configurations.htm)). Kevin Cook's d4_3 net
  (Chessex) shows 1 at the top, 2 lower right, 3 lower left: clockwise [V].
- **Corner-style layout [D]:** label the vertices 1:(1,1,1), 2:(−1,1,−1), 3:(1,−1,−1), 4:(−1,−1,1).
  This labelling is right-handed by the definition above (checked numerically). Each face shows the
  labels of its three corners. Each numeral sits near its corner with its top pointing at that
  corner, as in the Chessex catalogue photo [V]. The result is the label of the vertex pointing up.
  The face on the table is the one *without* that label.

### 3.3 d6

- The 7-die D&D set uses a **numbered** d6. The Chessex 7-die set is "4-sided (d4), 6-sided
  w/numbers (d6), …" [V] ([Chessex Catalog 6 p. 5](https://www.chessex.com/images/2019%20CAT06.pdf)).
  Pipped d6s (12 mm and 16 mm) are separate products with rounded corners [V] (same page).
- **Handedness.** With opposite sides summing to 7, the faces 1, 2 and 3 share a corner. If they run
  counter-clockwise the die is **right-handed**. "Western dice are normally right-handed, and Chinese
  dice are normally left-handed" [V] ([Wikipedia, Dice](https://en.wikipedia.org/wiki/Dice)). The
  Chessex numbered d6 (Kevin Cook d6_2) is right-handed: with the 6 on top and 2 and 3 both visible,
  the 2 is on the right [V/D] ([Alea Kybos D6 section](https://aleakybos.ch/Configurations.htm)).
- **Layout [D]** (cube frame): 6 = +z, 1 = −z, 3 = +y, 4 = −y, 2 = −x, 5 = +x. The triple product
  n₁·(n₂×n₃) = +1, so 1→2→3 runs counter-clockwise from outside, i.e. right-handed. This matches
  Kevin Cook's d6_2 net: 6 in the centre, 3 above, 2 left, 5 right, 4 below.
- **Pips [V]** ([Alea Kybos](https://aleakybos.ch/Configurations.htm)): the patterns for 1, 4 and 5
  look the same at every 90° rotation. The patterns for 2, 3 and 6 each have two possible
  orientations, which gives 8 orientations per handedness. Western pips use the familiar 3×3 grid.
  Asian dice put the pips closer to the centre and colour the 1 and 4 red [V] (Wikipedia, Dice).

### 3.4 d8

- Opposite faces sum to 9 [V]. Chessex layout **8U6_U4U2U1U3U5U7U** (Alea notation; Kevin Cook
  d8_2) [V]. This means: hold the die with a vertex up and the 8 in the upper half. The upper four
  faces read **8, 6, 4, 2** counter-clockwise seen from above. The lower faces are 1, 3, 5, 7, each
  opposite 8, 6, 4, 2 in turn. All evens are on one half.
- Kevin Cook's d8_2 image and the Chessex catalogue photo both show the 8 bordered by **2 (left),
  6 (right) and 5 (below)**. The derived table reproduces this, including chirality [V/D].
- Numeral orientation [V]: each numeral is upright relative to the equator ("baseline of the upper
  or the lower pyramid"). Its top points to its own half's apex vertex
  ([Alea Kybos](https://aleakybos.ch/Configurations.htm)).

| Face | Outward unit normal | Neighbours (CCW from outside) |
|---:|---|---|
| 1 | (−0.5774, −0.5774, −0.5774) | 3, 4, 7 |
| 2 | (+0.5774, −0.5774, +0.5774) | 3, 8, 4 |
| 3 | (+0.5774, −0.5774, −0.5774) | 1, 5, 2 |
| 4 | (−0.5774, −0.5774, +0.5774) | 1, 2, 6 |
| 5 | (+0.5774, +0.5774, −0.5774) | 3, 7, 8 |
| 6 | (−0.5774, +0.5774, +0.5774) | 4, 8, 7 |
| 7 | (−0.5774, +0.5774, −0.5774) | 1, 6, 5 |
| 8 | (+0.5774, +0.5774, +0.5774) | 2, 5, 6 |

Frame: the octahedron vertices (±1,0,0), (0,±1,0), (0,0,±1).

### 3.5 d10 and percentile d10

**d10 (0–9) [V]:**

- Opposite faces sum to 9.
- All odd numbers meet at one apex and all evens at the other
  ([Alea Kybos](https://aleakybos.ch/Configurations.htm);
  [The Dice Lab](https://mathartfun.com/thedicelab.com/DiceDesign.html): the polar sums on "typical
  d10s" are 20 and 25).
- Chessex layout **9_U1U7U3U5U0U8U2U6_U4U** (Kevin Cook d10_1). Kevin Cook also lists d10_1 for
  Koplow, Crystal Caste, Gamescience Classic and others, so it is very nearly universal. In words:
  around the odd apex, counter-clockwise seen from above, the faces read **9, 1, 7, 3, 5**. Around
  the even apex they read **0, 8, 2, 6, 4**, with 0 opposite 9, 8 opposite 1, and so on.
- The **equatorial zig-zag** reads **0, 7, 4, 1, 6, 9, 2, 5, 8, 3** (and back to 0) with the evens
  on top ([fact-index mirror of the old Wikipedia "10-sided dice" article](http://www.fact-index.com/1/10/10_sided_dice.html)).

Four independent checks agree on this one layout, including chirality [D]:

- the Alea order string;
- the zig-zag sequence above;
- The Dice Lab's published Chessex 3-face vertex sums {10, 11, 11, 11, 12, 15, 16, 16, 16, 17}.
  Brute force over all odd/even layouts with opposite faces summing to 9 leaves only this layout and
  its mirror image;
- Kevin Cook's d10_1 net and the Chessex catalogue photo (0 bordered by 8 on the left and 4 on the
  right, apex up).

Numeral orientation [V]: every numeral is upright relative to the equator, so its top points to its
own apex. With the evens on top, the odd numbers therefore look upside-down (Alea; fact-index).
Chessex underlines 6 and 9 [V].

| Face | Outward unit normal | Neighbours (CCW from outside) |
|---:|---|---|
| 0 | (−0.6015, +0.4370, −0.6687) | 3, 7, 4, 8 |
| 1 | (+0.6015, +0.4370, +0.6687) | 4, 7, 9, 6 |
| 2 | (+0.2298, −0.7071, −0.6687) | 5, 8, 6, 9 |
| 3 | (−0.7435, +0.0000, +0.6687) | 0, 8, 5, 7 |
| 4 | (+0.2298, +0.7071, −0.6687) | 0, 7, 1, 6 |
| 5 | (−0.2298, −0.7071, +0.6687) | 2, 9, 3, 8 |
| 6 | (+0.7435, +0.0000, −0.6687) | 1, 9, 2, 4 |
| 7 | (−0.2298, +0.7071, +0.6687) | 0, 3, 1, 4 |
| 8 | (−0.6015, −0.4370, −0.6687) | 0, 2, 5, 3 |
| 9 | (+0.6015, −0.4370, +0.6687) | 1, 5, 2, 6 |

Frame: the sphere-variant trapezohedron of §2.2 with h = 1 (odd apex at +z), r = 0.994412,
c = 0.105573. The faces map to kites as T₀…T₄ = 1, 7, 3, 5, 9 and B₀…B₄ = 6, 4, 0, 8, 2. The inradius
is 0.66876 h. In each neighbour list, the two numbers in the same half share the kite's long edges
and the other two share its short edges.

**Percentile d10 (00–90):**

- Chessex sells it as the "Tens 10™ (10-sided die numbered 00-90)" [V]
  ([Chessex Catalog 6](https://www.chessex.com/images/2019%20CAT06.pdf)).
- Chessex prints the two-digit numerals **perpendicular to the equator**, reading along the kite's
  long axis from apex to equator. Dice & Games and the old Taiwan mould print smaller numerals
  *parallel* to the equator instead [V]
  ([Critical Fail](https://www.virtual-dreams.org/dice/dice-identification-101/); the Chessex
  catalogue photo shows "90" bordered by "50" and "10", rotated this way).
- Layout [I]: assume the d10 layout ×10, i.e. 90, 10, 70, 30, 50 around one apex and 00, 80, 20, 60,
  40 around the other. The catalogue photo is consistent with this: 90 sits next to 50 and 10, just
  as 9 sits next to 5 and 1. No layout listing for percentile dice was found.
- A **Percentile Roll** combines the tens die and the units die, with 00 + 0 read as 100 (see
  GLOSSARY.md). This matches the common convention [V]
  ([Wikipedia](https://en.wikipedia.org/wiki/Pentagonal_trapezohedron)).

### 3.6 d12

- Opposite faces sum to 13 [V]. Chessex Denmark/England/Germany use layout **d12_3**, Alea string
  **12U3U11U9_U7U8U1U10U2U4U6_U5U** [V]
  ([Kevin Cook d12](http://www.dicecollector.com/d12face.html);
  [Alea Kybos](https://aleakybos.ch/Configurations.htm)). With the 12 on top, its five neighbours
  read **3, 11, 9, 7, 8** counter-clockwise from above. The opposite 1 is surrounded by
  10, 2, 4, 6, 5, the opposites of those five.
- The Chessex catalogue photo shows the 12 upright, the 11 to its left and an underlined 6/9 glyph
  below it (a 9, by this layout). This is consistent with the order above, but the photo is too
  small to confirm the remaining neighbours [V/I].
- Numeral orientation [V]: by default each numeral's top points to a corner of its pentagon (Alea's
  default; it adds an "E" code when a numeral points to an edge instead).
- An older German mould (d12_7) also carried the Chessex name. The table below uses d12_3, the
  Denmark/Opaque mould.

| Face | Outward unit normal | Neighbours (CCW from outside) |
|---:|---|---|
| 1 | (+0.0000, +0.0000, −1.0000) | 2, 10, 5, 6, 4 |
| 2 | (+0.4702, −0.7608, −0.4472) | 1, 4, 8, 7, 10 |
| 3 | (+0.5783, +0.6823, +0.4472) | 4, 6, 11, 12, 8 |
| 4 | (+0.8689, +0.2121, −0.4472) | 1, 6, 3, 8, 2 |
| 5 | (−0.8276, +0.3391, −0.4472) | 1, 10, 9, 11, 6 |
| 6 | (+0.0668, +0.8919, −0.4472) | 1, 5, 11, 3, 4 |
| 7 | (−0.0668, −0.8919, +0.4472) | 2, 8, 12, 9, 10 |
| 8 | (+0.8276, −0.3391, +0.4472) | 2, 4, 3, 12, 7 |
| 9 | (−0.8689, −0.2121, +0.4472) | 5, 10, 7, 12, 11 |
| 10 | (−0.5783, −0.6823, −0.4472) | 1, 2, 7, 9, 5 |
| 11 | (−0.4702, +0.7608, +0.4472) | 3, 6, 5, 9, 12 |
| 12 | (+0.0000, +0.0000, +1.0000) | 3, 11, 9, 7, 8 |

Frame: a regular dodecahedron rotated so that the 12 face's normal is +z. The rotation about z is
arbitrary. Build the solid as the intersection of half-spaces n·x ≤ r_in.

### 3.7 d20

- Opposite faces sum to 21 [V]. The Chessex layout is published as a **net** in Bosch, Fathauer and
  Segerman, "Numerically Balanced Dice" (2016 preprint; Princeton University Press, 2017), Fig. 3.
  They call it "a common numbering … typical of that used by other manufacturers" [V]
  ([PDF](http://dicecollector.com/docs/diceinfo_2016_NUMERICALLY_BALANCED_DICE_JANUARY.pdf),
  [DOI](https://doi.org/10.23943/princeton/9780691171920.003.0014)).
- The same layout is **d20_4**, "Chessex England, Chessex Denmark, Chessex Germany" in Kevin Cook's
  list ([link](http://www.dicecollector.com/d20face.html)). Its Alea string is
  **20U2U8U14U18U12U10U16U6_U4U1U19U13U7U3U9_U11U5U15U17U**, labelled "Chessex"
  ([Alea Kybos](https://aleakybos.ch/Configurations.htm)).
- With the 20 upright, the **8 sits directly below the 20**, with the 2 upper-left and the 14
  upper-right. This is in the Chessex catalogue photo, and "the d20 has an 8 below the 20" is a
  Chessex identification mark [V]
  ([Critical Fail](https://www.virtual-dreams.org/dice/dice-identification-101/)).
- **Cross-check [D].** The transcribed net reproduces all 12 vertex sums and all 20 face sums that
  Bosch et al. print. Both Chessex Alea strings decode to the same layout with the same chirality
  as the Bosch net. The Bosch photo (19 bordered by 1, 3 and 9; 2 bordered by 12, 18 and 20)
  confirms the chirality.
- Vertex sums (five faces each) are 41, 44, 47, 51, 52, 52, 53, 53, 54, 58, 61, 64, against an ideal
  of 52.5. So the "balanced" standard d20 is balanced only in its opposite pairs [V]. The Dice Lab
  sells a "magic" d20 whose vertex sums are all 52 or 53 [V]
  ([The Dice Lab](https://www.mathartfun.com/thedicelab.com/BalancedStdPoly.html)).
- **Spindown [V].** A spindown life counter labels the faces "such that the face labelled i is
  adjacent to the face labelled i − 1"
  ([Constantinides & Constantinides, *Spindown Polyhedra*, Math. Gazette 2018](https://doi.org/10.1017/mag.2018.111)).
  It is "different from the conventional numbering of opposite sides adding to the same value". MTG
  counters are usually 22 mm ([MTG Wiki](https://mtg.wiki/page/Life_counter)). Wizards bans them
  for competitive rolling because their numbers are not distributed evenly
  ([MTR release notes 2021](https://magic.wizards.com/en/news/announcements/july-magic-tournament-rules-release-notes-2021-07-19)).
  Andy's Dice should use the balanced (Chessex) layout.
- **Numeral orientation.** In the Chessex photo, the 20's baseline is parallel to the edge it shares
  with the 8, and its top points to the opposite vertex. The 8, 2 and 14 have their baselines toward
  the 20, so each points to its own far vertex [V, from the photo]. Inference [I]: every Chessex d20
  numeral has its baseline parallel to one edge and its top toward the opposite vertex. Alea marks
  all d20_4 numerals as "default orientation". The exact choice of vertex per face is encoded in
  those orientation letters but was not decoded to 3D here.

| Face | Outward unit normal | Neighbours (CCW from outside) |
|---:|---|---|
| 1 | (+0.5774, +0.5774, +0.5774) | 7, 13, 19 |
| 2 | (+0.0000, −0.9342, −0.3568) | 12, 20, 18 |
| 3 | (−0.5774, +0.5774, +0.5774) | 16, 17, 19 |
| 4 | (+0.3568, +0.0000, −0.9342) | 11, 18, 14 |
| 5 | (+0.9342, −0.3568, +0.0000) | 13, 15, 18 |
| 6 | (−0.5774, +0.5774, −0.5774) | 9, 14, 16 |
| 7 | (+0.3568, +0.0000, +0.9342) | 1, 17, 15 |
| 8 | (−0.9342, −0.3568, +0.0000) | 10, 16, 20 |
| 9 | (+0.0000, +0.9342, −0.3568) | 6, 19, 11 |
| 10 | (−0.5774, −0.5774, +0.5774) | 8, 12, 17 |
| 11 | (+0.5774, +0.5774, −0.5774) | 4, 9, 13 |
| 12 | (+0.0000, −0.9342, +0.3568) | 2, 15, 10 |
| 13 | (+0.9342, +0.3568, +0.0000) | 1, 5, 11 |
| 14 | (−0.3568, +0.0000, −0.9342) | 4, 20, 6 |
| 15 | (+0.5774, −0.5774, +0.5774) | 5, 7, 12 |
| 16 | (−0.9342, +0.3568, +0.0000) | 3, 6, 8 |
| 17 | (−0.3568, +0.0000, +0.9342) | 3, 10, 7 |
| 18 | (+0.5774, −0.5774, −0.5774) | 2, 4, 5 |
| 19 | (+0.0000, +0.9342, +0.3568) | 1, 9, 3 |
| 20 | (−0.5774, −0.5774, −0.5774) | 2, 8, 14 |

Frame: the icosahedron with vertices at the cyclic permutations of (0, ±1, ±φ). The five faces
around each vertex (CCW from outside, sum) are:

| Faces around the vertex | Sum |
|---|---:|
| 7, 17, 10, 12, 15 | 61 |
| 2, 18, 5, 15, 12 | 52 |
| 1, 7, 15, 5, 13 | 41 |
| 1, 19, 3, 17, 7 | 47 |
| 3, 16, 8, 10, 17 | 54 |
| 2, 12, 10, 8, 20 | 52 |
| 4, 11, 13, 5, 18 | 51 |
| 1, 13, 11, 9, 19 | 53 |
| 3, 19, 9, 6, 16 | 53 |
| 6, 14, 20, 8, 16 | 64 |
| 2, 20, 14, 4, 18 | 58 |
| 4, 14, 6, 9, 11 | 44 |

---

## 4. Numeral styling

- **How numerals are made [V].** On non-precision dice "the pips or numbers on the die are a part of
  the mould". The die is submerged in paint and then tumbled, so "the abrasive agent scrapes off all
  of the paint except for the indents of the numbering"
  ([Wikipedia, Dice → Manufacturing](https://en.wikipedia.org/wiki/Dice)). The ink is therefore
  flush with the bottom of a recessed glyph, and the face around it is polished bare.
- Gamescience dice are sold **uninked** or hand-inked. Buyers fill the numerals with a crayon or a
  paint pen [V] ([Gnome Stew](https://gnomestew.com/things-i-learned-while-inking-14-gamescience-dice-with-a-crayon/);
  [Gaming Brouhaha review](https://gamingbrouhaha.wordpress.com/2010/01/10/review-gamescience-precision-dice/)).
- **Size and placement [V].** Chessex: "All of our numbers are as large as possible on each face",
  with each numeral centred on its face
  ([Chessex Catalog 6 p. 5](https://www.chessex.com/images/2019%20CAT06.pdf)). On the d4, three
  smaller numerals sit in the corners.
- **Font [V].** The modern Chessex mould uses its own font. Its marks: "an underlined 6 and/or 9, a
  7 with a hook on the horizontal line, an upstroke on the 1, and a running track shaped 0" (two
  straight sides joining the top and bottom arcs)
  ([Critical Fail](https://www.virtual-dreams.org/dice/dice-identification-101/)). Visually it is a
  bold, condensed sans-serif. Other moulds differ: Koplow numerals are wide, with a dot behind the
  6 and 9; Gamescience has serifed 1s and underlined 6 and 9 [V] (same source;
  [Dice Collecting Wiki](https://dice.miraheze.org/wiki/Gamescience_Precision_Dice_Identification)).
- **6/9 marks [V]:**
  - Chessex: "we now use underscoring, rather than tiny indicator dots, to differentiate 6's from
    9's" (Catalog 6). Its d20 marks only the 6 and 9 (d20_4 string).
  - Koplow and D&G use dots. Some Koplow d20s also dot the 16 and 19 (Critical Fail; Alea d20_29).
  - The d10 and d12 mark the 6 and 9; the d8 marks its 6.
  - It was not found whether the percentile die's 60 or 90 carries a mark. The catalogue photo shows
    no mark on its 90 [I].
- **Engraving depth: no source found.** The closest documented value is for casino dice: pips
  "drilled 17 ± 4 mils (0.43 ± 0.10 mm) deep" (Wikipedia, Dice). Suggestion [I]: model RPG numerals
  as about 0.3–0.5 mm deep, with slightly rounded inner edges from tumbling. A normal or parallax map
  is enough at 16–20 mm. Real geometry is only needed for close-ups.
- **Contrast.** Chessex sets pair a body colour with a contrasting ink, e.g. "Opaque Black/white".
  That pair (body / numeral ink) is what the GLOSSARY calls a Dice Set [V]
  ([Chessex opaque set](https://www.chessex.com/opaque-polyhedral-blackwhite-7-die-set)).

---

## 5. Reading the Face Value

- **d6, d8, d10, percentile d10, d12, d20.** Each solid is centrally symmetric, so the face opposite
  the one on the table is parallel to the table and faces straight up. The Face Value is that
  face's number [V] ("the face … of the die that is uppermost when it comes to rest provides the
  value", [Wikipedia, Dice](https://en.wikipedia.org/wiki/Dice)). The d10 symmetry follows from §2.2:
  Tₖ is antipodal to Bₖ₊₃ [D].
  - **Implementation [D]:** pick the face whose world-space outward normal has the largest dot
    product with world up (+Y). This is exact at rest and robust to tiny tilts.
- **d4.**
  - **Top-read** (Chessex style): the value is the label of the vertex pointing up, which appears
    upright near the apex on all three visible faces [V]. Implementation: pick the vertex with the
    greatest height. Equivalently, the value is the vertex label missing from the face on the table.
  - **Bottom-read**: the value is the number printed along the bottom edges of the visible faces [V].
- **Cocked dice** (resting on an edge or against a wall) are not addressed by any source. Re-roll or
  settle the physics [I].

---

## 6. Open questions and inferences to revisit

1. **Percentile d10 layout.** It is assumed to be the d10 layout ×10. Confirm with a photo set or a
   physical Chessex Tens die.
2. **Per-face numeral orientation on the d12 and d20.** The Alea strings encode it, but decoding
   them needs their net convention. The photo-based rule ("baseline parallel to an edge, top toward
   the opposite vertex/corner") is an inference.
3. **Edge and vertex radii, and engraving depth.** No primary source exists. Tune visually against
   reference photos.
4. **Sizes from hobbyist measurement.** The d4, d8, d10 tip-to-tip and d12 figures come from one
   hobbyist's calipers and should be cross-checked.
5. **"Standard" d20 numbers.** The Dice Lab's quoted "standard d20" vertex sums
   (39, 47, 49, 51, 52, 52, 53, 53, 54, 56, 58, 66) match neither Chessex nor any other layout
   decoded here. This shows several "standard" layouts are in circulation; it does not suggest an
   error in the Chessex data.

---

## Method and cross-checks

Python scripts were run once to produce the tables in §3. They are not committed, because this
branch is research only. What they did:

- **d20.** Transcribed the Fig. 3 net from the Bosch et al. PDF, rendered to an image. Checked it
  against the paper's printed vertex sums and face sums (all 32 match). Mapped it onto a 3D
  icosahedron and checked that opposite faces are antipodal. Wrote a decoder for Alea Kybos d20
  order strings ("20, its 3 neighbours CCW from upper-left, the next 6 CCW, then the lower half so
  position i and i+10 are opposite"). Both Chessex strings decoded to exactly the Bosch layout and
  chirality, which validates the decoder.
- **d8, d10, d12.** Applied the same decoder rule (upper half CCW from above, lower half antipodal).
  Checked the results against Kevin Cook's net images (d8_2, d10_1), the Chessex catalogue photos
  (d8, d10, d12, d20), the fact-index zig-zag sequence (d10) and The Dice Lab's Chessex d10 vertex
  sums. The brute force over all odd/even d10 layouts with opposite faces summing to 9 found exactly
  one layout plus its mirror.
- **Geometry.** Built each convex hull from coordinates and measured the edges, radii and dihedral
  angles. For the d10, derived the planarity condition c/h = (1−cos 36°)/(1+cos 36°) and swept the
  free parameter r/h.

## Sources

Primary and manufacturer sources:

- Chessex Manufacturing, *Catalog 6* (2019), "The Chessex Standard", p. 5:
  https://www.chessex.com/images/2019%20CAT06.pdf
- Chessex product pages: https://www.chessex.com/opaque-polyhedral-blackwhite-7-die-set ,
  https://www.chessex.com/16mm-dice-block
- R. Bosch, R. Fathauer, H. Segerman, "Numerically Balanced Dice" (preprint, Jan 2016):
  http://dicecollector.com/docs/diceinfo_2016_NUMERICALLY_BALANCED_DICE_JANUARY.pdf ; published in
  *The Mathematics of Various Entertaining Subjects* vol. 2, Princeton UP 2017,
  https://doi.org/10.23943/princeton/9780691171920.003.0014
- The Dice Lab (Fathauer and Segerman, a manufacturer):
  https://www.mathartfun.com/thedicelab.com/BalancedStdPoly.html ,
  https://mathartfun.com/thedicelab.com/DiceDesign.html
- Gamescience precision dice description (via Noble Knight):
  https://www.nobleknight.com/Products/Dice---Precision-Edge-High-Impact-Plain-Lou-Zocchi
- L. Zocchi, GenCon 2015 talk (a competitor's claims about tumbling):
  https://www.youtube.com/watch?v=MRzg_M8pQms
- A. F. and G. A. Constantinides, "Spindown Polyhedra", *Mathematical Gazette* 2018:
  https://doi.org/10.1017/mag.2018.111

Mathematical references:

- Wikipedia, Pentagonal trapezohedron: https://en.wikipedia.org/wiki/Pentagonal_trapezohedron
- Wolfram MathWorld, Pentagonal Trapezohedron: https://mathworld.wolfram.com/PentagonalTrapezohedron.html
- D. McCooey, Visual Polyhedra, Pentagonal Trapezohedron:
  https://dmccooey.com/polyhedra/PentagonalTrapezohedron.html

Collector databases (layouts documented from physical dice):

- Alea Kybos' Dice Collection, Configurations: https://aleakybos.ch/Configurations.htm
- Kevin Cook, DiceCollector.com layout pages: http://www.dicecollector.com/d4face.html (likewise
  `d6face`, `d8face`, `d10face`, `d12face`, `d20face`) and net images such as
  http://www.dicecollector.com/d10_1.jpg
- Dice Collecting Wiki, Gamescience: https://dice.miraheze.org/wiki/Gamescience_Precision_Dice_Identification
- Critical Fail, "Dice Identification 101": https://www.virtual-dreams.org/dice/dice-identification-101/

Secondary and lower-trust sources (labelled where used):

- Wikipedia, Dice: https://en.wikipedia.org/wiki/Dice
- fact-index.com mirror of the old Wikipedia "10-sided dice" article: http://www.fact-index.com/1/10/10_sided_dice.html
- Printables, hobbyist caliper measurements:
  https://www.printables.com/model/658134-polyhedral-dice-storage-parametric-gridfinity
- geneome, caliper measurements: https://geneome.com/2013/02/18/gamescience-dice-shape/
- Dice Dragons size survey: https://www.dicedragons.co.uk/blogs/dice-advice/dice-size-comparison
- GMDICE, manufacturing: https://www.gmdice.com/blogs/dnd/how-are-dice-made
- MTG Wiki, Life counter: https://mtg.wiki/page/Life_counter
- boardgames.stackexchange, d20 numbering:
  https://boardgames.stackexchange.com/questions/16676/is-there-a-standard-way-to-number-the-faces-of-a-20-sided-die
