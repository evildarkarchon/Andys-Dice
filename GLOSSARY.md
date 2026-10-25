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
A matched visual style (material, colour, numeral ink) applied to the dice in a roll, modelled on the 7-piece D&D set: d4, d6, d8, d10, percentile d10, d12, d20. Some ship with the app, and a player can add their own.
_Avoid_: Skin, theme, pack, material (the Dice Set is the whole look, not just the body)

**Numeral**:
A number engraved into a die face and filled with ink. Most faces carry one, and a d4 face carries three, one per corner. It is what a die shows, as distinct from the Face Value it stands for.
_Avoid_: Number, label, pip

**Percentile Roll**:
A d100 result produced by rolling a percentile d10 and a d10 together, the percentile die giving the tens and the d10 the units, with 00 + 0 read as 100.
_Avoid_: d100 die

## Tray

**Tray**:
The walled surface the Dice are thrown into: its outline, its walls and its floor. Some ship with the app, and a player can add their own.
_Avoid_: Board, box, arena, table (the table is what the Tray sits on)

**Light Rig**:
The lighting the Tray and Dice are shown under, chosen separately from the Tray.
_Avoid_: Lighting preset, scene, theme

## Rolling

**Roll Expression**:
A request for a roll written in dice notation, for example `2d6+1d8+3`, `1d20 adv` or `4d6kh3`.
_Avoid_: Formula, command, query

**Dice Group**:
A count of Dice of one Die Kind within a Roll Expression, with an optional reroll rule and keep rule, such as the `4d6rr1kh3` in `4d6rr1kh3+2`.
_Avoid_: Dice term, pool

**Modifier**:
A constant integer added to or subtracted from a Roll Expression, such as the `+3` in `2d6+3`.
_Avoid_: Bonus, constant, flat

**Throw**:
One animated tumble of a set of Dice, from release until all of them come to rest, each showing a Face Value.
_Avoid_: Animation, toss

**Reroll**:
Throwing a Die again to replace its Face Value when that value meets its Dice Group's reroll condition: once (`ro`), or until it no longer does (`rr`).
_Avoid_: Re-roll, mulligan

**Reroll Throw**:
A Throw of only the Dice being rerolled, made after the previous Throw comes to rest, while the other Dice stay where they lie.
_Avoid_: Second throw, follow-up roll

**Face Value**:
The number a single die shows once it is at rest.
_Avoid_: Pip, die result

**Cocked Die**:
A Die that comes to rest without a single face (or, for a d4, a single vertex) pointing up, such as one leaning on a wall of the Tray or on another Die. It has no Face Value, so a Throw never ends with one.
_Avoid_: Leaner, tilted die

**Escaped Die**:
A Die that leaves the Tray over its walls during a Throw. A Throw never ends with one.
_Avoid_: Lost die, fallen die

**Roll Result**:
The total and breakdown obtained by applying a Roll Expression's Modifiers, Advantage/Disadvantage and keep rules to the final Face Values of its Throws.
_Avoid_: Outcome, score

**Advantage / Disadvantage**:
Rolling 2d20 and keeping the higher (advantage) or the lower (disadvantage).

**Keep Highest / Keep Lowest**:
Keeping only the N highest or N lowest Face Values of a group of dice, as in `4d6kh3`.
_Avoid_: Drop lowest (use the keep form)

**Roll**:
One rolling of a Roll Expression: its Throws and the Roll Result they produce.
_Avoid_: Roll event, history entry

**Roll History**:
The most recent Rolls, newest first, each remembered with its Roll Expression, Face Values and, if it came from one, its Saved Roll's name at the time.
_Avoid_: Log, roll log

**Saved Roll**:
A Roll Expression kept under a name the player chose, so it can be rolled again without retyping.
_Avoid_: Favourite, macro, preset
