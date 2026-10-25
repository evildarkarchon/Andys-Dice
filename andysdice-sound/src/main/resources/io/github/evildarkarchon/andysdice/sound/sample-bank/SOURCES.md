# Sources of Andy's Dice's dice sounds

Every slice in this folder is a single impact cut from a recording released under
[Creative Commons 0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) (public domain). CC0 asks for no
credit; this list gives it anyway, and records where each slice came from (ADR-0012 "Samples").

Each licence was read on the sound's own Freesound page or in the Freesound pack's `_readme_and_license.txt`,
which lists every file of the pack with its per-file URL and licence:

- [2BACH, *Dice sounds (yatzy style)*](https://freesound.org/people/2BACH/packs/11753/): d6s on felt; all 25 files
  CC0.
- [ksigloch, *Dice Set: D4 - D100*](https://freesound.org/people/ksigloch/packs/33397/): each Die Kind thrown into
  a pleather dice tray on soft fabric; all 17 files CC0.
- [Vanalosswen, *Percentile Dice Rolling*](https://freesound.org/people/Vanalosswen/sounds/572451/): percentile dice
  thrown into a felt-lined tray; CC0.
- [vartian, *Rolling Single and Dual 20-Sided Dice*](https://freesound.org/people/vartian/sounds/560085/): a plastic
  d20 on a wooden desk; CC0.
- [WallyDM, *Dice Clacking Together and Rolling into a Soft Tray*](https://freesound.org/people/WallyDM/sounds/815672/):
  two d6s clacked together in the hand; CC0.

No Sonniss GDC bundle, Dice So Nice or Owlbear Rodeo sound is used: their licences don't allow it.

## How the slices were made

Each recording was converted with ffmpeg to 48 kHz mono 16-bit PCM WAV (`-ac 1 -ar 48000`, soxr resampler). Single
hits were found from the 1 ms peak envelope: a rise of more than 15 dB above the previous 10 ms, above −45 dBFS
and 20 dB over the recording's floor. A slice runs from 1 ms before the hit's attack until it has decayed 45 dB, the
next hit starts, or 250 ms pass; it has a 1 ms fade-in and a raised-cosine fade-out of up to 10 ms, and its peak is
normalised to −1 dBFS. The raw recordings are not in the repository.

There are no recordings of a Die striking a Tray's wall, or of leather walls in particular, so the walls reuse the
floor recordings of the same material: the wooden walls are the desk's later bounces, the leather walls the
pleather tray's second, gentler bounces. Die on die is two d6s clacked together in the hand, and d6s rattled in the
hand before a throw.

The file name says the slice's category: `felt-floor`, `leather-floor`, `wood-floor`, `wood-wall`, `leather-wall`
or `die-on-die`, numbered from `01` with no gaps. The times are where the slice lies in the source recording.

| File | Source | Author | Licence | Retrieved | Cut from |
|---|---|---|---|---|---|
| `felt-floor-01.wav` | https://freesound.org/s/185963/ | 2BACH | CC0 1.0 | 2026-10-24 | `185963__2bach__dice-1-pour-on-felt.wav`, 0.149-0.201 s |
| `felt-floor-02.wav` | https://freesound.org/s/185963/ | 2BACH | CC0 1.0 | 2026-10-24 | `185963__2bach__dice-1-pour-on-felt.wav`, 1.616-1.670 s |
| `felt-floor-03.wav` | https://freesound.org/s/185963/ | 2BACH | CC0 1.0 | 2026-10-24 | `185963__2bach__dice-1-pour-on-felt.wav`, 3.192-3.243 s |
| `felt-floor-04.wav` | https://freesound.org/s/185963/ | 2BACH | CC0 1.0 | 2026-10-24 | `185963__2bach__dice-1-pour-on-felt.wav`, 4.594-4.646 s |
| `felt-floor-05.wav` | https://freesound.org/s/185963/ | 2BACH | CC0 1.0 | 2026-10-24 | `185963__2bach__dice-1-pour-on-felt.wav`, 10.188-10.225 s |
| `felt-floor-06.wav` | https://freesound.org/s/185963/ | 2BACH | CC0 1.0 | 2026-10-24 | `185963__2bach__dice-1-pour-on-felt.wav`, 12.175-12.237 s |
| `felt-floor-07.wav` | https://freesound.org/s/185967/ | 2BACH | CC0 1.0 | 2026-10-24 | `185967__2bach__dice-1-slam-on-felt.wav`, 1.466-1.532 s |
| `felt-floor-08.wav` | https://freesound.org/s/185967/ | 2BACH | CC0 1.0 | 2026-10-24 | `185967__2bach__dice-1-slam-on-felt.wav`, 2.700-2.766 s |
| `felt-floor-09.wav` | https://freesound.org/s/185977/ | 2BACH | CC0 1.0 | 2026-10-24 | `185977__2bach__dice-3-slam-on-felt.wav`, 0.172-0.230 s |
| `felt-floor-10.wav` | https://freesound.org/s/185977/ | 2BACH | CC0 1.0 | 2026-10-24 | `185977__2bach__dice-3-slam-on-felt.wav`, 1.437-1.515 s |
| `felt-floor-11.wav` | https://freesound.org/s/185977/ | 2BACH | CC0 1.0 | 2026-10-24 | `185977__2bach__dice-3-slam-on-felt.wav`, 4.598-4.663 s |
| `felt-floor-12.wav` | https://freesound.org/s/572451/ | Vanalosswen | CC0 1.0 | 2026-10-24 | `572451__vanalosswen__percentile-dice-rolling.wav`, 1.357-1.522 s |
| `leather-floor-01.wav` | https://freesound.org/s/596036/ | ksigloch | CC0 1.0 | 2026-10-24 | `596036__ksigloch__d10-single-1.wav`, 0.165-0.205 s |
| `leather-floor-02.wav` | https://freesound.org/s/596035/ | ksigloch | CC0 1.0 | 2026-10-24 | `596035__ksigloch__d10-single-2.wav`, 0.289-0.321 s |
| `leather-floor-03.wav` | https://freesound.org/s/596037/ | ksigloch | CC0 1.0 | 2026-10-24 | `596037__ksigloch__d100-single-3.wav`, 0.153-0.184 s |
| `leather-floor-04.wav` | https://freesound.org/s/596039/ | ksigloch | CC0 1.0 | 2026-10-24 | `596039__ksigloch__d100-single-1.wav`, 0.448-0.472 s |
| `leather-floor-05.wav` | https://freesound.org/s/596040/ | ksigloch | CC0 1.0 | 2026-10-24 | `596040__ksigloch__d12-single-2.wav`, 0.184-0.215 s |
| `leather-floor-06.wav` | https://freesound.org/s/596041/ | ksigloch | CC0 1.0 | 2026-10-24 | `596041__ksigloch__d12-single-1.wav`, 0.150-0.188 s |
| `leather-floor-07.wav` | https://freesound.org/s/596042/ | ksigloch | CC0 1.0 | 2026-10-24 | `596042__ksigloch__d6-single-3.wav`, 0.149-0.225 s |
| `leather-floor-08.wav` | https://freesound.org/s/596044/ | ksigloch | CC0 1.0 | 2026-10-24 | `596044__ksigloch__d6-single-1.wav`, 0.607-0.671 s |
| `leather-floor-09.wav` | https://freesound.org/s/596048/ | ksigloch | CC0 1.0 | 2026-10-24 | `596048__ksigloch__d20-single-2.wav`, 0.352-0.391 s |
| `leather-floor-10.wav` | https://freesound.org/s/596049/ | ksigloch | CC0 1.0 | 2026-10-24 | `596049__ksigloch__d20-single-1.wav`, 0.405-0.440 s |
| `leather-floor-11.wav` | https://freesound.org/s/596050/ | ksigloch | CC0 1.0 | 2026-10-24 | `596050__ksigloch__d8-single-2.wav`, 0.268-0.351 s |
| `leather-floor-12.wav` | https://freesound.org/s/596051/ | ksigloch | CC0 1.0 | 2026-10-24 | `596051__ksigloch__d8-single-1.wav`, 0.329-0.359 s |
| `leather-wall-01.wav` | https://freesound.org/s/596041/ | ksigloch | CC0 1.0 | 2026-10-24 | `596041__ksigloch__d12-single-1.wav`, 0.191-0.238 s |
| `leather-wall-02.wav` | https://freesound.org/s/596050/ | ksigloch | CC0 1.0 | 2026-10-24 | `596050__ksigloch__d8-single-2.wav`, 0.351-0.395 s |
| `leather-wall-03.wav` | https://freesound.org/s/596051/ | ksigloch | CC0 1.0 | 2026-10-24 | `596051__ksigloch__d8-single-1.wav`, 0.360-0.391 s |
| `leather-wall-04.wav` | https://freesound.org/s/596051/ | ksigloch | CC0 1.0 | 2026-10-24 | `596051__ksigloch__d8-single-1.wav`, 0.600-0.630 s |
| `leather-wall-05.wav` | https://freesound.org/s/596037/ | ksigloch | CC0 1.0 | 2026-10-24 | `596037__ksigloch__d100-single-3.wav`, 0.185-0.216 s |
| `leather-wall-06.wav` | https://freesound.org/s/596038/ | ksigloch | CC0 1.0 | 2026-10-24 | `596038__ksigloch__d100-single-2.wav`, 0.143-0.174 s |
| `leather-wall-07.wav` | https://freesound.org/s/596044/ | ksigloch | CC0 1.0 | 2026-10-24 | `596044__ksigloch__d6-single-1.wav`, 0.667-0.793 s |
| `leather-wall-08.wav` | https://freesound.org/s/596044/ | ksigloch | CC0 1.0 | 2026-10-24 | `596044__ksigloch__d6-single-1.wav`, 1.000-1.113 s |
| `leather-wall-09.wav` | https://freesound.org/s/596036/ | ksigloch | CC0 1.0 | 2026-10-24 | `596036__ksigloch__d10-single-1.wav`, 0.207-0.240 s |
| `leather-wall-10.wav` | https://freesound.org/s/596039/ | ksigloch | CC0 1.0 | 2026-10-24 | `596039__ksigloch__d100-single-1.wav`, 0.528-0.560 s |
| `wood-floor-01.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 1.172-1.249 s |
| `wood-floor-02.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 5.731-5.795 s |
| `wood-floor-03.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 11.201-11.267 s |
| `wood-floor-04.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 16.764-16.858 s |
| `wood-floor-05.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 21.632-21.725 s |
| `wood-floor-06.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 27.502-27.532 s |
| `wood-floor-07.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 32.496-32.542 s |
| `wood-floor-08.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 38.256-38.313 s |
| `wood-floor-09.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 42.269-42.321 s |
| `wood-floor-10.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 47.587-47.648 s |
| `wood-floor-11.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 53.284-53.342 s |
| `wood-floor-12.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 63.845-63.882 s |
| `wood-wall-01.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 1.250-1.318 s |
| `wood-wall-02.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 5.796-5.859 s |
| `wood-wall-03.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 11.270-11.324 s |
| `wood-wall-04.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 16.858-16.954 s |
| `wood-wall-05.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 21.725-21.788 s |
| `wood-wall-06.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 32.542-32.573 s |
| `wood-wall-07.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 38.318-38.397 s |
| `wood-wall-08.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 42.358-42.396 s |
| `wood-wall-09.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 47.648-47.710 s |
| `wood-wall-10.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 53.343-53.420 s |
| `wood-wall-11.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 58.722-58.774 s |
| `wood-wall-12.wav` | https://freesound.org/s/560085/ | vartian | CC0 1.0 | 2026-10-24 | `560085__vartian__rolling-single-and-dual-20-sided-dice.wav`, 63.882-63.940 s |
| `die-on-die-01.wav` | https://freesound.org/s/815672/ | WallyDM | CC0 1.0 | 2026-10-24 | `815672__wallydm__dice-clacking-together-and-rolling-into-a-soft-tray.wav`, 0.273-0.337 s |
| `die-on-die-02.wav` | https://freesound.org/s/815672/ | WallyDM | CC0 1.0 | 2026-10-24 | `815672__wallydm__dice-clacking-together-and-rolling-into-a-soft-tray.wav`, 0.338-0.380 s |
| `die-on-die-03.wav` | https://freesound.org/s/815672/ | WallyDM | CC0 1.0 | 2026-10-24 | `815672__wallydm__dice-clacking-together-and-rolling-into-a-soft-tray.wav`, 0.381-0.447 s |
| `die-on-die-04.wav` | https://freesound.org/s/815672/ | WallyDM | CC0 1.0 | 2026-10-24 | `815672__wallydm__dice-clacking-together-and-rolling-into-a-soft-tray.wav`, 0.515-0.541 s |
| `die-on-die-05.wav` | https://freesound.org/s/815672/ | WallyDM | CC0 1.0 | 2026-10-24 | `815672__wallydm__dice-clacking-together-and-rolling-into-a-soft-tray.wav`, 0.678-0.743 s |
| `die-on-die-06.wav` | https://freesound.org/s/815672/ | WallyDM | CC0 1.0 | 2026-10-24 | `815672__wallydm__dice-clacking-together-and-rolling-into-a-soft-tray.wav`, 0.744-0.798 s |
| `die-on-die-07.wav` | https://freesound.org/s/815672/ | WallyDM | CC0 1.0 | 2026-10-24 | `815672__wallydm__dice-clacking-together-and-rolling-into-a-soft-tray.wav`, 0.851-0.928 s |
| `die-on-die-08.wav` | https://freesound.org/s/815672/ | WallyDM | CC0 1.0 | 2026-10-24 | `815672__wallydm__dice-clacking-together-and-rolling-into-a-soft-tray.wav`, 1.074-1.105 s |
| `die-on-die-09.wav` | https://freesound.org/s/815672/ | WallyDM | CC0 1.0 | 2026-10-24 | `815672__wallydm__dice-clacking-together-and-rolling-into-a-soft-tray.wav`, 1.269-1.316 s |
| `die-on-die-10.wav` | https://freesound.org/s/596047/ | ksigloch | CC0 1.0 | 2026-10-24 | `596047__ksigloch__d6-multiple-3.wav`, 0.372-0.442 s |
| `die-on-die-11.wav` | https://freesound.org/s/596045/ | ksigloch | CC0 1.0 | 2026-10-24 | `596045__ksigloch__d6-multiple-2.wav`, 0.503-0.552 s |
| `die-on-die-12.wav` | https://freesound.org/s/596047/ | ksigloch | CC0 1.0 | 2026-10-24 | `596047__ksigloch__d6-multiple-3.wav`, 0.658-0.695 s |
