#!/usr/bin/env bash
set -euo pipefail

lasttrain_test_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
lasttrain_test_work="$(mktemp -d "${TMPDIR:-/tmp}/lasttrain-server-test.XXXXXX")"
trap 'rm -rf -- "$lasttrain_test_work"' EXIT

lasttrain_test_fail() {
  echo "FAIL: $*" >&2
  exit 1
}

bash -n "$lasttrain_test_repo/scripts/install-dev-server.sh"

lasttrain_fake_repo="$lasttrain_test_work/repo"
mkdir -p "$lasttrain_fake_repo/scripts" "$lasttrain_fake_repo/pack"
cp \
  "$lasttrain_test_repo/scripts/install-dev-server.sh" \
  "$lasttrain_fake_repo/scripts/install-dev-server.sh"
cp \
  "$lasttrain_test_repo/scripts/tests/fixtures/fetch-simurail.sh" \
  "$lasttrain_fake_repo/scripts/fetch-simurail.sh"
cp \
  "$lasttrain_test_repo/scripts/tests/fixtures/verify-simurail.sh" \
  "$lasttrain_fake_repo/scripts/verify-simurail.sh"
cp \
  "$lasttrain_test_repo/scripts/tests/fixtures/gradlew" \
  "$lasttrain_fake_repo/gradlew"
cp \
  "$lasttrain_test_repo/pack/pack.toml" \
  "$lasttrain_fake_repo/pack/pack.toml"
cp \
  "$lasttrain_test_repo/pack/index.toml" \
  "$lasttrain_fake_repo/pack/index.toml"
chmod +x \
  "$lasttrain_fake_repo/gradlew" \
  "$lasttrain_fake_repo/scripts/fetch-simurail.sh" \
  "$lasttrain_fake_repo/scripts/verify-simurail.sh" \
  "$lasttrain_fake_repo/scripts/install-dev-server.sh"

export LASTTRAIN_TEST_REAL_SHA256SUM="$(command -v sha256sum)"
export PATH="$lasttrain_test_repo/scripts/tests/fixtures/bin:$PATH"
export LASTTRAIN_TEST_LOG="$lasttrain_test_work/tools.log"

lasttrain_server="$lasttrain_test_work/servers/lasttrain-dev"
"$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server"

[[ "$(sed -n '1p' "$lasttrain_server/.lasttrain-managed-server")" == \
  "lasttrain-managed-server-v1" ]] ||
  lasttrain_test_fail "managed marker was not written"
[[ -x "$lasttrain_server/run.sh" ]] ||
  lasttrain_test_fail "NeoForge server installation was not invoked"
[[ -f "$lasttrain_server/mods/from-packwiz-server.jar" ]] ||
  lasttrain_test_fail "Packwiz server-side installation was not invoked"
[[ -f "$lasttrain_server/mods/lasttrain-test.jar" ]] ||
  lasttrain_test_fail "Last Train core was not installed"
[[ -f "$lasttrain_server/mods/simurail-1.21.1-0.0.0-a+e68481d.jar" ]] ||
  lasttrain_test_fail "pinned Simurail was not installed"
grep -q -- "--installServer" "$LASTTRAIN_TEST_LOG" ||
  lasttrain_test_fail "NeoForge installer arguments were not recorded"
grep -q -- "-s server" "$LASTTRAIN_TEST_LOG" ||
  lasttrain_test_fail "Packwiz was not called with the server side"
grep -q -- "--bootstrap-no-update" "$LASTTRAIN_TEST_LOG" ||
  lasttrain_test_fail "Packwiz bootstrap updates were not disabled"
grep -q '^minecraft=1.21.1$' "$lasttrain_server/.lasttrain-install-state" ||
  lasttrain_test_fail "install provenance is missing the Minecraft version"
grep -q '^neoforge=21.1.244$' "$lasttrain_server/.lasttrain-install-state" ||
  lasttrain_test_fail "install provenance is missing the NeoForge version"
grep -q '^simurail_commit=e68481dcf56de6a020e42880792526c77b017060$' \
  "$lasttrain_server/.lasttrain-install-state" ||
  lasttrain_test_fail "install provenance is missing the Simurail commit"
[[ ! -e "$lasttrain_server/.lasttrain-install-in-progress" ]] ||
  lasttrain_test_fail "successful installation retained an in-progress marker"
if find "$lasttrain_server" -name eula.txt -print -quit | grep -q .; then
  lasttrain_test_fail "server installer created an EULA acceptance file"
fi

