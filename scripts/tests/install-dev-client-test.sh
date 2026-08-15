#!/usr/bin/env bash
set -euo pipefail

lasttrain_test_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
lasttrain_test_work="$(mktemp -d "${TMPDIR:-/tmp}/lasttrain-client-test.XXXXXX")"
trap 'rm -rf "$lasttrain_test_work"' EXIT

lasttrain_test_fail() {
  echo "FAIL: $*" >&2
  exit 1
}

bash -n "$lasttrain_test_repo/scripts/install-dev-client.sh"

lasttrain_fake_repo="$lasttrain_test_work/repo"
mkdir -p \
  "$lasttrain_fake_repo/scripts" \
  "$lasttrain_fake_repo/prism" \
  "$lasttrain_fake_repo/pack"
cp \
  "$lasttrain_test_repo/scripts/install-dev-client.sh" \
  "$lasttrain_fake_repo/scripts/install-dev-client.sh"
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
  "$lasttrain_test_repo/prism/instance.cfg" \
  "$lasttrain_fake_repo/prism/instance.cfg"
cp \
  "$lasttrain_test_repo/prism/mmc-pack.json" \
  "$lasttrain_fake_repo/prism/mmc-pack.json"
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
  "$lasttrain_fake_repo/scripts/install-dev-client.sh"

export LASTTRAIN_TEST_REAL_SHA256SUM="$(command -v sha256sum)"
export PATH="$lasttrain_test_repo/scripts/tests/fixtures/bin:$PATH"
export LASTTRAIN_TEST_LOG="$lasttrain_test_work/tools.log"

lasttrain_client="$lasttrain_test_work/instances/lasttrain-dev"
"$lasttrain_fake_repo/scripts/install-dev-client.sh" "$lasttrain_client"

[[ "$(sed -n '1p' "$lasttrain_client/.lasttrain-managed-client")" == \
  "lasttrain-managed-client-v1" ]] ||
  lasttrain_test_fail "managed marker was not written"
cmp -s \
  "$lasttrain_fake_repo/prism/instance.cfg" \
  "$lasttrain_client/instance.cfg" ||
  lasttrain_test_fail "Prism instance.cfg differs from the pinned template"
cmp -s \
  "$lasttrain_fake_repo/prism/mmc-pack.json" \
  "$lasttrain_client/mmc-pack.json" ||
  lasttrain_test_fail "Prism mmc-pack.json differs from the pinned template"
[[ -f "$lasttrain_client/.minecraft/mods/from-packwiz-client.jar" ]] ||
  lasttrain_test_fail "Packwiz client-side installation was not invoked"
[[ -f "$lasttrain_client/.minecraft/mods/lasttrain-test.jar" ]] ||
  lasttrain_test_fail "Last Train core was not installed"
[[ -f "$lasttrain_client/.minecraft/mods/simurail-1.21.1-0.0.0-a+e68481d.jar" ]] ||
  lasttrain_test_fail "pinned Simurail was not installed"
grep -q -- "-s client" "$LASTTRAIN_TEST_LOG" ||
  lasttrain_test_fail "Packwiz was not called with the client side"
grep -q -- "--bootstrap-no-update" "$LASTTRAIN_TEST_LOG" ||
  lasttrain_test_fail "Packwiz bootstrap updates were not disabled"
grep -q '^minecraft=1.21.1$' "$lasttrain_client/.lasttrain-install-state" ||
  lasttrain_test_fail "install provenance is missing the Minecraft version"
grep -q '^neoforge=21.1.244$' "$lasttrain_client/.lasttrain-install-state" ||
  lasttrain_test_fail "install provenance is missing the NeoForge version"
grep -q '^simurail_commit=e68481dcf56de6a020e42880792526c77b017060$' \
  "$lasttrain_client/.lasttrain-install-state" ||
  lasttrain_test_fail "install provenance is missing the Simurail commit"
[[ ! -e "$lasttrain_client/.lasttrain-install-in-progress" ]] ||
  lasttrain_test_fail "successful installation retained an in-progress marker"
if find "$lasttrain_client" -name eula.txt -print -quit | grep -q .; then
  lasttrain_test_fail "client installer created an EULA acceptance file"
fi

printf 'TestUserSetting=true\n' >> "$lasttrain_client/instance.cfg"
"$lasttrain_fake_repo/scripts/install-dev-client.sh" "$lasttrain_client"
grep -q '^TestUserSetting=true$' "$lasttrain_client/instance.cfg" ||
  lasttrain_test_fail "managed update overwrote Prism user settings"
find "$lasttrain_client/.lasttrain-installer-cache/replaced-mods" \
  -type f -name 'lasttrain-*.jar' -print -quit |
  grep -q . ||
  lasttrain_test_fail "managed update did not back up the old core"
