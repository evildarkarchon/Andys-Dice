#!/usr/bin/env bash
# PROTOTYPE. Runs the issue #21 measurements headless after `mvn.cmd -q package` (see README.md).
# Calls java directly rather than exec:exec because knob names contain spaces and '+', which mvn.cmd
# (a batch file) splits. Usage: run-flat-rest.sh <runs> <knobs> <policies> <out>
set -euo pipefail
cd "$(dirname "$0")"
JAVA="${JAVA:-/c/OpenJDK/jdk-27/bin/java}"
"$JAVA" --enable-native-access=ALL-UNNAMED -Djoml.nounsafe=true -Xmx2g \
  -Dtray.natives=target/natives/windows/x86-64/com/github/stephengold/joltjni.dll \
  -Dflat.runs="$1" -Dflat.knobs="$2" -Dflat.policies="$3" -Dflat.out="$4" \
  -cp "target/classes;target/lib/*" andysdice.prototype.tray.FlatRest
