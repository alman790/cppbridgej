#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"
MVN="${MVN:-$REPO_ROOT/mvnw}"

# Use install instead of verify because the example runner starts a separate
# Maven invocation from inside cppbridge-example. Without install, Maven may try
# to resolve dev.cppbridge artifacts from Maven Central.
"$MVN" clean install
./scripts/show-build-reports.sh
./scripts/run-example.sh
