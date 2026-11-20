#!/usr/bin/env bash
# Render the HUD off-device (desktop JDK only): the renderer draws through a tiny AWT shim of android.graphics.
#   ./preview.sh [Preview|AodPreview|BlankPreview] [outdir]
# Preview writes stills of home, lock, unlock and storm/pass states at 1440x3120.
set -euo pipefail
cd "$(dirname "$0")"
MAIN=${1:-Preview}; OUT=${2:-build/preview}
rm -rf build/prev && mkdir -p build/prev "$OUT"
S=app/src/com/tokyonight/hud/live
javac -nowarn -d build/prev -sourcepath app/shim:app/src app/shim/preview/$MAIN.java \
  $S/HudRenderer.java $S/Astro.java $S/Sat.java $S/Sgp4.java $S/SpaceWx.java
if [ "$MAIN" = Preview ]; then java -cp build/prev preview.Preview "$OUT" 1440 3120
else java -cp build/prev preview.$MAIN "$OUT/$MAIN.png"; fi
echo "wrote $OUT"
