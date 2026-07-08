#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

OUT="target/cppbridgej-source.zip"
mkdir -p target
rm -f "$OUT"
zip -qr "$OUT" . \
  -x "target/*" \
  -x "*/target/*" \
  -x ".git/*" \
  -x ".idea/*" \
  -x ".vscode/*" \
  -x "*.DS_Store"
echo "Created $OUT"