find "$lasttrain_client/.lasttrain-installer-cache/replaced-mods" \
  -type f -name 'simurail-*.jar' -print -quit |
  grep -q . ||
  lasttrain_test_fail "managed update did not back up old Simurail"

lasttrain_unmanaged="$lasttrain_test_work/instances/unmanaged"
mkdir -p "$lasttrain_unmanaged"
printf 'user data\n' > "$lasttrain_unmanaged/options.txt"
if "$lasttrain_fake_repo/scripts/install-dev-client.sh" "$lasttrain_unmanaged" \
  >"$lasttrain_test_work/unmanaged.out" 2>&1; then
  lasttrain_test_fail "installer accepted a non-empty unmanaged target"
fi
[[ ! -e "$lasttrain_unmanaged/.lasttrain-managed-client" ]] ||
  lasttrain_test_fail "installer marked an unmanaged target"
[[ "$(sed -n '1p' "$lasttrain_unmanaged/options.txt")" == "user data" ]] ||
  lasttrain_test_fail "installer changed an unmanaged target"

lasttrain_wrong_java="$lasttrain_test_work/instances/wrong-java"
if LASTTRAIN_TEST_JAVA_FEATURE=17 \
  "$lasttrain_fake_repo/scripts/install-dev-client.sh" "$lasttrain_wrong_java" \
  >"$lasttrain_test_work/wrong-java.out" 2>&1; then
  lasttrain_test_fail "installer accepted Java 17"
fi
[[ ! -e "$lasttrain_wrong_java/.lasttrain-managed-client" ]] ||
  lasttrain_test_fail "installer marked a target before validating Java 21"

lasttrain_bad_hash="$lasttrain_test_work/instances/bad-simurail-hash"
if LASTTRAIN_TEST_FAIL_SHA_PATTERN=simurail \
  "$lasttrain_fake_repo/scripts/install-dev-client.sh" "$lasttrain_bad_hash" \
  >"$lasttrain_test_work/bad-hash.out" 2>&1; then
  lasttrain_test_fail "installer accepted a bad Simurail SHA-256"
fi
[[ -f "$lasttrain_bad_hash/.lasttrain-install-in-progress" ]] ||
  lasttrain_test_fail "failed installation did not retain its in-progress marker"
[[ ! -e "$lasttrain_bad_hash/.lasttrain-install-state" ]] ||
  lasttrain_test_fail "failed installation wrote successful provenance"
[[ ! -e \
  "$lasttrain_bad_hash/.minecraft/mods/simurail-1.21.1-0.0.0-a+e68481d.jar" ]] ||
  lasttrain_test_fail "failed installation copied an unverified Simurail JAR"
if find "$lasttrain_bad_hash/.lasttrain-installer-cache" \
  -maxdepth 1 -type f -name 'lasttrain-*.jar.build.*' -print -quit | grep -q .; then
  lasttrain_test_fail "failed installation retained a copied core build artifact"
fi

mkdir -p "$lasttrain_fake_repo/pack/unsafe-client"
if "$lasttrain_fake_repo/scripts/install-dev-client.sh" \
  "$lasttrain_fake_repo/pack/unsafe-client" \
  >"$lasttrain_test_work/pack-source.out" 2>&1; then
  lasttrain_test_fail "installer accepted a target inside Packwiz source"
fi

exec {lasttrain_test_lock_fd}> "$lasttrain_client/.lasttrain-install.lock"
flock -n "$lasttrain_test_lock_fd"
if "$lasttrain_fake_repo/scripts/install-dev-client.sh" "$lasttrain_client" \
  >"$lasttrain_test_work/lock.out" 2>&1; then
  lasttrain_test_fail "installer ignored an existing client target lock"
fi
grep -q 'already running' "$lasttrain_test_work/lock.out" ||
  lasttrain_test_fail "concurrent client installer failure did not explain the lock"
flock -u "$lasttrain_test_lock_fd"
exec {lasttrain_test_lock_fd}>&-

lasttrain_link_target="$lasttrain_test_work/link-target"
mkdir -p "$lasttrain_link_target"
mv \
  "$lasttrain_client/.minecraft/mods" \
  "$lasttrain_client/.minecraft/mods-before-link-test"
ln -s "$lasttrain_link_target" "$lasttrain_client/.minecraft/mods"
if "$lasttrain_fake_repo/scripts/install-dev-client.sh" "$lasttrain_client" \
  >"$lasttrain_test_work/symlink.out" 2>&1; then
  lasttrain_test_fail "installer accepted a symbolic-link mods directory"
fi
if find "$lasttrain_link_target" -mindepth 1 -print -quit | grep -q .; then
  lasttrain_test_fail "installer wrote through a symbolic-link mods directory"
fi

echo "PASS: install-dev-client safety and reproducibility checks"
