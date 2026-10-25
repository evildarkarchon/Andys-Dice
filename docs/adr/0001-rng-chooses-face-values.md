# The random number generator chooses Face Values; physics only animates the Throw

A single injected `RandomGenerator` (seeded from `SecureRandom` in the app, from fixed seeds in tests) chooses every Face Value. Physics then pre-simulates the Throw headlessly from random release conditions, recording each Die's motion until all dice rest. Before playback, each Die's numbering is rotated by one of its solid's own symmetries so the chosen numeral sits on the face (or d4 vertex) that landed up. Because the shape is unchanged by that rotation, the physics is untouched, and the real numeral layout is preserved. We did this so that fairness is the generator's uniformity: provable and testable without running physics. The motion stays physically genuine, and the Roll Result is always computed from the Face Values the dice visibly show.

## Considered Options

- **Physics decides the Face Value.** This was rejected because fairness would depend on the simulation being unbiased (mesh asymmetry, the d10's kite shape, the friction model), which can only be argued from large statistical runs, and any physics bug becomes roll bias.
- **Choose the result, then search for a Throw that lands on it.** This was rejected because re-simulating until the dice happen to match is wasteful, and it scales badly as the number of dice in a Throw grows.

## Consequences

- Physics determinism is not required, because the renderer plays back a recording and nothing re-simulates expecting the same outcome. Any future replay stores the recording, not a seed.
- A pre-simulated Throw that ends with a Cocked Die or a Die outside the tray is discarded and re-simulated whole, silently and under a retry cap. Exceeding the cap is an error, never a fudged result. *Amended by ADR-0006:* when a Roll Expression has Reroll Throws, only the failing Throw is re-simulated, and the Dice already at rest stay frozen. *Amended by ADR-0013:* the cap is 24 attempts, and exceeding it leads to a visible Cocked Rethrow of only the Cocked Dice, not an error. The error remains only past 3 Cocked Rethrows, or when no attempt came to rest.
- The Roll Result is revealed only when playback reaches the moment every Die is at rest, even though it is known before the Throw starts.
- *Amended by ADR-0003 and ADR-0012:* this generator stays the only one that chooses Face Values, but it is no longer the only generator. Release conditions draw from a second one (ADR-0003) and sound variation from a third (ADR-0012). Each is seeded independently, so neither a physics retry nor sound can shift a Face Value.
