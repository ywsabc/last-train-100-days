#!/usr/bin/env bash
set -euo pipefail

lasttrain_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
lasttrain_target_input="${1:-}"

if [[ -z "$lasttrain_target_input" ]]; then
  echo "Usage: $0 <empty-or-lasttrain-managed-server-directory>" >&2
  exit 2
fi

for lasttrain_required_file in \
  "$lasttrain_repo/pack/pack.toml" \
  "$lasttrain_repo/pack/index.toml"; do
  if [[ ! -f "$lasttrain_required_file" ]]; then
    echo "Required project file is missing: $lasttrain_required_file" >&2
    exit 1
  fi
done
for lasttrain_required_executable in \
  "$lasttrain_repo/scripts/fetch-simurail.sh" \
  "$lasttrain_repo/scripts/verify-simurail.sh" \
  "$lasttrain_repo/gradlew"; do
  if [[ ! -f "$lasttrain_required_executable" || ! -x "$lasttrain_required_executable" ]]; then
    echo "Required project executable is missing or not executable: $lasttrain_required_executable" >&2
    exit 1
  fi
done

for lasttrain_command in \
  java jar curl sha256sum awk find grep sed sort dirname date mktemp flock tar chmod cp mv rm mkdir basename; do
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

if [[ -e "$lasttrain_target_input" && ! -d "$lasttrain_target_input" ]]; then
  echo "Target exists and is not a directory: $lasttrain_target_input" >&2
  exit 1
fi
mkdir -p -- "$lasttrain_target_input"
lasttrain_target="$(cd -- "$lasttrain_target_input" && pwd -P)"
if [[ "$lasttrain_target" == "/" || "$lasttrain_target" == "$lasttrain_repo" ||
  "$lasttrain_target" == "$lasttrain_repo/run" ]]; then
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
lasttrain_lock="$lasttrain_target/.lasttrain-install.lock"
lasttrain_marker_value="lasttrain-managed-server-v1"
lasttrain_new_install="false"
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
  echo "Use an empty directory; migrate existing worlds only after making a backup." >&2
  exit 1
else
  lasttrain_new_install="true"
fi

lasttrain_cache="$lasttrain_target/.lasttrain-installer-cache"
lasttrain_backup_root="$lasttrain_cache/replaced-mods"
for lasttrain_managed_directory in \
  "$lasttrain_cache" \
  "$lasttrain_backup_root" \
  "$lasttrain_target/mods" \
  "$lasttrain_target/config" \
  "$lasttrain_target/defaultconfigs" \
  "$lasttrain_target/kubejs" \
  "$lasttrain_target/libraries"; do
  if [[ -L "$lasttrain_managed_directory" ]] ||
    [[ -e "$lasttrain_managed_directory" && ! -d "$lasttrain_managed_directory" ]]; then
    echo "Refusing an unsafe managed server directory: $lasttrain_managed_directory" >&2
    exit 1
  fi
done
for lasttrain_managed_file in \
  "$lasttrain_marker" \
  "$lasttrain_lock" \
  "$lasttrain_target/.lasttrain-install-state" \
  "$lasttrain_target/.lasttrain-install-in-progress" \
  "$lasttrain_target/run.sh" \
  "$lasttrain_target/run.bat" \
  "$lasttrain_target/user_jvm_args.txt" \
  "$lasttrain_target/neoforge-21.1.244-installer.jar.log" \
  "$lasttrain_target/packwiz.json"; do
  if [[ -L "$lasttrain_managed_file" ]] ||
    [[ -e "$lasttrain_managed_file" && ! -f "$lasttrain_managed_file" ]]; then
    echo "Refusing an unsafe managed server file: $lasttrain_managed_file" >&2
    exit 1
  fi
done

if [[ "$lasttrain_new_install" == "true" ]]; then
  lasttrain_marker_staged="$(mktemp "$lasttrain_target/.lasttrain-managed-server.new.XXXXXXXX")"
  printf '%s\n' "$lasttrain_marker_value" > "$lasttrain_marker_staged"
  mv -f -- "$lasttrain_marker_staged" "$lasttrain_marker"
fi

