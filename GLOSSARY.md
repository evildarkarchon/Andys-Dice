# Andy's Dice

A desktop application that performs animated, realistic-looking Dungeons & Dragons dice rolls for a single person at the table.

## Dice

**Die**:
One physical-looking polyhedral die of a given kind (d4, d6, d8, d10, percentile d10, d12, d20).
_Avoid_: Dice (as a singular), token

**Die Kind**:
The shape and numbering of a die, identified by its face count, plus the percentile d10, which is a d10 numbered 00–90 in tens.
_Avoid_: Die type, die size

**Dice Set**:
A matched visual style (material, colour, numeral ink) applied to the dice in a roll, modelled on the 7-piece D&D set: d4, d6, d8, d10, percentile d10, d12, d20.
_Avoid_: Skin, theme, pack

**Percentile Roll**:
A d100 result produced by rolling a percentile d10 and a d10 together, the percentile die giving the tens and the d10 the units, with 00 + 0 read as 100.
_Avoid_: d100 die

## Rolling

**Roll Expression**:
A request for a roll written in dice notation, for example `2d6+1d8+3`, `1d20 adv` or `4d6kh3`.
_Avoid_: Formula, command, query

**Throw**:
One animated tumble of every die a Roll Expression calls for, from release until all dice come to rest.
_Avoid_: Animation, toss

**Face Value**:
The number a single die shows once it is at rest.
_Avoid_: Pip, die result

**Roll Result**:
The total and breakdown obtained by applying a Roll Expression's modifiers, advantage/disadvantage and keep rules to the Face Values of a Throw.
_Avoid_: Outcome, score

**Advantage / Disadvantage**:
Rolling 2d20 and keeping the higher (advantage) or the lower (disadvantage).

**Keep Highest / Keep Lowest**:
Keeping only the N highest or N lowest Face Values of a group of dice, as in `4d6kh3`.
_Avoid_: Drop lowest (use the keep form)
