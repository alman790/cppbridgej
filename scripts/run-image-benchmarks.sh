#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"
MVN="${MVN:-$REPO_ROOT/mvnw}"

"$MVN" -pl cppbridge-benchmark -am clean package
cd cppbridge-benchmark
java -jar target/benchmarks.jar ImageBenchmarks