exec {lasttrain_lock_fd}> "$lasttrain_lock"
if ! flock -n "$lasttrain_lock_fd"; then
  echo "Another Last Train installation is already running for: $lasttrain_target" >&2
  exit 1
fi

lasttrain_tree_has_no_symlinks() {
  local lasttrain_tree="$1"
  local lasttrain_symlink
  if ! lasttrain_symlink="$(
    find -P "$lasttrain_tree" -type l -print -quit
  )"; then
    return 1
  fi
  [[ -z "$lasttrain_symlink" ]]
}

for lasttrain_managed_tree in \
  "$lasttrain_cache" \
  "$lasttrain_target/mods" \
  "$lasttrain_target/config" \
  "$lasttrain_target/defaultconfigs" \
  "$lasttrain_target/kubejs" \
  "$lasttrain_target/libraries"; do
  if [[ -d "$lasttrain_managed_tree" ]] &&
    ! lasttrain_tree_has_no_symlinks "$lasttrain_managed_tree"; then
    echo "Refusing an unreadable managed tree or symbolic link inside it: $lasttrain_managed_tree" >&2
    exit 1
  fi
done

mkdir -p -- "$lasttrain_cache" "$lasttrain_backup_root" "$lasttrain_target/mods"

lasttrain_progress="$lasttrain_target/.lasttrain-install-in-progress"
lasttrain_managed_relative_paths=(
  mods
  config
  defaultconfigs
  kubejs
  libraries
  run.sh
  run.bat
  user_jvm_args.txt
  neoforge-21.1.244-installer.jar.log
  packwiz.json
  .lasttrain-install-state
)
lasttrain_snapshot_sentinel=".lasttrain-managed-snapshot-v1"

lasttrain_is_safe_snapshot_path() {
  local lasttrain_snapshot="$1"
  local lasttrain_snapshot_name
  lasttrain_snapshot_name="$(basename -- "$lasttrain_snapshot")"
  [[ "$lasttrain_snapshot" == "$lasttrain_cache/$lasttrain_snapshot_name" ]] &&
    [[ "$lasttrain_snapshot_name" =~ ^managed-snapshot\.[[:alnum:]]{8}$ ]]
}

lasttrain_is_complete_snapshot() {
  local lasttrain_snapshot="$1"
  lasttrain_is_safe_snapshot_path "$lasttrain_snapshot" &&
    [[ -d "$lasttrain_snapshot" && ! -L "$lasttrain_snapshot" ]] &&
    [[ -f "$lasttrain_snapshot/$lasttrain_snapshot_sentinel" &&
      ! -L "$lasttrain_snapshot/$lasttrain_snapshot_sentinel" ]] &&
    grep -qx 'lasttrain-managed-snapshot-v1' \
      "$lasttrain_snapshot/$lasttrain_snapshot_sentinel" &&
    lasttrain_tree_has_no_symlinks "$lasttrain_snapshot"
}

lasttrain_detach_snapshot_from_existing_progress() {
  local lasttrain_progress_staged
  lasttrain_progress_staged="$(
    mktemp "$lasttrain_target/.lasttrain-install-in-progress.recovered.XXXXXXXX"
  )"
  if ! sed \
      -e '/^managed_snapshot=/d' \
      -e '/^managed_transaction_active=/d' \
      "$lasttrain_progress" > "$lasttrain_progress_staged"; then
    rm -f -- "$lasttrain_progress_staged"
    return 1
  fi
  {
    printf 'managed_snapshot_recovered_at_utc=%s\n' \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >> "$lasttrain_progress_staged"
  if ! mv -f -- "$lasttrain_progress_staged" "$lasttrain_progress"; then
    rm -f -- "$lasttrain_progress_staged"
    return 1
  fi
}

