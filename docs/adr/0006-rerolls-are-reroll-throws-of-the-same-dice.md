# A Reroll is a Reroll Throw of the same Dice, with resting Dice frozen

A Roll Expression with a reroll rule (`ro` once, `rr` until the condition no longer matches) is shown as a chain of Throws. The first Throw plays out, and every Die comes to rest. Then each Die whose Face Value is rerolled is picked up and Thrown again in a Reroll Throw. All the Dice rerolled in that round go together, across every Dice Group. `ro` makes at most one round, and `rr` repeats rounds until nothing matches. Dice that stay put are immovable obstacles in the Reroll Throw's pre-simulation, so a Face Value the player has already seen can never be knocked over. Re-throwing the same Die is what happens at a real table. And because ADR-0001 chooses every Face Value up front, the whole chain is known before anything moves. That keeps the motion honest without any physics deciding a result.

## Considered Options

- **Add a replacement Die.** A new Die would be Thrown, and the rerolled-away one would stay greyed out on the tray. This was rejected because it clutters the tray, inflates the Dice-per-Throw count, and doesn't match how a reroll is made at the table.
- **Change the Face Value without animation.** This was rejected because it fails the realism bar. The die would visibly show one number while the Roll Result counted another.
- **Resolve the reroll inside the original Throw.** There is no physical gesture for this. A Die that tumbles once shows one Face Value.

## Consequences

- **Many Throws per Roll Expression.** A Roll Expression produces one recording per Throw, not one in total. This amends ADR-0003's single-recording assumption. The Roll Result appears only when the last Throw is at rest.
- **Frozen obstacles.** A Reroll Throw's pre-simulation starts from the previous Throw's resting poses. The Dice not being rerolled are static bodies there, and the rerolled Dice are released again from conditions drawn from the release generator (ADR-0003). The numbering rotation (ADR-0001) is applied per Throw to each re-thrown Die, for the next value in its chain.
- **Whole chain pre-simulated up front.** Every Throw in the chain is pre-simulated before playback starts. A Throw that ends cocked, escaped or timed out (for example a Die leaning on a frozen Die) re-simulates only that Throw, under the existing retry cap. If any Throw exceeds the cap, the whole Roll Expression fails before anything is shown. *Amended by ADR-0013:* a Throw that exceeds the cap is first followed by up to 3 Cocked Rethrows, and the Roll Expression fails only past those. Pipelining later Throws during playback is allowed as an optimisation, as long as it keeps these error semantics. The extra delay before the first Throw is counted in the performance targets.
- **`rr` is bounded twice.** At parse time, an `rr` condition may match at most half the Die Kind's faces, which bounds the expected number of rounds per Die at 2. At run time, a backstop of 100 reroll rounds per Roll Expression guarantees termination. Exceeding it is an error, never a fudged result.
- **The Dice-per-Throw cap is unchanged.** A Reroll Throw only re-throws Dice already counted in the first Throw.
- **The breakdown is a chain.** Each Die records every Face Value it showed, oldest first, and only the last one counts. "Rerolled" is a separate mark from "unkept". Keep-rule ties go to the Die's original position in its Dice Group.
