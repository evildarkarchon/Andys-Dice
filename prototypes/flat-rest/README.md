# PROTOTYPE: how a 20-Dice Throw reaches a flat rest (throwaway)

> Lives only on the `prototype/flat-rest` branch. Never merge it. It answers one question for
> [How does a 20-Dice Throw reach a flat rest?](https://github.com/evildarkarchon/Andys-Dice/issues/21)
> and is kept as a primary source. It is a fork of the `prototype/tray` code with the physics opened up:
> `Throws` takes `Knobs`, and `FlatRest` is a headless measuring harness. The renderer and the rest are unchanged.

**Verdict (2026-10-24).** The fixes are a one-layer, gentler release, die-on-die friction of 0.2, and a
deterministic settle nudge, at a 4° Cocked Die tolerance. With them, the 20-Dice cap takes 1.3–1.7 attempts
per Throw on all three built-in Trays, and none of 900 Throws ran out. Silent whole-Throw retries are capped
at 24. If they ever run out, a visible **Cocked Rethrow** of only the Cocked Dice follows; the error is left
only as the backstop, after 3 of them. Recorded in
[ADR-0013](https://github.com/evildarkarchon/Andys-Dice/blob/main/docs/adr/0013-throws-reach-a-flat-rest-by-release-friction-and-nudges.md).
The release and the nudge are measured but not yet judged in motion. They are on the release checklist.

**Question.** The tray prototype's 20-Dice Throws often rested with a Die leaning on another. How does a
Throw of the cap come to rest with every Die flat, which tolerance and retry cap are right, and what happens
when every attempt fails?

## What was measured

Everything runs on ADR-0010's three built-in Trays: green felt in a walnut rectangle, brown leather in a
padded hexagon, and bare walnut in a walnut rectangle. All are 300 x 200 mm, with 30 mm walls and the
visible-wall escape volume. Each row is 300 Throws of the 20-Dice mix, with seeds 1000 to 1299.

- **whole**: ADR-0001 as written, re-simulating the whole Throw. One run is scored at 4, 6, 8, 10 and 15
  degrees, because an attempt flat at 4° is flat at every wider tolerance. The table shows the per-attempt
  flat rate *p* and the extrapolated failure rate (1-p)^cap at caps of 12, 24 and 48. Each Cocked Die is
  sorted by tilt band and by what it leans on (a Die, a wall, or nothing).
- **cocked-only**: freeze the flat Dice and rethrow only the Cocked ones, round after round.
- **reroll**: ADR-0006 Reroll Throws of 1, 4 and 10 Dice among the rest, frozen.
- **count**: attempts against the number of Dice (2 to 25).

| Knobs (`FlatRest.KNOBS`) | What changes |
|---|---|
| `base` | The tray prototype: 5 x 2 handful in layers, thrown hard, die friction 0.4 |
| `spread`, `gentle`, `spread+gentle` | One layer of 7 x 3 at 30 mm / 0.65x speed, softer down, less spin / both |
| `dieF 0.2`, `dieF 0.1` | Die-on-die friction only; die-on-floor and die-on-wall friction kept (floor and walls rescaled) |
| `sleep strict` | Sleep at 0.2 cm/s over 0.6 s instead of 0.4 cm/s over 0.35 s |
| `nudge` | A Die still for 0.15 s while tilted past 4° gets a levelling spin (5 rad/s) and a 4 cm/s hop, up to 3 times |
| `s+g dieF 0.2`, `s+g nudge`, `combo` | Spread + gentle with friction 0.2 / with nudge / with both (**chosen**) |

Results: [`flat-rest-whole.txt`](flat-rest-whole.txt), [`flat-rest-policies.txt`](flat-rest-policies.txt), and
the first 100-Throw sweep, [`flat-rest-sweep-100.txt`](flat-rest-sweep-100.txt).

### Headlines (4° tolerance, attempts per Throw: felt / hexagon / walnut)

| Knobs | Attempts | Throws past 12 attempts (of 300) | Est. failure at 24 |
|---|---|---|---|
| base | 4.99 / 7.38 / 4.03 | 19 / 50 / 7 | 5e-3 / 3e-2 / 1e-3 |
| spread+gentle | 2.61 / 3.50 / 1.69 | 2 / 7 / 0 | 9e-6 / 3e-4 / 4e-10 |
| s+g dieF 0.2 | 1.97 / 2.45 / 1.46 | 0 / 2 / 0 | 4e-8 / 3e-6 / 9e-13 |
| **combo** | **1.59 / 1.71 / 1.30** | **0 / 0 / 0** | **5e-11 / 7e-10 / 5e-16** |

- **Cocked Dice are propped, not slightly tilted.** About 97% lean on another Die, and only about 20% are
  within 10° of flat, so a wider tolerance buys little: the combo goes from 1.59 to 1.44 attempts at 8°.
- **Stricter sleep thresholds do nothing** except make each attempt 30% slower.
- **Cocked-only rethrows always converge** (at most 6 rounds at base, 3 with the combo). But 24–41% of
  Throws would show one even with the combo, so it is the fallback, not the default.
- **Reroll Throws among frozen Dice are fine**: with the combo, 10 Dice among 10 frozen ones took at most 7 attempts.
- **Count**: with the combo, 25 Dice take 2.12 attempts in the felt Tray (against 20.8 at base).
- **Cost**: about 4.5 ms an attempt with the combo; time to rest is unchanged at about 0.8 s.

## Run

From this directory, with JDK 27:

```
JAVA_HOME='C:\OpenJDK\jdk-27' mvn.cmd -q package
bash run-flat-rest.sh 300 "base,spread+gentle,combo" whole target/out.txt
```

`run-flat-rest.sh <runs> <knobs> <policies> <out>` calls `java` directly, because knob names have spaces
and `+`, which `mvn.cmd` splits. The policies are `whole`, `cocked-only`, `reroll` and `count`,
comma-separated. A full run of every knob takes about 15 minutes.

To watch Throws made with a set of knobs in the tray prototype's window (keys as in the tray README; D
selects the 20-Dice set):

```
JAVA_HOME='C:\OpenJDK\jdk-27' mvn.cmd -q package exec:exec -Dtray.knobs=combo
```
