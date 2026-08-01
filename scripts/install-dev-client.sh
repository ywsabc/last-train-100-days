#!/usr/bin/env bash
set -euo pipefail

lasttrain_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
lasttrain_target_input="${1:-}"

if [[ -z "$lasttrain_target_input" ]]; then
  echo "Usage: $0 <empty-or-lasttrain-managed-prism-instance-directory>" >&2
  exit 2
fi

if [[ -e "$lasttrain_target_input" && ! -d "$lasttrain_target_input" ]]; then
  echo "Target exists and is not a directory: $lasttrain_target_input" >&2
  exit 1
fi

mkdir -p -- "$lasttrain_target_input"
lasttrain_target="$(cd -- "$lasttrain_target_input" && pwd -P)"

if [[ "$lasttrain_target" == "/" || "$lasttrain_target" == "$lasttrain_repo" ]]; then
  echo "Refusing to install a client over the repository or filesystem root." >&2
  exit 1
fi
case "$lasttrain_target/" in
  "$lasttrain_repo/pack/"*|"$lasttrain_repo/.git/"*)
    echo "Refusing to install a client over the repository, Packwiz source, or Git metadata." >&2
    exit 1
    ;;
esac

lasttrain_marker="$lasttrain_target/.lasttrain-managed-client"
lasttrain_marker_value="lasttrain-managed-client-v1"
lasttrain_new_install="false"

if [[ -L "$lasttrain_marker" ]]; then
  echo "Refusing a managed-client marker that is a symbolic link." >&2
  exit 1
fi

if [[ -f "$lasttrain_marker" ]]; then
  if [[ "$(sed -n '1p' "$lasttrain_marker")" != "$lasttrain_marker_value" ]]; then
    echo "Target has an unrecognized Last Train client marker." >&2
    exit 1
  fi
elif find "$lasttrain_target" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
  echo "Target is not empty and is not marked as a Last Train managed client." >&2
  echo "Use an empty Prism instance directory; migrate saves only after making a backup." >&2
  exit 1
fi

for lasttrain_required_file in \
  "$lasttrain_repo/prism/instance.cfg" \
  "$lasttrain_repo/prism/mmc-pack.json" \
  "$lasttrain_repo/pack/pack.toml" \
  "$lasttrain_repo/scripts/fetch-simurail.sh" \
  "$lasttrain_repo/scripts/verify-simurail.sh"; do
  if [[ ! -f "$lasttrain_required_file" ]]; then
    echo "Required project file is missing: $lasttrain_required_file" >&2
    exit 1
  fi
done

for lasttrain_command in java jar curl sha256sum awk find grep sed git date mktemp; do
  if ! command -v "$lasttrain_command" >/dev/null 2>&1; then
    echo "Required command was not found on PATH: $lasttrain_command" >&2
    exit 1
  fi
done

lasttrain_java_feature="$(
  java -XshowSettings:properties -version 2>&1 |
    awk -F= '/java.specification.version/ {gsub(/ /, "", $2); print $2}'
)"
if [[ "$lasttrain_java_feature" != "21" ]]; then
  echo "Java 21 is required; detected Java ${lasttrain_java_feature:-unknown}." >&2
  exit 1
fi

if [[ ! -f "$lasttrain_marker" ]]; then
  printf '%s\n' "$lasttrain_marker_value" > "$lasttrain_marker"
  lasttrain_new_install="true"
fi

lasttrain_game="$lasttrain_target/.minecraft"
lasttrain_cache="$lasttrain_target/.lasttrain-installer-cache"
lasttrain_backup_root="$lasttrain_cache/replaced-mods"

for lasttrain_managed_directory in \
  "$lasttrain_game" \
  "$lasttrain_game/mods" \
  "$lasttrain_cache" \
  "$lasttrain_backup_root"; do
  if [[ -L "$lasttrain_managed_directory" ]] ||
    [[ -e "$lasttrain_managed_directory" && ! -d "$lasttrain_managed_directory" ]]; then
    echo "Refusing an unsafe managed client directory: $lasttrain_managed_directory" >&2
    exit 1
  fi
done
for lasttrain_managed_file in \
  "$lasttrain_target/instance.cfg" \
  "$lasttrain_target/mmc-pack.json" \
  "$lasttrain_target/.lasttrain-install-state" \
  "$lasttrain_target/.lasttrain-install-in-progress"; do
  if [[ -L "$lasttrain_managed_file" ]] ||
    [[ -e "$lasttrain_managed_file" && ! -f "$lasttrain_managed_file" ]]; then
    echo "Refusing an unsafe managed client file: $lasttrain_managed_file" >&2
    exit 1
  fi
done

mkdir -p "$lasttrain_game/mods" "$lasttrain_cache" "$lasttrain_backup_root"

