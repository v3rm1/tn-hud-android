#!/usr/bin/env bash
# Pure-Java checks (no Android needed): SGP4 / pass prediction, astronomy, NOAA feed parsing.
# Reference values were cross-checked against python sgp4 / skyfield and PyEphem.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/test && mkdir -p build/test
S=app/src/com/tokyonight/hud/live
javac -nowarn -d build/test $S/Sgp4.java $S/Sat.java $S/Astro.java $S/SpaceWx.java app/test/*.java
for t in SatTest AstroV20 SwTest FcTest; do echo "== $t"; java -cp build/test $t; done