lasttrain_restore_managed_snapshot() {
  local lasttrain_snapshot="$1"
  local lasttrain_relative
  local lasttrain_live_path
  local lasttrain_saved_path
  local lasttrain_restore_status=0

  if ! lasttrain_is_complete_snapshot "$lasttrain_snapshot"; then
    echo "Refusing an incomplete or unsafe managed-server snapshot: $lasttrain_snapshot" >&2
    return 1
  fi

  for lasttrain_relative in "${lasttrain_managed_relative_paths[@]}"; do
    lasttrain_live_path="$lasttrain_target/$lasttrain_relative"
    lasttrain_saved_path="$lasttrain_snapshot/$lasttrain_relative"
    if ! rm -rf -- "$lasttrain_live_path"; then
      lasttrain_restore_status=1
      continue
    fi
    if [[ -e "$lasttrain_saved_path" ]]; then
      # Keep the snapshot complete so a failed or interrupted restore can be
      # retried safely by the next installer run.
      if ! cp -a -- "$lasttrain_saved_path" "$lasttrain_live_path"; then
        lasttrain_restore_status=1
      fi
    fi
  done
  return "$lasttrain_restore_status"
}

# A kill -9 or host crash cannot run EXIT traps. If the prior transaction left
# its private snapshot behind, restore it before starting another mutation.
if [[ -f "$lasttrain_progress" && ! -L "$lasttrain_progress" ]]; then
  lasttrain_stale_snapshot="$(
    sed -n 's/^managed_snapshot=//p' "$lasttrain_progress" | sed -n '1p'
  )"
  lasttrain_stale_transaction_active="$(
    sed -n 's/^managed_transaction_active=//p' "$lasttrain_progress" | sed -n '1p'
  )"
  if [[ -n "$lasttrain_stale_snapshot" ||
    "$lasttrain_stale_transaction_active" == "true" ]]; then
    if [[ "$lasttrain_stale_transaction_active" != "true" ]] ||
      ! lasttrain_is_complete_snapshot "$lasttrain_stale_snapshot"; then
      echo "Refusing to continue: active install progress references an unsafe or missing snapshot." >&2
      exit 1
    fi
    echo "Recovering the managed server trees from an interrupted transaction." >&2
    lasttrain_restore_managed_snapshot "$lasttrain_stale_snapshot"
    # Atomically remove the recovery pointer before recursively deleting the
    # snapshot. A kill during deletion can then leave only a harmless orphan.
    lasttrain_detach_snapshot_from_existing_progress
    if ! rm -rf -- "$lasttrain_stale_snapshot"; then
      echo "Warning: recovered snapshot could not be fully removed: $lasttrain_stale_snapshot" >&2
    fi
  fi
fi

# With the target lock held, any immediate-child snapshot not referenced by an
# active progress record can only be an orphan from a stop before activation or
# after the recovery pointer was detached. Recursive cleanup is now harmless
# even if it is interrupted again.
while IFS= read -r -d '' lasttrain_orphan_snapshot; do
  if lasttrain_is_safe_snapshot_path "$lasttrain_orphan_snapshot"; then
    rm -rf -- "$lasttrain_orphan_snapshot"
  fi
done < <(
  find -P "$lasttrain_cache" \
    -mindepth 1 \
    -maxdepth 1 \
    -type d \
    -name 'managed-snapshot.????????' \
    -print0
)

lasttrain_started_at_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
lasttrain_transaction_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
lasttrain_previous_state_sha256="none"
if [[ -f "$lasttrain_target/.lasttrain-install-state" ]]; then
  lasttrain_previous_state_sha256="$(
    sha256sum -- "$lasttrain_target/.lasttrain-install-state" | awk '{print $1}'
  )"
fi
lasttrain_phase="initializing"
lasttrain_backup=""
lasttrain_swap_active="false"
lasttrain_new_core_target=""
lasttrain_new_simurail_target=""
lasttrain_managed_snapshot=""
lasttrain_managed_transaction_active="false"
lasttrain_rollback_attempted="false"
lasttrain_rollback_succeeded="false"
lasttrain_cleanup_paths=()

lasttrain_track_cleanup() {
  lasttrain_cleanup_paths+=("$1")
}

