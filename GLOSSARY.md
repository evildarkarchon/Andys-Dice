# Andy's Dice

A desktop application that performs animated, realistic-looking Dungeons & Dragons dice rolls for a single person at the table.

**Player**:
The one person at the table using Andy's Dice.
_Avoid_: User, customer

## Dice

**Die**:
One physical-looking polyhedral die of a given kind (d4, d6, d8, d10, percentile d10, d12, d20).
_Avoid_: Dice (as a singular), token

**Die Kind**:
The shape and numbering of a die, identified by its face count, plus the percentile d10, which is a d10 numbered 00–90 in tens.
_Avoid_: Die type, die size

**Dice Set**:
A matched visual style (material, colour, numeral ink) applied to the dice in a roll, modelled on the 7-piece D&D set: d4, d6, d8, d10, percentile d10, d12, d20. Some ship with the app, and a player can add their own.
_Avoid_: Skin, theme, pack, material (the Dice Set is everything about how a Die is drawn, not just its body)

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

**Footprint**:
The fixed inner floor area that every Tray's outline fits inside, whichever Tray is chosen.
_Avoid_: Tray size

**Light Rig**:
The lighting the Tray and Dice are shown under, chosen separately from the Tray.
_Avoid_: Lighting preset, scene, theme

**Look**:
What the Dice and the Tray are drawn as: the Dice Set, the Tray and the Light Rig, each picked on its own in the Look tab.
_Avoid_: Theme, skin, style

**Player File**:
A Dice Set or Tray the Player added as a file of their own, listed after the ones that ship with the app.
_Avoid_: Mod, custom file, user file

## Rolling

**Roll Expression**:
A request for a roll written in dice notation, for example `2d6+1d8+3`, `1d20 adv` or `4d6kh3`.
_Avoid_: Formula, command, query

**Dice Group**:
A count of Dice of one Die Kind within a Roll Expression, with an optional reroll condition and keep rule.
_Avoid_: Dice term, pool

**Modifier**:
A constant integer added to or subtracted from a Roll Expression, such as the `+3` in `2d6+3`.
_Avoid_: Bonus, constant, flat

**Throw**:
One animated tumble of a set of Dice, from their Release until all of them come to rest. Each Die normally ends showing a Face Value; any left as Cocked Dice are thrown again in a Cocked Rethrow.
_Avoid_: Animation, toss

**Reroll**:
Throwing a Die again to replace its Face Value when that value meets its Dice Group's reroll condition: either once, or until it no longer does.
_Avoid_: Re-roll, mulligan

**Reroll Throw**:
A Throw of only the Dice being rerolled, made after the previous Throw comes to rest, while the other Dice stay where they lie.
_Avoid_: Second throw, follow-up roll

**Face Value**:
The number a single die shows once it is at rest.
_Avoid_: Pip, die result

**Face Value Chain**:
Every Face Value one Die showed during a Roll, oldest first, of which only the last counts. It holds more than one only when the Die was Rerolled.
_Avoid_: Reroll history

**Cocked Die**:
A Die that comes to rest without lying flat on a face, such as one leaning on a wall of the Tray or on another Die. It has no Face Value, so a Roll never ends with one.
_Avoid_: Leaner, tilted die

**Cocked Rethrow**:
A Throw of only the Cocked Dice left by a Throw, made while the other Dice stay where they lie, to give them the Face Values they never had. Unlike a Reroll Throw, it replaces no Face Value.
_Avoid_: Cocked reroll, re-throw, do-over

**Escaped Die**:
A Die that leaves the Tray over its walls during a Throw. A Throw never ends with one.
_Avoid_: Lost die, fallen die

**Roll Result**:
The total and Breakdown obtained by applying a Roll Expression's Modifiers, Advantage/Disadvantage and keep rules to the final Face Values of its Throws.
_Avoid_: Outcome, score

**Breakdown**:
The part of a Roll Result that shows each term as written, its subtotal, and each Die's Face Value Chain, marked where a Die was rerolled or not kept.
_Avoid_: Details, explanation

**Advantage / Disadvantage**:
Rolling 2d20 and keeping the higher (advantage) or the lower (disadvantage).

**Keep Highest / Keep Lowest**:
Keeping only the N highest or N lowest Face Values of a group of dice, as in `4d6kh3`.
_Avoid_: Drop lowest (use the keep form)

**Roll**:
One rolling of a Roll Expression: its Throws and the Roll Result they produce.
_Avoid_: Roll event, history entry

**Failed Roll**:
A Roll that ends without a Roll Result, because its Dice could not be brought to rest or it needed too many Rerolls. Nothing moves, and it never joins the Roll History.
_Avoid_: Error roll, aborted roll

**Lost Roll**:
A Roll cut off before its Roll Result was revealed, because the session ended in a runtime failure or the app closed. It was never shown, so it never joins the Roll History. Unlike a Failed Roll, its Dice may already have been moving.
_Avoid_: Abandoned roll, cancelled roll

**Queued Roll**:
A Roll the Player asks for while Dice are still moving, which starts shortly after the current Roll Result is revealed. There is at most one.
_Avoid_: Pending roll, next roll

**Fast-forward**:
Playing the current Roll's Throws faster for as long as the Player holds it. Nothing is ever skipped.
_Avoid_: Skip, speed-up

**Roll History**:
The most recent Rolls, newest first, each remembered with its Roll Expression, Face Values and, if it came from one, its Saved Roll's name at the time.
_Avoid_: Log, roll log

**Saved Roll**:
A Roll Expression kept under a name the player chose, so it can be rolled again without retyping.
_Avoid_: Favourite, macro, preset

## Simulation

**Release**:
How the Dice leave the hand at the start of a Throw: where they start, and how they are moving and spinning.
_Avoid_: Launch, spawn

**Attempt**:
One unseen simulation of a Throw from its Release. A Throw takes as many Attempts as it needs, and only the accepted one is ever shown.
_Avoid_: Try, retry (as a noun)

**Recording**:
The accepted Attempt's motion of every Die, and the contacts it made, played back to show a Throw.
_Avoid_: Replay, animation

**Numbering Rotation**:
Turning a Die's numbering by one of its shape's own symmetries, so that the Face Value chosen for it is the Numeral that landed up.
_Avoid_: Relabelling, remapping

**Frozen Die**:
A Die left lying still during a Reroll Throw or a Cocked Rethrow, which the moving Dice can strike but never move.
_Avoid_: Static die, locked die

**Settle Nudge**:
A small tip given, within an Attempt, to a Die settling cocked, so that it falls onto its nearest face.
_Avoid_: Push, correction

## The app

**Roll UI**:
The window the Player rolls from: the Roll Expression box, the Tray, the Roll Result and the tabs beside them.
_Avoid_: Main screen, HUD

**Notice**:
A one-time message from the app that waits behind the bell until the Player reads it, never interrupting a Roll.
_Avoid_: Toast, alert, popup

**Quality Preset**:
The one setting that trades looks for speed: Low, Medium or High.
_Avoid_: Graphics settings, quality levers
