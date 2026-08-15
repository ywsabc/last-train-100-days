#!/usr/bin/env bash
set -euo pipefail

# Create Simurail is intentionally unreleased. This script locks one successful
# upstream CI artifact by commit, artifact ID and both archive/JAR SHA-256.
simurail_commit="e68481dcf56de6a020e42880792526c77b017060"
simurail_short_commit="e68481d"
simurail_artifact_id="8738923712"
simurail_archive_sha256="6e49ab6573d027456e8f935ec9c3b29a665ff31feed612ce5b6256c0c9bbabd4"
simurail_jar_sha256="d85ee304d972397807f801aae73a167163729628e382b26db0fcc0c52834e602"
simurail_source_sha256="8417fadd7f6a5afa7322e191326f423344d68937f724ebf684485d02e8de9144"
simurail_gradle_distribution_sha256="bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
simurail_filename="simurail-1.21.1-0.0.0-a+e68481d.jar"
simurail_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

simurail_output="${1:-}"
if [[ -z "$simurail_output" ]]; then
  echo "Usage: $0 <output-jar>" >&2
  exit 2
fi

simurail_output_parent="$(dirname "$simurail_output")"
if [[ -L "$simurail_output_parent" ]] ||
  [[ -e "$simurail_output_parent" && ! -d "$simurail_output_parent" ]]; then
  echo "Refusing an unsafe Create Simurail output directory: $simurail_output_parent" >&2
  exit 1
fi
mkdir -p -- "$simurail_output_parent"
if [[ -L "$simurail_output" ]] ||
  [[ -e "$simurail_output" && ! -f "$simurail_output" ]]; then
  echo "Refusing an unsafe Create Simurail output path: $simurail_output" >&2
  exit 1
fi

if [[ -f "$simurail_output" ]] \
  && "$simurail_repo/scripts/verify-simurail.sh" "$simurail_output"; then
  echo "Create Simurail $simurail_short_commit is already verified: $simurail_output"
  exit 0
fi

simurail_work="$(mktemp -d "${TMPDIR:-/tmp}/lasttrain-simurail.XXXXXX")"
simurail_staged=""
simurail_cleanup() {
  rm -rf -- "$simurail_work"
  if [[ -n "$simurail_staged" ]]; then
    rm -f -- "$simurail_staged"
  fi
}
trap simurail_cleanup EXIT

simurail_downloaded_jar="$simurail_work/$simurail_filename"
simurail_artifact_zip="$simurail_work/simurail-artifact.zip"

simurail_from_artifact="false"
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  echo "Downloading unreleased Create Simurail artifact $simurail_artifact_id..."
  if gh api \
      "repos/Crystaelix/Create-Simurail/actions/artifacts/$simurail_artifact_id/zip" \
      > "$simurail_artifact_zip" &&
    printf '%s  %s\n' "$simurail_archive_sha256" "$simurail_artifact_zip" |
      sha256sum --check --status &&
    (
      cd "$simurail_work"
      jar xf "$simurail_artifact_zip"
    ) &&
    [[ -f "$simurail_downloaded_jar" ]] &&
    "$simurail_repo/scripts/verify-simurail.sh" "$simurail_downloaded_jar"; then
    simurail_from_artifact="true"
  else
    echo "Pinned GitHub artifact is unavailable or invalid; falling back to source." >&2
    rm -f -- "$simurail_downloaded_jar" "$simurail_artifact_zip"
  fi
fi

if [[ "$simurail_from_artifact" != "true" ]]; then
  echo "Building pinned Create Simurail source." >&2
  for simurail_command in curl sha256sum tar; do
    if ! command -v "$simurail_command" >/dev/null 2>&1; then
      echo "Required source-build command was not found on PATH: $simurail_command" >&2
      exit 1
    fi
  done
  simurail_source="$simurail_work/simurail-source.tar.gz"
  curl -fL --retry 3 \
    -o "$simurail_source" \
    "https://codeload.github.com/Crystaelix/Create-Simurail/tar.gz/$simurail_commit"
  echo "$simurail_source_sha256  $simurail_source" | sha256sum --check -
  simurail_source_dir="$simurail_work/source"
  mkdir -p "$simurail_source_dir"
  tar -xzf "$simurail_source" -C "$simurail_source_dir" --strip-components=1
  simurail_wrapper_properties="$simurail_source_dir/gradle/wrapper/gradle-wrapper.properties"
  if [[ ! -f "$simurail_wrapper_properties" ]]; then
    echo "Pinned Simurail source is missing Gradle wrapper properties." >&2
    exit 1
  fi
  if grep -qx \
    'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip' \
    "$simurail_wrapper_properties"; then
    sed -i 's#services.gradle.org#downloads.gradle.org#' \
      "$simurail_wrapper_properties"
  elif ! grep -qx \
    'distributionUrl=https\\://downloads.gradle.org/distributions/gradle-8.14.3-bin.zip' \
    "$simurail_wrapper_properties"; then
    echo "Pinned Simurail source declares an unexpected Gradle distribution URL." >&2
    exit 1
  fi
  if grep -q '^distributionSha256Sum=' "$simurail_wrapper_properties"; then
    if ! grep -qx \
      "distributionSha256Sum=$simurail_gradle_distribution_sha256" \
      "$simurail_wrapper_properties"; then
      echo "Pinned Simurail source declares an unexpected Gradle distribution hash." >&2
      exit 1
    fi
  else
    printf '\ndistributionSha256Sum=%s\n' \
      "$simurail_gradle_distribution_sha256" >> "$simurail_wrapper_properties"
  fi
  chmod +x "$simurail_source_dir/gradlew"
  (
    cd "$simurail_source_dir"
    ./gradlew --no-daemon clean assemble -PcommitHash="$simurail_short_commit"
  )
  cp "$simurail_source_dir/build/libs/$simurail_filename" "$simurail_downloaded_jar"
fi

if [[ ! -f "$simurail_downloaded_jar" ]]; then
  echo "Pinned Create Simurail artifact did not contain $simurail_filename." >&2
  exit 1
fi
"$simurail_repo/scripts/verify-simurail.sh" "$simurail_downloaded_jar"

simurail_staged="$(
  mktemp "$simurail_output_parent/.$(basename "$simurail_output").new.XXXXXXXX"
)"
cp -- "$simurail_downloaded_jar" "$simurail_staged"
"$simurail_repo/scripts/verify-simurail.sh" "$simurail_staged"
mv -f -- "$simurail_staged" "$simurail_output"
simurail_staged=""
echo "Installed Create Simurail $simurail_short_commit: $simurail_output"