lasttrain_write_progress() {
  lasttrain_phase="$1"
  local lasttrain_progress_transaction_active="${2:-$lasttrain_managed_transaction_active}"
  local lasttrain_progress_staged
  lasttrain_progress_staged="$(
    mktemp "$lasttrain_target/.lasttrain-install-in-progress.new.XXXXXXXX"
  )"
  lasttrain_track_cleanup "$lasttrain_progress_staged"
  {
    printf 'transaction_id=%s\n' "$lasttrain_transaction_id"
    printf 'started_at_utc=%s\n' "$lasttrain_started_at_utc"
    printf 'phase=%s\n' "$lasttrain_phase"
    printf 'expected_minecraft=1.21.1\n'
    printf 'expected_neoforge=21.1.244\n'
    printf 'previous_state_sha256=%s\n' "$lasttrain_previous_state_sha256"
    if [[ -n "$lasttrain_backup" ]]; then
      printf 'backup_directory=%s\n' "$lasttrain_backup"
    fi
    if [[ -n "$lasttrain_managed_snapshot" ]]; then
      printf 'managed_snapshot=%s\n' "$lasttrain_managed_snapshot"
      printf 'managed_transaction_active=%s\n' "$lasttrain_progress_transaction_active"
    fi
  } > "$lasttrain_progress_staged"
  mv -f -- "$lasttrain_progress_staged" "$lasttrain_progress"
}

lasttrain_on_exit() {
  local lasttrain_exit_status=$?
  local lasttrain_failed_phase="$lasttrain_phase"
  local lasttrain_snapshot_to_cleanup
  trap - EXIT
  set +e

  if [[ "$lasttrain_exit_status" -ne 0 &&
    "$lasttrain_managed_transaction_active" == "true" ]]; then
    lasttrain_rollback_attempted="true"
    if lasttrain_restore_managed_snapshot "$lasttrain_managed_snapshot"; then
      lasttrain_snapshot_to_cleanup="$lasttrain_managed_snapshot"
      lasttrain_managed_snapshot=""
      lasttrain_managed_transaction_active="false"
      if lasttrain_write_progress rollback_restored; then
        lasttrain_rollback_succeeded="true"
        if ! rm -rf -- "$lasttrain_snapshot_to_cleanup"; then
          echo "Warning: restored snapshot could not be fully removed: $lasttrain_snapshot_to_cleanup" >&2
        fi
      else
        # The old progress still points to the complete snapshot, so retain it
        # and let the next run repeat the idempotent restore.
        lasttrain_managed_snapshot="$lasttrain_snapshot_to_cleanup"
        lasttrain_managed_transaction_active="true"
        echo "Rollback restored live files but could not detach its recovery pointer." >&2
      fi
      if [[ "$lasttrain_rollback_succeeded" == "true" ]]; then
        lasttrain_managed_snapshot=""
      fi
    else
      echo "Managed-server rollback was incomplete; retained snapshot: $lasttrain_managed_snapshot" >&2
    fi
  elif [[ "$lasttrain_exit_status" -ne 0 && "$lasttrain_swap_active" == "true" ]]; then
    lasttrain_rollback_attempted="true"
    [[ -n "$lasttrain_new_core_target" ]] && rm -f -- "$lasttrain_new_core_target"
    [[ -n "$lasttrain_new_simurail_target" ]] && rm -f -- "$lasttrain_new_simurail_target"
    if [[ -n "$lasttrain_backup" && -d "$lasttrain_backup" ]]; then
      while IFS= read -r -d '' lasttrain_backup_file; do
        mv -f -- \
          "$lasttrain_backup_file" \
          "$lasttrain_target/mods/$(basename "$lasttrain_backup_file")"
      done < <(find -P "$lasttrain_backup" -mindepth 1 -maxdepth 1 -type f -print0)
    fi
    lasttrain_rollback_succeeded="true"
  fi

  for lasttrain_cleanup_path in "${lasttrain_cleanup_paths[@]}"; do
    [[ -n "$lasttrain_cleanup_path" ]] && rm -f -- "$lasttrain_cleanup_path"
  done

  if [[ "$lasttrain_exit_status" -ne 0 && -f "$lasttrain_progress" &&
    ! -L "$lasttrain_progress" ]]; then
    {
      printf 'failed_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'failed_phase=%s\n' "$lasttrain_failed_phase"
      printf 'failed_exit=%s\n' "$lasttrain_exit_status"
      if [[ "$lasttrain_rollback_attempted" == "true" ]]; then
        printf 'rollback_attempted=true\n'
        printf 'rollback_succeeded=%s\n' "$lasttrain_rollback_succeeded"
      fi
    } >> "$lasttrain_progress"
  fi

  exit "$lasttrain_exit_status"
}
trap lasttrain_on_exit EXIT

