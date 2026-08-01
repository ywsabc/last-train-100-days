#!/usr/bin/env bash
set -euo pipefail

lasttrain_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lasttrain_target_input="${1:-}"

if [[ -z "$lasttrain_target_input" ]]; then
  echo "Usage: $0 <empty-or-lasttrain-managed-server-directory>" >&2
  exit 2
fi

if [[ -e "$lasttrain_target_input" && ! -d "$lasttrain_target_input" ]]; then
  echo "Target exists and is not a directory: $lasttrain_target_input" >&2
  exit 1
fi
mkdir -p "$lasttrain_target_input"
lasttrain_target="$(cd "$lasttrain_target_input" && pwd)"
if [[ "$lasttrain_target" == "/" || "$lasttrain_target" == "$lasttrain_repo" ]]; then
  echo "Refusing to install a server over the filesystem or repository root." >&2
  exit 1
fi
case "$lasttrain_target/" in
  "$lasttrain_repo/"*)
    case "$lasttrain_target/" in
      "$lasttrain_repo/run/"*)
        ;;
      *)
        echo "A server inside the repository must be placed below run/." >&2
        exit 1
        ;;
    esac
    ;;
esac

lasttrain_marker="$lasttrain_target/.lasttrain-managed-server"
lasttrain_marker_value="lasttrain-managed-server-v1"
if [[ -L "$lasttrain_marker" ]]; then
  echo "Refusing a managed-server marker that is a symbolic link." >&2
  exit 1
fi
if [[ -f "$lasttrain_marker" ]]; then
  if [[ "$(sed -n '1p' "$lasttrain_marker")" != "$lasttrain_marker_value" ]]; then
    echo "Target has an unrecognized Last Train server marker." >&2
    exit 1
  fi
elif find "$lasttrain_target" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
  echo "Target is not empty and is not marked as a Last Train managed server." >&2
  echo "Use an empty directory; migrate existing servers separately after making a backup." >&2
  exit 1
fi
printf '%s\n' "$lasttrain_marker_value" > "$lasttrain_marker"

lasttrain_cache="$lasttrain_target/.lasttrain-installer-cache"
lasttrain_backup="$lasttrain_cache/replaced-mods"
mkdir -p "$lasttrain_cache" "$lasttrain_backup" "$lasttrain_target/mods"

if ! command -v java >/dev/null 2>&1; then
  echo "A Java 21 JDK is required and java was not found on PATH." >&2
  exit 1
fi
if ! command -v jar >/dev/null 2>&1; then
  echo "A Java 21 JDK providing the jar tool is required." >&2
  exit 1
fi

lasttrain_java_feature="$(java -XshowSettings:properties -version 2>&1 | awk -F= '/java.specification.version/ {gsub(/ /, "", $2); print $2}')"
if [[ "$lasttrain_java_feature" != "21" ]]; then
  echo "Java 21 is required; detected Java $lasttrain_java_feature." >&2
  exit 1
fi

lasttrain_neoforge="$lasttrain_cache/neoforge-21.1.244-installer.jar"
lasttrain_packwiz_bootstrap="$lasttrain_cache/packwiz-installer-bootstrap-v0.0.3.jar"
lasttrain_packwiz_main="$lasttrain_cache/packwiz-installer-v0.5.14.jar"

if [[ ! -f "$lasttrain_neoforge" ]]; then
  curl -fL --retry 3 \
    -o "$lasttrain_neoforge" \
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.244/neoforge-21.1.244-installer.jar"
fi
echo "ac7bea8f5c8a1d64f8787d177cc890e3b9abf67ede800f999fe05386d46fcaa8  $lasttrain_neoforge" | sha256sum --check -

if [[ ! -f "$lasttrain_packwiz_bootstrap" ]]; then
  curl -fL --retry 3 \
    -o "$lasttrain_packwiz_bootstrap" \
    "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar"
fi
echo "a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c  $lasttrain_packwiz_bootstrap" | sha256sum --check -

if [[ ! -f "$lasttrain_packwiz_main" ]]; then
  curl -fL --retry 3 \
    -o "$lasttrain_packwiz_main" \
    "https://github.com/packwiz/packwiz-installer/releases/download/v0.5.14/packwiz-installer.jar"
fi
echo "c9f646908d340d84773948a9a7d98bc1dae250d35e1016dc6e2b8459760b5598  $lasttrain_packwiz_main" | sha256sum --check -

(
  cd "$lasttrain_target"
  java -jar "$lasttrain_neoforge" --installServer
)

java -jar "$lasttrain_packwiz_bootstrap" \
  --bootstrap-no-update \
  --bootstrap-main-jar "$lasttrain_packwiz_main" \
  -g \
  -s server \
  --pack-folder "$lasttrain_target" \
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

for lasttrain_old_core in "$lasttrain_target"/mods/lasttrain-*.jar; do
  mv "$lasttrain_old_core" "$lasttrain_backup/$(basename "$lasttrain_old_core").$(date +%s)"
done
for lasttrain_old_adapter in "$lasttrain_target"/mods/create_ferronautics-*.jar; do
  mv "$lasttrain_old_adapter" "$lasttrain_backup/$(basename "$lasttrain_old_adapter").$(date +%s)"
done
for lasttrain_old_taczjs in "$lasttrain_target"/mods/taczjs-*.jar; do
  mv "$lasttrain_old_taczjs" "$lasttrain_backup/$(basename "$lasttrain_old_taczjs").$(date +%s)"
done
for lasttrain_old_simurail in "$lasttrain_target"/mods/simurail-*.jar; do
  mv "$lasttrain_old_simurail" "$lasttrain_backup/$(basename "$lasttrain_old_simurail").$(date +%s)"
done

cp "${lasttrain_core_candidates[0]}" "$lasttrain_target/mods/"
"$lasttrain_repo/scripts/fetch-simurail.sh" \
  "$lasttrain_target/mods/simurail-1.21.1-0.0.0-a+e68481d.jar"

echo
echo "Server files installed in: $lasttrain_target"
echo "Vehicle dependency: Create Simurail alpha e68481d (pinned and SHA-256 verified)"
echo "Review eula.txt, set eula=true only if you accept Mojang's EULA, then run run.sh."