lasttrain_neoforge_cache="$lasttrain_server/.lasttrain-installer-cache/neoforge-21.1.244-installer.jar"
printf 'corrupt cache fixture\n' > "$lasttrain_neoforge_cache"
LASTTRAIN_TEST_VALIDATE_CACHE_CONTENT=1 \
  "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server"
grep -qx 'download fixture' "$lasttrain_neoforge_cache" ||
  lasttrain_test_fail "corrupted installer cache was not replaced"
find "$lasttrain_server/.lasttrain-installer-cache/replaced-mods" \
  -type f -name 'lasttrain-*.jar' -print -quit |
  grep -q . ||
  lasttrain_test_fail "managed update did not back up the old core"
find "$lasttrain_server/.lasttrain-installer-cache/replaced-mods" \
  -type f -name 'simurail-*.jar' -print -quit |
  grep -q . ||
  lasttrain_test_fail "managed update did not back up old Simurail"

lasttrain_installed_simurail="$lasttrain_server/mods/simurail-1.21.1-0.0.0-a+e68481d.jar"
lasttrain_installed_simurail_before="$($LASTTRAIN_TEST_REAL_SHA256SUM "$lasttrain_installed_simurail")"
if LASTTRAIN_TEST_FAIL_SHA_PATTERN=simurail \
  "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/bad-update.out" 2>&1; then
  lasttrain_test_fail "installer accepted a bad Simurail update"
fi
[[ "$($LASTTRAIN_TEST_REAL_SHA256SUM "$lasttrain_installed_simurail")" == \
  "$lasttrain_installed_simurail_before" ]] ||
  lasttrain_test_fail "failed update changed installed Simurail"
[[ -f "$lasttrain_server/.lasttrain-install-in-progress" ]] ||
  lasttrain_test_fail "failed update did not retain its in-progress marker"

lasttrain_installed_core="$lasttrain_server/mods/lasttrain-test.jar"
lasttrain_installed_core_before="$($LASTTRAIN_TEST_REAL_SHA256SUM "$lasttrain_installed_core")"
lasttrain_installed_simurail_before="$($LASTTRAIN_TEST_REAL_SHA256SUM "$lasttrain_installed_simurail")"
if LASTTRAIN_TEST_FAIL_SHA_COMMAND_PATTERN=lasttrain-test.jar \
  "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/rollback.out" 2>&1; then
  lasttrain_test_fail "installer accepted a post-swap hash failure"
fi
[[ "$($LASTTRAIN_TEST_REAL_SHA256SUM "$lasttrain_installed_core")" == \
  "$lasttrain_installed_core_before" ]] ||
  lasttrain_test_fail "failed swap did not restore the previous core"
[[ "$($LASTTRAIN_TEST_REAL_SHA256SUM "$lasttrain_installed_simurail")" == \
  "$lasttrain_installed_simurail_before" ]] ||
  lasttrain_test_fail "failed swap did not restore the previous Simurail"
grep -q '^rollback_attempted=true$' \
  "$lasttrain_server/.lasttrain-install-in-progress" ||
  lasttrain_test_fail "failed swap did not record its rollback attempt"
grep -q '^rollback_succeeded=true$' \
  "$lasttrain_server/.lasttrain-install-in-progress" ||
  lasttrain_test_fail "failed swap did not confirm successful rollback"

"$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/recovery.out"
[[ ! -e "$lasttrain_server/.lasttrain-install-in-progress" ]] ||
  lasttrain_test_fail "successful recovery retained an in-progress marker"

mkdir -p "$lasttrain_server/config"
printf 'original managed config\n' \
  > "$lasttrain_server/config/transaction-sentinel.txt"
lasttrain_transaction_state_before="$(
  "$LASTTRAIN_TEST_REAL_SHA256SUM" "$lasttrain_server/.lasttrain-install-state"
)"
if LASTTRAIN_TEST_FAIL_PACKWIZ_AFTER_WRITE=1 \
  "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/packwiz-rollback.out" 2>&1; then
  lasttrain_test_fail "installer accepted a partial Packwiz update"
fi
grep -qx 'original managed config' \
  "$lasttrain_server/config/transaction-sentinel.txt" ||
  lasttrain_test_fail "failed Packwiz update did not restore managed config"
[[ ! -e "$lasttrain_server/mods/partial-packwiz.jar" ]] ||
  lasttrain_test_fail "failed Packwiz update left a partial mod"
[[ "$("$LASTTRAIN_TEST_REAL_SHA256SUM" "$lasttrain_server/.lasttrain-install-state")" == \
  "$lasttrain_transaction_state_before" ]] ||
  lasttrain_test_fail "failed Packwiz update changed install provenance"