lasttrain_verify_sha256() {
  local lasttrain_expected="$1"
  local lasttrain_file="$2"
  printf '%s  %s\n' "$lasttrain_expected" "$lasttrain_file" |
    sha256sum --check --status
}

lasttrain_tree_sha256() {
  local lasttrain_tree="$1"
  local lasttrain_manifest
  local lasttrain_digest
  lasttrain_manifest="$(mktemp "$lasttrain_cache/tree-hash.XXXXXXXX")"
  lasttrain_track_cleanup "$lasttrain_manifest"
  if ! (
    cd "$lasttrain_tree" || exit 1
    find -P . -type f -print0 |
      sort -z |
      while IFS= read -r -d '' lasttrain_tree_file; do
        printf '%s\0' "$lasttrain_tree_file"
        sha256sum -- "$lasttrain_tree_file" || exit 1
      done
  ) > "$lasttrain_manifest"; then
    rm -f -- "$lasttrain_manifest"
    return 1
  fi
  if ! lasttrain_digest="$(
    sha256sum -- "$lasttrain_manifest" | awk '{print $1}'
  )"; then
    rm -f -- "$lasttrain_manifest"
    return 1
  fi
  rm -f -- "$lasttrain_manifest"
  printf '%s\n' "$lasttrain_digest"
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
  lasttrain_track_cleanup "$lasttrain_staged"
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

lasttrain_write_progress downloads

lasttrain_neoforge="$lasttrain_cache/neoforge-21.1.244-installer.jar"
lasttrain_packwiz_bootstrap="$lasttrain_cache/packwiz-installer-bootstrap-v0.0.3.jar"
lasttrain_packwiz_main="$lasttrain_cache/packwiz-installer-v0.5.14.jar"
lasttrain_download_verified \
  "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.244/neoforge-21.1.244-installer.jar" \
  "ac7bea8f5c8a1d64f8787d177cc890e3b9abf67ede800f999fe05386d46fcaa8" \
  "$lasttrain_neoforge"
lasttrain_download_verified \
  "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar" \
  "a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c" \
  "$lasttrain_packwiz_bootstrap"
lasttrain_download_verified \
  "https://github.com/packwiz/packwiz-installer/releases/download/v0.5.14/packwiz-installer.jar" \
  "c9f646908d340d84773948a9a7d98bc1dae250d35e1016dc6e2b8459760b5598" \
  "$lasttrain_packwiz_main"

lasttrain_write_progress core_build
mkdir -p -- "$lasttrain_repo/.gradle"
exec {lasttrain_repo_build_lock_fd}> "$lasttrain_repo/.gradle/lasttrain-installer-build.lock"
flock "$lasttrain_repo_build_lock_fd"
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
lasttrain_core_filename="$(basename "${lasttrain_core_candidates[0]}")"
lasttrain_core_source="$(
  mktemp "$lasttrain_cache/$lasttrain_core_filename.build.XXXXXXXX"
)"
lasttrain_track_cleanup "$lasttrain_core_source"
cp -- "${lasttrain_core_candidates[0]}" "$lasttrain_core_source"
flock -u "$lasttrain_repo_build_lock_fd"
exec {lasttrain_repo_build_lock_fd}>&-

lasttrain_write_progress simurail
lasttrain_simurail_filename="simurail-1.21.1-0.0.0-a+e68481d.jar"
lasttrain_cached_simurail="$lasttrain_cache/$lasttrain_simurail_filename"
if [[ -L "$lasttrain_cached_simurail" ]] ||
  [[ -e "$lasttrain_cached_simurail" && ! -f "$lasttrain_cached_simurail" ]] ||
  [[ -L "${lasttrain_cached_simurail}.new" ]] ||
  [[ -e "${lasttrain_cached_simurail}.new" && ! -f "${lasttrain_cached_simurail}.new" ]]; then
  echo "Refusing an unsafe Create Simurail cache entry." >&2
  exit 1