lasttrain_verify_sha256() {
  local lasttrain_expected="$1"
  local lasttrain_file="$2"
  printf '%s  %s\n' "$lasttrain_expected" "$lasttrain_file" |
    sha256sum --check --status
}

lasttrain_download_verified() {
  local lasttrain_url="$1"
  local lasttrain_expected="$2"
  local lasttrain_destination="$3"
  local lasttrain_staged

  if [[ -L "$lasttrain_destination" ]] ||
    [[ -e "$lasttrain_destination" && ! -f "$lasttrain_destination" ]]; then
    echo "Refusing an unsafe installer cache entry: $lasttrain_destination" >&2
    return 1
  fi

  if [[ -f "$lasttrain_destination" ]] &&
    lasttrain_verify_sha256 "$lasttrain_expected" "$lasttrain_destination"; then
    return
  fi

  lasttrain_staged="$(mktemp "${lasttrain_destination}.new.XXXXXXXX")"
  if ! curl -fL --retry 3 -o "$lasttrain_staged" "$lasttrain_url"; then
    rm -f -- "$lasttrain_staged"
    return 1
  fi
  if ! lasttrain_verify_sha256 "$lasttrain_expected" "$lasttrain_staged"; then
    echo "SHA-256 verification failed for: $lasttrain_url" >&2
    rm -f -- "$lasttrain_staged"
    return 1
  fi
  mv -f -- "$lasttrain_staged" "$lasttrain_destination"
}

lasttrain_copy_atomic() {
  local lasttrain_source="$1"
  local lasttrain_destination="$2"
  local lasttrain_staged

  lasttrain_staged="$(mktemp "${lasttrain_destination}.new.XXXXXXXX")"
  if ! cp -- "$lasttrain_source" "$lasttrain_staged"; then
    rm -f -- "$lasttrain_staged"
    return 1
  fi
  mv -f -- "$lasttrain_staged" "$lasttrain_destination"
}

lasttrain_progress="$lasttrain_target/.lasttrain-install-in-progress"
lasttrain_progress_staged="$(
  mktemp "$lasttrain_target/.lasttrain-install-in-progress.new.XXXXXXXX"
)"
{
  printf 'started_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'expected_minecraft=1.21.1\n'
  printf 'expected_neoforge=21.1.244\n'
} > "$lasttrain_progress_staged"
mv -f -- "$lasttrain_progress_staged" "$lasttrain_progress"

lasttrain_packwiz_bootstrap="$lasttrain_cache/packwiz-installer-bootstrap-v0.0.3.jar"
lasttrain_packwiz_main="$lasttrain_cache/packwiz-installer-v0.5.14.jar"

lasttrain_download_verified \
  "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar" \
  "a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c" \
  "$lasttrain_packwiz_bootstrap"
lasttrain_download_verified \
  "https://github.com/packwiz/packwiz-installer/releases/download/v0.5.14/packwiz-installer.jar" \
  "c9f646908d340d84773948a9a7d98bc1dae250d35e1016dc6e2b8459760b5598" \
  "$lasttrain_packwiz_main"

if [[ "$lasttrain_new_install" == "true" || ! -f "$lasttrain_target/instance.cfg" ]]; then
  lasttrain_copy_atomic \
    "$lasttrain_repo/prism/instance.cfg" \
    "$lasttrain_target/instance.cfg"
fi
lasttrain_copy_atomic \
  "$lasttrain_repo/prism/mmc-pack.json" \
  "$lasttrain_target/mmc-pack.json"

java -jar "$lasttrain_packwiz_bootstrap" \
  --bootstrap-no-update \
  --bootstrap-main-jar "$lasttrain_packwiz_main" \
  -g \
  -s client \
  --pack-folder "$lasttrain_game" \
  "$lasttrain_repo/pack/pack.toml"

(
  cd "$lasttrain_repo"
  ./gradlew clean build
)

shopt -s nullglob
lasttrain_all_core_candidates=("$lasttrain_repo"/build/libs/lasttrain-*.jar)
lasttrain_core_candidates=()
for lasttrain_candidate in "${lasttrain_all_core_candidates[@]}"; do
  case "$(basename "$lasttrain_candidate")" in
    *-sources.jar|*-javadoc.jar)
      ;;
    *)
      lasttrain_core_candidates+=("$lasttrain_candidate")
      ;;
  esac
done

if [[ "${#lasttrain_core_candidates[@]}" -ne 1 ]]; then
  echo "Expected exactly one runnable Last Train core JAR; found ${#lasttrain_core_candidates[@]}." >&2
  exit 1
fi