grep -q '^rollback_attempted=true$' \
  "$lasttrain_server/.lasttrain-install-in-progress" ||
  lasttrain_test_fail "failed Packwiz update did not record full rollback"
grep -q '^rollback_succeeded=true$' \
  "$lasttrain_server/.lasttrain-install-in-progress" ||
  lasttrain_test_fail "failed Packwiz update did not confirm successful rollback"

"$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/packwiz-recovery.out"
[[ ! -e "$lasttrain_server/.lasttrain-install-in-progress" ]] ||
  lasttrain_test_fail "Packwiz rollback recovery retained an in-progress marker"

# Simulate a hard process/host stop after live trees were mutated. The next
# run must recover from the complete, still-present snapshot before doing any
# new installation work. Force that new work to fail before another managed
# transaction so the recovered state remains directly observable.
lasttrain_crash_snapshot="$lasttrain_server/.lasttrain-installer-cache/managed-snapshot.CRASH001"
mkdir -p "$lasttrain_crash_snapshot"
for lasttrain_crash_relative in \
  mods config defaultconfigs kubejs libraries run.sh run.bat \
  user_jvm_args.txt neoforge-21.1.244-installer.jar.log packwiz.json \
  .lasttrain-install-state; do
  if [[ -e "$lasttrain_server/$lasttrain_crash_relative" ]]; then
    cp -a \
      "$lasttrain_server/$lasttrain_crash_relative" \
      "$lasttrain_crash_snapshot/"
  fi
done
printf 'lasttrain-managed-snapshot-v1\n' \
  > "$lasttrain_crash_snapshot/.lasttrain-managed-snapshot-v1"
lasttrain_crash_state_before="$(
  "$LASTTRAIN_TEST_REAL_SHA256SUM" "$lasttrain_server/.lasttrain-install-state"
)"
printf 'mutated after simulated crash\n' \
  > "$lasttrain_server/config/transaction-sentinel.txt"
printf 'partial crash fixture\n' > "$lasttrain_server/mods/partial-crash.jar"
{
  printf 'phase=packwiz\n'
  printf 'managed_snapshot=%s\n' "$lasttrain_crash_snapshot"
  printf 'managed_transaction_active=true\n'
} > "$lasttrain_server/.lasttrain-install-in-progress"
if LASTTRAIN_TEST_FAIL_SHA_PATTERN=simurail \
  "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/crash-recovery.out" 2>&1; then
  lasttrain_test_fail "post-crash fixture unexpectedly completed"
fi
grep -qx 'original managed config' \
  "$lasttrain_server/config/transaction-sentinel.txt" ||
  lasttrain_test_fail "stale transaction did not restore managed config"
[[ ! -e "$lasttrain_server/mods/partial-crash.jar" ]] ||
  lasttrain_test_fail "stale transaction left a partial mod"
[[ "$("$LASTTRAIN_TEST_REAL_SHA256SUM" "$lasttrain_server/.lasttrain-install-state")" == \
  "$lasttrain_crash_state_before" ]] ||
  lasttrain_test_fail "stale transaction did not restore install provenance"
[[ ! -e "$lasttrain_crash_snapshot" ]] ||
  lasttrain_test_fail "successful stale recovery retained its snapshot"
grep -q 'Recovering the managed server trees' \
  "$lasttrain_test_work/crash-recovery.out" ||
  lasttrain_test_fail "stale transaction recovery was not reported"

"$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/crash-recovery-followup.out"
[[ ! -e "$lasttrain_server/.lasttrain-install-in-progress" ]] ||
  lasttrain_test_fail "post-crash follow-up retained an in-progress marker"

# A progress file is server-local input and must not be able to smuggle a
# traversal path into recursive snapshot cleanup.
lasttrain_attack_prefix="$lasttrain_server/.lasttrain-installer-cache/managed-snapshot.ATTACK01"
lasttrain_attack_victim="$lasttrain_server/path-injection-victim"
mkdir -p "$lasttrain_attack_prefix" "$lasttrain_attack_victim"
printf 'do not delete\n' > "$lasttrain_attack_victim/sentinel.txt"
{
  printf 'managed_snapshot=%s/../../path-injection-victim\n' "$lasttrain_attack_prefix"
  printf 'managed_transaction_active=true\n'
} > "$lasttrain_server/.lasttrain-install-in-progress"
if "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/snapshot-path-injection.out" 2>&1; then
  lasttrain_test_fail "installer accepted a traversing snapshot path"
fi
grep -q 'unsafe or missing snapshot' \
  "$lasttrain_test_work/snapshot-path-injection.out" ||
  lasttrain_test_fail "unsafe snapshot path did not fail closed"
