#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"
MVN="${MVN:-$REPO_ROOT/mvnw}"

# Install reactor artifacts first. The second Maven invocation runs inside the
# example module, so cppbridge-core and cppbridge-maven-plugin must already be
# available in the local Maven repository instead of being resolved from Central.
"$MVN" -pl cppbridge-example -am install
cd cppbridge-example
MAVEN_OPTS="${MAVEN_OPTS:-} --enable-native-access=ALL-UNNAMED" "$MVN" exec:java