fi
"$lasttrain_repo/scripts/fetch-simurail.sh" "$lasttrain_cached_simurail"
"$lasttrain_repo/scripts/verify-simurail.sh" "$lasttrain_cached_simurail"

# NeoForge and Packwiz both update several live trees. Snapshot every tree they
# own before either tool runs so a download error, JVM crash, or failed
# postcheck restores the previously runnable server as one unit. Worlds,
# server.properties and EULA state are intentionally outside this transaction.
lasttrain_managed_snapshot="$(
  mktemp -d "$lasttrain_cache/managed-snapshot.XXXXXXXX"
)"
for lasttrain_relative in "${lasttrain_managed_relative_paths[@]}"; do
  lasttrain_live_path="$lasttrain_target/$lasttrain_relative"
  if [[ -e "$lasttrain_live_path" ]]; then
    cp -a -- "$lasttrain_live_path" "$lasttrain_managed_snapshot/"
  fi
done
printf 'lasttrain-managed-snapshot-v1\n' \
  > "$lasttrain_managed_snapshot/$lasttrain_snapshot_sentinel"
if ! lasttrain_write_progress managed_snapshot true; then
  # No managed live path has changed yet. Do not enter rollback unless the
  # durable recovery pointer was activated successfully; a later run may
  # safely discard this unreferenced snapshot as an orphan.
  echo "Could not atomically activate the managed-server snapshot." >&2
  exit 1
fi
lasttrain_managed_transaction_active="true"

lasttrain_write_progress neoforge
lasttrain_verify_sha256 \
  "ac7bea8f5c8a1d64f8787d177cc890e3b9abf67ede800f999fe05386d46fcaa8" \
  "$lasttrain_neoforge"
lasttrain_verify_sha256 \
  "a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c" \
  "$lasttrain_packwiz_bootstrap"
lasttrain_verify_sha256 \
  "c9f646908d340d84773948a9a7d98bc1dae250d35e1016dc6e2b8459760b5598" \
  "$lasttrain_packwiz_main"
lasttrain_neoforge_args="$lasttrain_target/libraries/net/neoforged/neoforge/21.1.244/unix_args.txt"
lasttrain_neoforge_server_jar="$lasttrain_target/libraries/net/neoforged/neoforge/21.1.244/neoforge-21.1.244-server.jar"
lasttrain_reuse_neoforge="false"
if [[ -f "$lasttrain_target/.lasttrain-install-state" ]] &&
  grep -qx 'neoforge=21.1.244' "$lasttrain_target/.lasttrain-install-state" &&
  [[ -f "$lasttrain_target/run.sh" && -x "$lasttrain_target/run.sh" ]] &&
  [[ -f "$lasttrain_neoforge_args" && -f "$lasttrain_neoforge_server_jar" ]]; then
  lasttrain_recorded_run_sha256="$(
    sed -n 's/^neoforge_run_sha256=//p' \
      "$lasttrain_target/.lasttrain-install-state" | sed -n '1p'
  )"
  lasttrain_recorded_libraries_sha256="$(
    sed -n 's/^neoforge_libraries_sha256=//p' \
      "$lasttrain_target/.lasttrain-install-state" | sed -n '1p'
  )"
  lasttrain_current_libraries_sha256=""
  if [[ "$lasttrain_recorded_run_sha256" =~ ^[[:xdigit:]]{64}$ ]] &&
    [[ "$lasttrain_recorded_libraries_sha256" =~ ^[[:xdigit:]]{64}$ ]] &&
    lasttrain_verify_sha256 \
      "$lasttrain_recorded_run_sha256" \
      "$lasttrain_target/run.sh" &&
    lasttrain_current_libraries_sha256="$(
      lasttrain_tree_sha256 "$lasttrain_target/libraries"
    )" &&
    [[ "$lasttrain_current_libraries_sha256" == \
      "$lasttrain_recorded_libraries_sha256" ]]; then
    lasttrain_reuse_neoforge="true"
  fi
fi
if [[ "$lasttrain_reuse_neoforge" == "true" ]]; then
  echo "Reusing verified NeoForge 21.1.244 server runtime."
