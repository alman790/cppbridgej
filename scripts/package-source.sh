#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Commit tracked changes before packaging release sources." >&2
  exit 1
fi

mkdir -p target
git archive --format=zip --output=target/cppbridgej-source.zip HEAD
echo "Created target/cppbridgej-source.zip"
