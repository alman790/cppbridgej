#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/mvnw" -pl cppbridge-voxel-demo -am install -DskipTests
"$ROOT/mvnw" -q -pl cppbridge-voxel-demo dependency:build-classpath \
  -Dmdep.outputFile="$ROOT/cppbridge-voxel-demo/target/gpu-classpath.txt"
CP="$(cat "$ROOT/cppbridge-voxel-demo/target/gpu-classpath.txt"):$ROOT/cppbridge-voxel-demo/target/classes"
ARGS=()
if [[ "$(uname -s)" == Darwin ]]; then ARGS+=("-XstartOnFirstThread"); fi
cd "$ROOT"
exec java "${ARGS[@]}" --enable-native-access=ALL-UNNAMED -cp "$CP" \
  dev.cppbridge.voxel.StratawildGL "$@"