else
  (
    cd "$lasttrain_target"
    java -jar "$lasttrain_neoforge" --installServer
  )
fi
if [[ -L "$lasttrain_target/run.sh" || ! -f "$lasttrain_target/run.sh" ||
  ! -x "$lasttrain_target/run.sh" ]]; then
  echo "NeoForge installation did not produce a safe executable run.sh." >&2
  exit 1
fi
if [[ -L "$lasttrain_neoforge_args" || ! -f "$lasttrain_neoforge_args" ]]; then
  echo "NeoForge installation did not produce its pinned Unix argument file." >&2
  exit 1
fi
if [[ -L "$lasttrain_neoforge_server_jar" || ! -f "$lasttrain_neoforge_server_jar" ]]; then
  echo "NeoForge installation did not produce its pinned server JAR." >&2
  exit 1
fi

lasttrain_write_progress packwiz
java -jar "$lasttrain_packwiz_bootstrap" \
  --bootstrap-no-update \
  --bootstrap-main-jar "$lasttrain_packwiz_main" \
  -g \
  -s server \
  --pack-folder "$lasttrain_target" \
  "$lasttrain_repo/pack/pack.toml"

for lasttrain_required_mod in \
  "$lasttrain_target/mods/create-1.21.1-6.0.10.jar" \
  "$lasttrain_target/mods/tongdarailway-1.1.3+mc21.1.jar" \
  "$lasttrain_target/mods/tacz-neoforge-1.21.1-1.1.8-hotfix-r5.jar" \
  "$lasttrain_target/mods/create-aeronautics-bundled-1.21.1-1.3.0.jar" \
  "$lasttrain_target/mods/sable-neoforge-1.21.1-2.0.3.jar" \
  "$lasttrain_target/mods/sable_pathfinder-1.4.0.jar"; do
  if [[ -L "$lasttrain_required_mod" || ! -f "$lasttrain_required_mod" ]]; then
    echo "Packwiz did not install a required pinned server mod: $lasttrain_required_mod" >&2
    exit 1
  fi
done
for lasttrain_managed_tree in \
  "$lasttrain_target/mods" \
  "$lasttrain_target/config" \
  "$lasttrain_target/defaultconfigs" \
  "$lasttrain_target/kubejs" \
  "$lasttrain_target/libraries"; do
  if [[ -d "$lasttrain_managed_tree" ]] &&
    ! lasttrain_tree_has_no_symlinks "$lasttrain_managed_tree"; then
    echo "Installer produced an unreadable tree or unsafe symbolic link in: $lasttrain_managed_tree" >&2
    exit 1
  fi
done

lasttrain_write_progress staging
lasttrain_core_staged="$(mktemp "$lasttrain_target/mods/$lasttrain_core_filename.new.XXXXXXXX")"
lasttrain_simurail_staged="$(
  mktemp "$lasttrain_target/mods/$lasttrain_simurail_filename.new.XXXXXXXX"
)"
lasttrain_track_cleanup "$lasttrain_core_staged"
lasttrain_track_cleanup "$lasttrain_simurail_staged"
cp -- "$lasttrain_core_source" "$lasttrain_core_staged"
cp -- "$lasttrain_cached_simurail" "$lasttrain_simurail_staged"
if ! "$lasttrain_repo/scripts/verify-simurail.sh" "$lasttrain_simurail_staged"; then
  echo "Copied Create Simurail JAR failed verification." >&2
  rm -f -- "$lasttrain_core_staged" "$lasttrain_simurail_staged"
  exit 1
fi
chmod 0644 -- "$lasttrain_core_staged" "$lasttrain_simurail_staged"

lasttrain_backup="$(mktemp -d "$lasttrain_backup_root/update.XXXXXXXX")"
lasttrain_new_core_target="$lasttrain_target/mods/$lasttrain_core_filename"
lasttrain_new_simurail_target="$lasttrain_target/mods/$lasttrain_simurail_filename"
lasttrain_write_progress swap
lasttrain_swap_active="true"
for lasttrain_old_mod in \
  "$lasttrain_target"/mods/lasttrain-*.jar \
  "$lasttrain_target"/mods/simurail-*.jar \
  "$lasttrain_target"/mods/create_ferronautics-*.jar \
  "$lasttrain_target"/mods/taczjs-*.jar; do
  mv -- "$lasttrain_old_mod" "$lasttrain_backup/"
