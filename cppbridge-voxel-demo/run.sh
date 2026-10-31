#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/mvnw" -pl cppbridge-voxel-demo -am package -DskipTests
cd "$ROOT"
exec java --enable-native-access=ALL-UNNAMED \
  -cp "$ROOT/cppbridge-voxel-demo/target/classes:$ROOT/cppbridge-core/target/classes" \
  dev.cppbridge.voxel.Stratawild "$@"
