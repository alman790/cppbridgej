#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"${CXX:-c++}" -std=c++20 -O3 -pthread "$HERE/src/test/cpp/benchmark.cpp" -o "$TMP/benchmark"
(cd "$TMP" && ./benchmark)