lasttrain_simurail_filename="simurail-1.21.1-0.0.0-a+e68481d.jar"
lasttrain_simurail_sha256="d85ee304d972397807f801aae73a167163729628e382b26db0fcc0c52834e602"
lasttrain_cached_simurail="$lasttrain_cache/$lasttrain_simurail_filename"
if [[ -L "$lasttrain_cached_simurail" ]] ||
  [[ -e "$lasttrain_cached_simurail" && ! -f "$lasttrain_cached_simurail" ]] ||
  [[ -L "${lasttrain_cached_simurail}.new" ]] ||
  [[ -e "${lasttrain_cached_simurail}.new" && ! -f "${lasttrain_cached_simurail}.new" ]]; then
  echo "Refusing an unsafe Create Simurail cache entry." >&2
  exit 1
fi
"$lasttrain_repo/scripts/fetch-simurail.sh" "$lasttrain_cached_simurail"
if ! "$lasttrain_repo/scripts/verify-simurail.sh" "$lasttrain_cached_simurail"; then
  echo "Pinned Create Simurail cache failed verification." >&2
  exit 1
fi

lasttrain_core_source="${lasttrain_core_candidates[0]}"
lasttrain_core_filename="$(basename "$lasttrain_core_source")"
lasttrain_core_staged="$(
  mktemp "$lasttrain_game/mods/$lasttrain_core_filename.new.XXXXXXXX"
)"
cp -- "$lasttrain_core_source" "$lasttrain_core_staged"
lasttrain_simurail_staged="$(
  mktemp "$lasttrain_game/mods/$lasttrain_simurail_filename.new.XXXXXXXX"
)"
cp -- "$lasttrain_cached_simurail" "$lasttrain_simurail_staged"
if ! "$lasttrain_repo/scripts/verify-simurail.sh" "$lasttrain_simurail_staged"; then
  echo "Copied Create Simurail JAR failed verification." >&2
  rm -f -- "$lasttrain_simurail_staged"
  exit 1
fi

lasttrain_backup="$(mktemp -d "$lasttrain_backup_root/update.XXXXXXXX")"
for lasttrain_old_mod in \
  "$lasttrain_game"/mods/lasttrain-*.jar \
  "$lasttrain_game"/mods/simurail-*.jar; do
  mv "$lasttrain_old_mod" "$lasttrain_backup/"
done

mv -f \
  "$lasttrain_core_staged" \
  "$lasttrain_game/mods/$lasttrain_core_filename"
mv -f \
  "$lasttrain_simurail_staged" \
  "$lasttrain_game/mods/$lasttrain_simurail_filename"

lasttrain_core_sha256="$(
  sha256sum "$lasttrain_game/mods/$lasttrain_core_filename" | awk '{print $1}'
)"
lasttrain_simurail_actual_sha256="$(
  sha256sum "$lasttrain_game/mods/$lasttrain_simurail_filename" | awk '{print $1}'
)"
lasttrain_pack_index_sha256="$(
  sha256sum "$lasttrain_repo/pack/index.toml" | awk '{print $1}'
)"
lasttrain_source_commit="$(
  git -C "$lasttrain_repo" rev-parse --verify HEAD 2>/dev/null || printf 'unknown'
)"
lasttrain_source_dirty="false"
if [[ -n "$(git -C "$lasttrain_repo" status --porcelain 2>/dev/null || true)" ]]; then
  lasttrain_source_dirty="true"
fi

lasttrain_state_staged="$(
  mktemp "$lasttrain_target/.lasttrain-install-state.new.XXXXXXXX"
)"
{
  printf 'installed_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'minecraft=1.21.1\n'
  printf 'neoforge=21.1.244\n'
  printf 'packwiz_installer=0.5.14\n'
  printf 'pack_index_sha256=%s\n' "$lasttrain_pack_index_sha256"
  printf 'core_file=%s\n' "$lasttrain_core_filename"
  printf 'core_sha256=%s\n' "$lasttrain_core_sha256"
  printf 'simurail_commit=e68481dcf56de6a020e42880792526c77b017060\n'
  printf 'simurail_sha256=%s\n' "$lasttrain_simurail_actual_sha256"
  printf 'simurail_reference_sha256=%s\n' "$lasttrain_simurail_sha256"
  printf 'source_commit=%s\n' "$lasttrain_source_commit"
  printf 'source_dirty=%s\n' "$lasttrain_source_dirty"
} > "$lasttrain_state_staged"
mv -f \
  "$lasttrain_state_staged" \
  "$lasttrain_target/.lasttrain-install-state"
rm -f -- "$lasttrain_progress"

echo
echo "Prism client instance installed in: $lasttrain_target"
echo "Minecraft game directory: $lasttrain_game"
echo "Vehicle dependency: Create Simurail alpha e68481d (pinned and SHA-256 verified)"
echo "Add or rescan this instance in Prism Launcher, review its versions, then launch it."
echo "Select a Java 21 runtime in Prism; this script does not modify launcher-wide settings."
echo "This script does not sign in, launch Minecraft, or accept any third-party terms."