done
mv -f -- "$lasttrain_core_staged" "$lasttrain_target/mods/$lasttrain_core_filename"
mv -f -- \
  "$lasttrain_simurail_staged" \
  "$lasttrain_new_simurail_target"

lasttrain_write_progress state
lasttrain_core_sha256="$(
  sha256sum "$lasttrain_target/mods/$lasttrain_core_filename" | awk '{print $1}'
)"
lasttrain_simurail_sha256="$(
  sha256sum "$lasttrain_target/mods/$lasttrain_simurail_filename" | awk '{print $1}'
)"
lasttrain_pack_index_sha256="$(
  sha256sum "$lasttrain_repo/pack/index.toml" | awk '{print $1}'
)"
lasttrain_source_commit="$(
  git -C "$lasttrain_repo" rev-parse --verify HEAD 2>/dev/null || printf 'unknown'
)"
lasttrain_source_dirty="unknown"
if lasttrain_git_status="$(git -C "$lasttrain_repo" status --porcelain 2>/dev/null)"; then
  lasttrain_source_dirty="false"
  if [[ -n "$lasttrain_git_status" ]]; then
    lasttrain_source_dirty="true"
  fi
fi
lasttrain_neoforge_run_sha256="$(
  sha256sum "$lasttrain_target/run.sh" | awk '{print $1}'
)"
lasttrain_neoforge_libraries_sha256="$(
  lasttrain_tree_sha256 "$lasttrain_target/libraries"
)"

lasttrain_state_staged="$(
  mktemp "$lasttrain_target/.lasttrain-install-state.new.XXXXXXXX"
)"
lasttrain_track_cleanup "$lasttrain_state_staged"
{
  printf 'installed_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'minecraft=1.21.1\n'
  printf 'neoforge=21.1.244\n'
  printf 'neoforge_run_sha256=%s\n' "$lasttrain_neoforge_run_sha256"
  printf 'neoforge_libraries_sha256=%s\n' "$lasttrain_neoforge_libraries_sha256"
  printf 'packwiz_installer=0.5.14\n'
  printf 'pack_index_sha256=%s\n' "$lasttrain_pack_index_sha256"
  printf 'core_file=%s\n' "$lasttrain_core_filename"
  printf 'core_sha256=%s\n' "$lasttrain_core_sha256"
  printf 'simurail_commit=e68481dcf56de6a020e42880792526c77b017060\n'
  printf 'simurail_sha256=%s\n' "$lasttrain_simurail_sha256"
  printf 'source_commit=%s\n' "$lasttrain_source_commit"
  printf 'source_dirty=%s\n' "$lasttrain_source_dirty"
  printf 'transaction_id=%s\n' "$lasttrain_transaction_id"
  printf 'backup_directory=%s\n' "$lasttrain_backup"
} > "$lasttrain_state_staged"
mv -f -- "$lasttrain_state_staged" "$lasttrain_target/.lasttrain-install-state"
lasttrain_swap_active="false"
lasttrain_managed_transaction_active="false"
lasttrain_completed_snapshot="$lasttrain_managed_snapshot"
lasttrain_managed_snapshot=""
if ! lasttrain_write_progress committed; then
  # Keep the complete snapshot referenced so EXIT can roll back rather than
  # leaving an ambiguous committed state.
  lasttrain_managed_snapshot="$lasttrain_completed_snapshot"
  lasttrain_managed_transaction_active="true"
  echo "Could not atomically detach the completed transaction snapshot." >&2
  exit 1
fi
if ! rm -rf -- "$lasttrain_completed_snapshot"; then
  echo "Warning: could not remove completed transaction snapshot: $lasttrain_completed_snapshot" >&2
fi
rm -f -- "$lasttrain_progress"

echo
echo "Server files installed in: $lasttrain_target"
echo "Vehicle dependency: Create Simurail alpha e68481d (pinned and SHA-256 verified)"
echo "Review eula.txt, set eula=true only if you accept Mojang's EULA, then run run.sh."
