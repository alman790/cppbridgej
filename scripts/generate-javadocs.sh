#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR"

MVN="${MVN:-$ROOT_DIR/mvnw}"
JAVADOC_PLUGIN="javadoc:javadoc"

CORE_INDEX=""
PLUGIN_INDEX=""
CORE_CANDIDATES=(
  "cppbridge-core/target/reports/apidocs/index.html"
  "cppbridge-core/target/site/apidocs/index.html"
)
PLUGIN_CANDIDATES=(
  "cppbridge-maven-plugin/target/reports/apidocs/index.html"
  "cppbridge-maven-plugin/target/site/apidocs/index.html"
)

printf 'Generating JavaDoc for cppbridge-core...\n'
"$MVN" -q -pl cppbridge-core -DskipTests "$JAVADOC_PLUGIN"

printf 'Generating JavaDoc for cppbridge-maven-plugin...\n'
"$MVN" -q -pl cppbridge-maven-plugin -DskipTests "$JAVADOC_PLUGIN"

missing=0

for candidate in "${CORE_CANDIDATES[@]}"; do
  if [[ -f "$candidate" ]]; then
    CORE_INDEX="$candidate"
    break
  fi
done

for candidate in "${PLUGIN_CANDIDATES[@]}"; do
  if [[ -f "$candidate" ]]; then
    PLUGIN_INDEX="$candidate"
    break
  fi
done

if [[ -z "$CORE_INDEX" ]]; then
  printf 'ERROR: core JavaDoc index.html was not generated. Checked:\n' >&2
  printf '%s\n' "${CORE_CANDIDATES[@]/#/- }" >&2
  missing=1
fi

if [[ -z "$PLUGIN_INDEX" ]]; then
  printf 'ERROR: Maven plugin JavaDoc index.html was not generated. Checked:\n' >&2
  printf '%s\n' "${PLUGIN_CANDIDATES[@]/#/- }" >&2
  missing=1
fi

if [[ "$missing" -ne 0 ]]; then
  printf '\nExisting JavaDoc index files under target directories:\n' >&2
  find . -path '*/target/*' -name index.html -print >&2 || true
  exit 1
fi

printf '\nJavaDoc generated successfully:\n'
printf '%s\n' "- Core API: $CORE_INDEX"
printf '%s\n' "- Maven plugin API: $PLUGIN_INDEX"
