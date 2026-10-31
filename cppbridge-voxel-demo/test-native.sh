#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
COMPILER="${CXX:-c++}"
"$COMPILER" -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread \
  "$HERE/src/main/cpp/stratawild.cpp" "$HERE/src/test/cpp/smoke.cpp" \
  -o "$TMP/smoke"
(cd "$TMP" && ./smoke)
"$COMPILER" -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread \
  "$HERE/src/test/cpp/mesh.cpp" -o "$TMP/mesh"
(cd "$TMP" && ./mesh)
"$COMPILER" -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread \
  "$HERE/src/test/cpp/mesh_fuzz.cpp" -o "$TMP/mesh-fuzz"
(cd "$TMP" && ./mesh-fuzz)

"$COMPILER" -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread \
  "$HERE/src/test/cpp/world03.cpp" -o "$TMP/world03"
(cd "$TMP" && ./world03)
"$COMPILER" -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread \
  "$HERE/src/test/cpp/world04.cpp" -o "$TMP/world04"
(cd "$TMP" && ./world04)
"$COMPILER" -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread \
  "$HERE/src/test/cpp/world05.cpp" -o "$TMP/world05"
(cd "$TMP" && ./world05)

"$COMPILER" -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread \
  "$HERE/src/test/cpp/world06.cpp" -o "$TMP/world06"
(cd "$TMP" && ./world06)

"$COMPILER" -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread \
  "$HERE/src/test/cpp/world07.cpp" -o "$TMP/world07"
(cd "$TMP" && ./world07)
"$COMPILER" -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread \
  "$HERE/src/test/cpp/world08.cpp" -o "$TMP/world08"
(cd "$TMP" && ./world08)

if command -v javac >/dev/null && command -v java >/dev/null; then
  javac -d "$TMP" "$HERE/src/main/java/dev/cppbridge/voxel/FrameTimeStats.java" \
    "$HERE/src/test/java/dev/cppbridge/voxel/FrameTimeStatsTest.java"
  (cd "$TMP" && java dev.cppbridge.voxel.FrameTimeStatsTest)
else
  echo "Java frame statistics test skipped (javac/java unavailable)"
fi
