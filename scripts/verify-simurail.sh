#!/usr/bin/env bash
set -euo pipefail

simurail_expected_jar_sha256="d85ee304d972397807f801aae73a167163729628e382b26db0fcc0c52834e602"
simurail_expected_content_sha256="aa30b9b0a4e27ba90aedb6e4ff171a94e88c01c7f0c95ef2d57bd9a253f3d63b"
simurail_jar="${1:-}"

if [[ -z "$simurail_jar" ]]; then
  echo "Usage: $0 <simurail-jar>" >&2
  exit 2
fi
if [[ ! -f "$simurail_jar" || -L "$simurail_jar" ]]; then
  echo "Create Simurail verification target is not a regular file: $simurail_jar" >&2
  exit 1
fi
if ! command -v jar >/dev/null 2>&1; then
  echo "A Java 21 JDK providing the jar tool is required." >&2
  exit 1
fi

simurail_actual_jar_sha256="$(sha256sum "$simurail_jar" | awk '{print $1}')"
if [[ "$simurail_actual_jar_sha256" == "$simurail_expected_jar_sha256" ]]; then
  exit 0
fi

# A local build of the exact source commit can differ only in ZIP ordering and
# timestamps. Hash sorted entry names and uncompressed bytes to verify the
# executable content without weakening the source/artifact pin.
simurail_verify_dir="$(mktemp -d "${TMPDIR:-/tmp}/lasttrain-simurail-verify.XXXXXX")"
trap 'rm -rf "$simurail_verify_dir"' EXIT
(
  cd "$simurail_verify_dir"
  jar xf "$simurail_jar"
)
simurail_actual_content_sha256="$(
  (
    cd "$simurail_verify_dir"
    find . -type f -print0 |
      LC_ALL=C sort -z |
      while IFS= read -r -d '' simurail_entry; do
        printf '%s\0' "${simurail_entry#./}"
        sha256sum "$simurail_entry" | awk '{printf "%s", $1}'
        printf '\0'
      done
  ) |
    sha256sum |
    awk '{print $1}'
)"

if [[ "$simurail_actual_content_sha256" != "$simurail_expected_content_sha256" ]]; then
  echo "Create Simurail verification failed." >&2
  echo "Expected JAR SHA-256:     $simurail_expected_jar_sha256" >&2
  echo "Actual JAR SHA-256:       $simurail_actual_jar_sha256" >&2
  echo "Expected content SHA-256: $simurail_expected_content_sha256" >&2
  echo "Actual content SHA-256:   $simurail_actual_content_sha256" >&2
  exit 1
fi
