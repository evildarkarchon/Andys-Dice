# Andy's Dice for Windows x64

A desktop dice roller: type a Roll Expression such as `4d6kh3`, `1d20+5 adv` or `8d6`, press Enter, and watch
real-looking Dice tumble through a Tray, clatter and come to rest. The Roll Result always matches the Numerals
showing.

## Install, run and uninstall

1. Download `AndysDice-<version>-windows-x64.zip` from this release and unzip it anywhere you can write to, such as
   your Documents or Desktop folder. It holds one folder, `AndysDice`.
2. Run `AndysDice\AndysDice.exe`. Nothing is installed: Java is bundled inside the folder.
3. To uninstall, delete the `AndysDice` folder.

Your settings, Saved Rolls, Roll History and log are kept in `AndysDice\data\`. If that folder can't be written
(for example, when the app is unzipped into `Program Files`), they go to `%LOCALAPPDATA%\AndysDice` instead.

**Needs:** 64-bit Windows and a graphics driver with OpenGL 3.3 core. Without it, the app shows a panel naming
the OpenGL it found instead of the Tray.

## Known limitations: the app is not signed

Andy's Dice is not code-signed. Expect Windows to warn you the first time you run it:

- **SmartScreen** shows "Windows protected your PC" on the first launch. Click **More info**, then **Run anyway**.
- **Smart App Control:** on a Windows 11 PC where Smart App Control is turned on (enforcing), Windows blocks
  unsigned apps outright, and there is no per-app exception. Andy's Dice can't run on such a PC.

## Licence

Andy's Dice is MIT-licensed. The licences of everything it bundles (JavaFX, LWJGL, Jolt Physics and Jolt JNI,
JOML and the Java runtime) and the sources of its CC0 dice sounds are in `AndysDice\app\legal\`, indexed by
`THIRD-PARTY-NOTICES.md`; the Java runtime's and JavaFX's own notices are in `AndysDice\runtime\legal\`.