grep -qx 'do not delete' "$lasttrain_attack_victim/sentinel.txt" ||
  lasttrain_test_fail "unsafe snapshot path deleted an unrelated directory"
[[ -f "$lasttrain_server/.lasttrain-install-in-progress" ]] ||
  lasttrain_test_fail "unsafe snapshot refusal discarded recovery evidence"
rm -f -- "$lasttrain_server/.lasttrain-install-in-progress"
rm -rf -- "$lasttrain_attack_prefix" "$lasttrain_attack_victim"

# A stop before a snapshot was linked into progress can leave an unreferenced
# immediate-child directory in the private installer cache. It must be removed
# without treating similarly named paths elsewhere as cleanup targets.
lasttrain_orphan_snapshot="$lasttrain_server/.lasttrain-installer-cache/managed-snapshot.ORPHAN01"
mkdir -p "$lasttrain_orphan_snapshot"
printf 'partial orphan\n' > "$lasttrain_orphan_snapshot/sentinel.txt"
"$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/orphan-cleanup.out"
[[ ! -e "$lasttrain_orphan_snapshot" ]] ||
  lasttrain_test_fail "unreferenced installer snapshot was not cleaned up"

exec {lasttrain_test_lock_fd}> "$lasttrain_server/.lasttrain-install.lock"
flock -n "$lasttrain_test_lock_fd"
if "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/lock.out" 2>&1; then
  lasttrain_test_fail "installer ignored an existing target lock"
fi
grep -q 'already running' "$lasttrain_test_work/lock.out" ||
  lasttrain_test_fail "concurrent installer failure did not explain the target lock"
[[ ! -e "$lasttrain_server/.lasttrain-install-in-progress" ]] ||
  lasttrain_test_fail "lock refusal modified installation progress"
flock -u "$lasttrain_test_lock_fd"
exec {lasttrain_test_lock_fd}>&-

lasttrain_nested_link_target="$lasttrain_test_work/nested-link-target"
printf 'outside\n' > "$lasttrain_nested_link_target"
mkdir -p "$lasttrain_server/config/nested"
ln -s "$lasttrain_nested_link_target" "$lasttrain_server/config/nested/unsafe-link"
if "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/nested-link.out" 2>&1; then
  lasttrain_test_fail "installer accepted a nested managed-tree symbolic link"
fi
grep -qx 'outside' "$lasttrain_nested_link_target" ||
  lasttrain_test_fail "installer wrote through a nested symbolic link"
rm -f -- "$lasttrain_server/config/nested/unsafe-link"

lasttrain_unmanaged="$lasttrain_test_work/servers/unmanaged"
mkdir -p "$lasttrain_unmanaged"
printf 'user data\n' > "$lasttrain_unmanaged/world.txt"
if "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_unmanaged" \
  >"$lasttrain_test_work/unmanaged.out" 2>&1; then
  lasttrain_test_fail "installer accepted a non-empty unmanaged target"
fi
[[ ! -e "$lasttrain_unmanaged/.lasttrain-managed-server" ]] ||
  lasttrain_test_fail "installer marked an unmanaged target"
grep -qx 'user data' "$lasttrain_unmanaged/world.txt" ||
  lasttrain_test_fail "installer changed an unmanaged target"

lasttrain_wrong_java="$lasttrain_test_work/servers/wrong-java"
if LASTTRAIN_TEST_JAVA_FEATURE=17 \
  "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_wrong_java" \
  >"$lasttrain_test_work/wrong-java.out" 2>&1; then
  lasttrain_test_fail "installer accepted Java 17"
fi
[[ ! -e "$lasttrain_wrong_java/.lasttrain-managed-server" ]] ||
  lasttrain_test_fail "installer marked a target before validating Java 21"

lasttrain_link_target="$lasttrain_test_work/link-target"
mkdir -p "$lasttrain_link_target"
mv "$lasttrain_server/mods" "$lasttrain_server/mods-before-link-test"
ln -s "$lasttrain_link_target" "$lasttrain_server/mods"
if "$lasttrain_fake_repo/scripts/install-dev-server.sh" "$lasttrain_server" \
  >"$lasttrain_test_work/symlink.out" 2>&1; then
  lasttrain_test_fail "installer accepted a symbolic-link mods directory"
fi
if find "$lasttrain_link_target" -mindepth 1 -print -quit | grep -q .; then
  lasttrain_test_fail "installer wrote through a symbolic-link mods directory"
fi

echo "PASS: install-dev-server safety and reproducibility checks"
