# Roll Expression grammar (v1)

The committed reference for what the Roll Expression box accepts, what it means and how it fails. It
gathers the base grammar settled in
[What is the v1 Roll Expression grammar?](https://github.com/evildarkarchon/Andys-Dice/issues/7#issuecomment-6382077638),
the reroll extension in
[Which reroll forms does the v1 Roll Expression grammar support?](https://github.com/evildarkarchon/Andys-Dice/issues/13)
([ADR-0006](adr/0006-rerolls-are-reroll-throws-of-the-same-dice.md)), and the caps in
[ADR-0014](adr/0014-performance-is-budgeted-as-scaled-gpu-time-and-checked-by-a-bench.md). Where this file
and those ticket comments differ, this file wins: it carries the later corrections listed under
[Sharpened at hand-off](#sharpened-at-hand-off).

The [example table](#examples) is the source of truth for the parser's parameterised tests (ADR-0005). Terms
are as in [`GLOSSARY.md`](../GLOSSARY.md).

## Grammar

```ebnf
expression = [ sign ] term { sign term } [ advantage ] ;
sign       = "+" | "-" ;
term       = dice-group | modifier ;
dice-group = [ count ] "d" die-kind [ reroll ] [ keep ] ;
die-kind   = "4" | "6" | "8" | "10" | "12" | "20" | "100" | "%" ;
reroll     = ( "ro" | "rr" ) condition ;
condition  = [ compare ] value ;
compare    = "<" | "<=" | ">" | ">=" ;
keep       = ( "kh" | "kl" ) [ count ] ;
advantage  = "adv" | "dis" ;
count      = digit { digit } ;      (* >= 1 *)
value      = digit { digit } ;
modifier   = digit { digit } ;      (* 0-999 *)
```

## Lexical rules

- **Case-insensitive.** `2D6`, `D%`, `ADV`, `4d6KH3` and `1d6RO1` are all accepted.
- **Spaces.** The only whitespace is the ASCII space, U+0020. Any number of spaces may stand between tokens,
  before the first and after the last. No space may stand inside a token: `2 d6`, `k h3` and `ro <3` are
  invalid. A tab, a newline or any other control character is an invalid character, never a space. That
  keeps ADR-0007's tab-separated files free of escaping.
- **Integers.** Leading zeros are ordinary (`02d6` ≡ `2d6`).
- **`adv` / `dis`** needs no space before it (`1d20adv` ≡ `1d20 adv`).
- **Alphabet.** Nothing outside digits, `d`, `k`, `h`, `l`, `r`, `o`, `a`, `v`, `i`, `s`, `%`, `+`, `-`, `<`,
  `>`, `=` and the space can appear. There are no parentheses, `*`, `/`, labels or comments.

## Validation rules

**Die Kinds and counts**
- Only the 7-piece set: d4, d6, d8, d10, d12, d20, and `d%` ≡ `d100` for one Percentile Roll. Any other `dY`
  (`d7`, `d3`, `dF`, `d1000`) is invalid.
- A missing count means 1 (`d20` ≡ `1d20`). A count must be at least 1, so `0d6` is invalid.
- There must be at least one Dice Group: a bare `5` throws nothing.

**Keep rules**
- `khN` / `klN`. A missing N means 1, and N runs from 1 to the group's count. They apply to every Die Kind,
  including Percentile Rolls (`2d%kh1`).
- A bare `k` and the drop forms `dl` / `dh` are invalid, and the error suggests the keep form.

**Rerolls**
- `ro` rerolls once and `rr` rerolls until the condition no longer matches. A bare `r` is invalid as
  ambiguous.
- The condition is one value (`ro1`) or one comparison (`ro<3`, `<=`, `>`, `>=`), with its ordinary meaning.
  There is one reroll clause per Dice Group: no stacking (`ro1ro2`) and no lists.
- The condition must match at least one face and not every face, checked against 1–N, or 1–100 for `d%`.
- An `rr` condition may match at most half the faces: d4 ≤ 2, d6 ≤ 3, d8 ≤ 4, d10 ≤ 5, d12 ≤ 6, d20 ≤ 10,
  `d%` ≤ 50. `ro` has only the range rule.
- The reroll clause comes before the keep rule (`4d6rr1kh3`). `4d6kh3rr1` is invalid.

**Advantage / Disadvantage**
- A single trailing `adv` or `dis` turns the expression's one added `1d20` term into `2d20kh1` or `2d20kl1`.
  A reroll clause on that term carries over: `1d20ro1 adv` ≡ `2d20ro1kh1`.
- The keyword is rejected if there is no added `1d20` term, if there is more than one d20 term, if the d20
  term has a count above 1 or its own keep rule, if the d20 term is subtracted, if the keyword is not at the
  end, or if more than one keyword is written.
- Only the short forms are accepted. `advantage` is an error that suggests `adv`.

**Caps**
- **Expression length:** at most **100 characters**, counted as typed, spaces included.
- **Dice per Throw:** at most **20 physical Dice**, a constant in code, not a player setting. A Percentile Roll
  counts 2, Advantage or Disadvantage counts 2, and Dice that aren't kept still count, because they are
  still Thrown. Reroll Throws and Cocked Rethrows add nothing: they re-throw Dice already counted.
- **Modifiers:** each at most **999**.

## Meaning

- Each Die yields one Face Value, chosen by the Face Value generator (ADR-0001). A Percentile Roll yields
  one value from 1 to 100, built from its two Dice as `GLOSSARY.md` describes.
- **Rerolls before keeps.** Every Die's Face Value Chain is settled first, and only its last value counts.
  A `d%` reroll tests the 1–100 value and rerolls both of its Dice together. A reroll round re-throws every
  matching Die in the expression together, as one Reroll Throw (ADR-0006).
- **Keeps.** A Dice Group's value is the sum of its kept values. A tie between kept and unkept values goes
  to the Die's **original position in its Dice Group**, whichever Throw it last landed in.
- **Totals.** The Roll Result total is the signed sum of all terms, with **no floor**: `1d4-5` can total −4.
- **Terms stay as written.** They are never merged or reordered: `1d6+1d6` stays two terms in the
  Breakdown and is not the same as `2d6`.
- **Termination.** There is a backstop of 100 reroll rounds per Roll Expression. Going past it fails the
  Roll, never fudges it.

## Errors

- **All or nothing.** An invalid Roll Expression starts no Throw and rolls nothing.
- **One error.** Only the first error is reported: one message and the character span it refers to.
- **Spans** are 0-based, end-exclusive character offsets into the input as typed, `[start, end)`. A
  zero-width span (`[n, n)`) marks an error at a position, such as a missing term at the end.
- **Which error is first.** The length cap is checked first. Then the input is read left to right, and the
  first character, syntax or per-term problem (Die Kind, count, keep range, reroll range, half-the-faces)
  is reported. The whole-expression checks come last, in this order: at least one Dice Group, `adv` /
  `dis`, then Dice per Throw.
- **Messages.** The span is the contract. Message wording may be polished in the build, but each message
  must name the problem and give the suggestion the table shows. The messages quoted from #7 and #13 are
  kept as written.

## Examples

### Valid

| Input | Means |
|---|---|
| `2d6+1d8+3` | two Dice Groups and a Modifier |
| `d20` | `1d20` |
| `2D6`, `D%`, `4d6KH3` | case-insensitive |
| `02d6` | `2d6` |
| ` 1d20 + 5 ` | `1d20+5`: spaces around tokens are ignored |
| `-1d4+2` | a leading sign on the first term |
| `1d20-1d4` | a subtracted Dice Group |
| `1d6+1d6` | two terms, not `2d6` |
| `d%`, `d100` | one Percentile Roll (2 Dice) |
| `2d%kh1` | keep the higher of two Percentile Rolls |
| `4d6kh3` | keep the three highest |
| `1d20 adv`, `1d20adv` | `2d20kh1` |
| `1d20+5 dis` | `2d20kl1+5` |
| `4d6rr1kh3` | reroll 1s until none remain, then keep three |
| `2d6ro<=2` | reroll a 1 or 2 once (Great Weapon Fighting) |
| `1d20ro1 adv` | `2d20ro1kh1` |
| `1d20rr<=10` | exactly half the faces |
| `1d4-5` | can total −4 |
| `10d6+10d6`, `10d%`, `18d6+1d20 adv` | exactly 20 Dice |

### Invalid

| Input | Span | Error, and its suggestion |
|---|---|---|
| *(empty, or only spaces)* | `[0, 0)` | nothing typed; type a Roll Expression such as `1d20` |
| *(101 characters)* | `[100, 101)` | a Roll Expression is at most 100 characters |
| `1d7` | `[1, 3)` | "`d7` is not a Die Kind; use d4, d6, d8, d10, d12, d20 or d%" |
| `2 d6` | `[1, 2)` | no space inside `2d6` |
| `1d6ro <3` | `[5, 6)` | no space inside `ro<3` |
| `1d20⇥+5` (a tab) | `[4, 5)` | a tab can't appear; use a space |
| `(1d6+2)` | `[0, 1)` | `(` can't appear in a Roll Expression |
| `1d20+5 # attack` | `[7, 8)` | `#` can't appear in a Roll Expression |
| `2d6+` | `[4, 4)` | a term must follow `+` |
| `0d6` | `[0, 1)` | a Dice Group throws at least 1 Die |
| `5` | `[0, 1)` | nothing to Throw; add a Dice Group such as `1d20` |
| `1d20+1000` | `[5, 9)` | a Modifier is at most 999 |
| `4d6dl1` | `[3, 6)` | drop forms aren't used; use the keep form, `4d6kh3` |
| `4d6k3` | `[3, 4)` | write `kh` (keep highest) or `kl` (keep lowest) |
| `4d6kh5` | `[3, 6)` | `4d6` can keep at most 4 |
| `1d6r1` | `[3, 4)` | "`r` is ambiguous; use `ro` (once) or `rr` (until)" |
| `1d6ro7` | `[3, 6)` | `ro7` never matches a d6 |
| `1d6ro>=1` | `[3, 8)` | `ro>=1` matches every face of a d6 |
| `1d4rr<5` | `[3, 7)` | `rr<5` matches every face of a d4 |
| `1d20rr<=19` | `[4, 10)` | "`rr` may reroll at most half the faces; use `ro` to reroll once" |
| `1d6ro1ro2` | `[6, 9)` | one reroll clause per Dice Group |
| `4d6kh3rr1` | `[6, 9)` | "write the reroll before the keep rule: `4d6rr1kh3`" |
| `1d6 adv` | `[4, 7)` | `adv` needs one added `1d20` |
| `2d20 adv` | `[5, 8)` | `adv` needs one added `1d20`; write `2d20kh1` or `1d20 adv` |
| `-1d20 adv` | `[6, 9)` | `adv` needs one added `1d20`, not a subtracted one |
| `1d20+1d20 adv` | `[10, 13)` | `adv` needs exactly one d20 term |
| `1d20 adv+5` | `[5, 8)` | `adv` goes at the end |
| `1d20 adv dis` | `[9, 12)` | "use only one of adv or dis" |
| `1d20 advantage` | `[5, 14)` | write `adv` |
| `11d6+10d6` | `[5, 9)` | at most 20 Dice per Throw; this throws 21 |
| `11d%` | `[0, 4)` | at most 20 Dice per Throw; this throws 22 |

The Dice-per-Throw span covers the Dice Group whose Dice take the count past 20.

## Sharpened at hand-off

These points were settled in
[What does the v1 definition hand off, and how does it split into build issues?](https://github.com/evildarkarchon/Andys-Dice/issues/23)
and correct or extend the ticket comments:

- **Spaces only.** #7 allowed "whitespace" without defining it. It is now the ASCII space alone, and a tab or
  other control character is an invalid character.
- **Dice per Throw** is the constant 20 (ADR-0014), not #7's "configurable cap, provisionally 20".
- **Expression length** is 100 characters (ADR-0014), which #7 left open.
- **Keep ties** go to the original position in the Dice Group (#13, ADR-0006), not #7's "earlier Die in the
  Throw".
- **Spans and error order** are defined here as above. #7 said only "the first error".
