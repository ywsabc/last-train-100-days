#!/usr/bin/env bash
set -euo pipefail

simurail_test_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
simurail_test_fixture="$simurail_test_repo/scripts/tests/fixtures/fetch-simurail"
simurail_test_work="$(mktemp -d "${TMPDIR:-/tmp}/lasttrain-fetch-simurail-test.XXXXXX")"
simurail_test_cleanup() {
  if [[ "${LASTTRAIN_SIMURAIL_TEST_KEEP_WORK:-0}" == "1" ]]; then
    echo "Preserved test work directory: $simurail_test_work" >&2
  else
    rm -rf -- "$simurail_test_work"
  fi
}
trap simurail_test_cleanup EXIT

simurail_test_fail() {
  echo "FAIL: $*" >&2
  exit 1
}

bash -n "$simurail_test_repo/scripts/fetch-simurail.sh"

simurail_fake_repo="$simurail_test_work/repo"
mkdir -p "$simurail_fake_repo/scripts"
cp -- \
  "$simurail_test_repo/scripts/fetch-simurail.sh" \
  "$simurail_fake_repo/scripts/fetch-simurail.sh"
cp -- \
  "$simurail_test_fixture/verify-simurail.sh" \
  "$simurail_fake_repo/scripts/verify-simurail.sh"
chmod +x \
  "$simurail_fake_repo/scripts/fetch-simurail.sh" \
  "$simurail_fake_repo/scripts/verify-simurail.sh" \
  "$simurail_test_fixture/bin/"* \
  "$simurail_test_fixture/source-gradlew"

export LASTTRAIN_SIMURAIL_TEST_REAL_SHA256SUM="$(command -v sha256sum)"
export LASTTRAIN_SIMURAIL_TEST_REAL_MV="$(command -v mv)"
export PATH="$simurail_test_fixture/bin:$PATH"
export LASTTRAIN_SIMURAIL_TEST_LOG="$simurail_test_work/tools.log"
mkdir -p "$simurail_test_work/tmp"
export TMPDIR="$simurail_test_work/tmp"

# A valid existing JAR must return before any network, extraction, build, or move.
simurail_short_output="$simurail_test_work/short-circuit/simurail.jar"
mkdir -p "$(dirname "$simurail_short_output")"
printf 'simurail e68481d fixture\n' > "$simurail_short_output"
simurail_short_inode="$(stat -c '%i' "$simurail_short_output")"
: > "$LASTTRAIN_SIMURAIL_TEST_LOG"
"$simurail_fake_repo/scripts/fetch-simurail.sh" "$simurail_short_output" \
  > "$simurail_test_work/short-circuit.out" 2>&1
grep -q 'is already verified' "$simurail_test_work/short-circuit.out" ||
  simurail_test_fail "verified output did not take the short-circuit path"
[[ "$(stat -c '%i' "$simurail_short_output")" == "$simurail_short_inode" ]] ||
  simurail_test_fail "verified output was replaced"
[[ "$(wc -l < "$LASTTRAIN_SIMURAIL_TEST_LOG")" == "1" ]] ||
  simurail_test_fail "short-circuit invoked tools after verification"
grep -q '^verify|.*|valid$' "$LASTTRAIN_SIMURAIL_TEST_LOG" ||
  simurail_test_fail "short-circuit did not verify the existing JAR"

# A failed pinned Actions artifact must fall back to the exact source commit.
# The wrapper starts without a hash; the fetcher must inject the pinned 8.14.3 hash.
simurail_fallback_output="$simurail_test_work/fallback/mods/simurail.jar"
mkdir -p "$(dirname "$simurail_fallback_output")"
printf 'old invalid output\n' > "$simurail_fallback_output"
: > "$LASTTRAIN_SIMURAIL_TEST_LOG"
LASTTRAIN_SIMURAIL_GH_MODE=fail \
LASTTRAIN_SIMURAIL_WRAPPER_URL_MODE=services \
LASTTRAIN_SIMURAIL_WRAPPER_HASH_MODE=missing \
  "$simurail_fake_repo/scripts/fetch-simurail.sh" "$simurail_fallback_output" \
  > "$simurail_test_work/fallback.out" 2>&1
grep -q 'falling back to source' "$simurail_test_work/fallback.out" ||
  simurail_test_fail "failed GitHub artifact did not report source fallback"
grep -Fq \
  'gh|api repos/Crystaelix/Create-Simurail/actions/artifacts/8738923712/zip' \
  "$LASTTRAIN_SIMURAIL_TEST_LOG" ||
  simurail_test_fail "fetcher did not request the pinned GitHub artifact"
grep -Fq \
  'https://codeload.github.com/Crystaelix/Create-Simurail/tar.gz/e68481dcf56de6a020e42880792526c77b017060' \
  "$LASTTRAIN_SIMURAIL_TEST_LOG" ||
  simurail_test_fail "source fallback did not request the pinned commit"
grep -Fq \
  'sha256-check|8417fadd7f6a5afa7322e191326f423344d68937f724ebf684485d02e8de9144' \
  "$LASTTRAIN_SIMURAIL_TEST_LOG" ||
  simurail_test_fail "source fallback did not verify the pinned source archive"
grep -Fq \
  'gradlew|--no-daemon clean assemble -PcommitHash=e68481d|distributionUrl=https\://downloads.gradle.org/distributions/gradle-8.14.3-bin.zip|distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531' \
  "$LASTTRAIN_SIMURAIL_TEST_LOG" ||
  simurail_test_fail "fallback build did not receive the rewritten URL and injected Gradle 8.14.3 hash"
grep -qx 'simurail e68481d fixture' "$simurail_fallback_output" ||
  simurail_test_fail "source fallback did not install the verified JAR"

simurail_mv_line="$(grep '^mv|' "$LASTTRAIN_SIMURAIL_TEST_LOG")"
IFS='|' read -r _ simurail_staged_path simurail_move_destination \
  <<< "$simurail_mv_line"
[[ "$simurail_move_destination" == "$simurail_fallback_output" ]] ||
  simurail_test_fail "atomic move targeted the wrong output"
[[ "$(dirname "$simurail_staged_path")" == \
  "$(dirname "$simurail_fallback_output")" ]] ||
  simurail_test_fail "final staging file was not in the output directory"
[[ "$(basename "$simurail_staged_path")" == \
  ".$(basename "$simurail_fallback_output").new."* ]] ||
  simurail_test_fail "final staging file did not use the expected unique name"
[[ ! -e "$simurail_staged_path" ]] ||
  simurail_test_fail "successful atomic move left a staging file"
simurail_final_verify_line="$(grep -n '^verify|.*lasttrain-simurail.*|valid$' \
  "$LASTTRAIN_SIMURAIL_TEST_LOG" | tail -n 1 | cut -d: -f1)"
simurail_move_line="$(grep -n '^mv|' "$LASTTRAIN_SIMURAIL_TEST_LOG" | cut -d: -f1)"
[[ -n "$simurail_final_verify_line" &&
  "$simurail_final_verify_line" -lt "$simurail_move_line" ]] ||
  simurail_test_fail "JAR was not verified before the final atomic move"

# An unapproved Gradle distribution host must fail before Gradle runs and must
# leave an existing output intact.
simurail_bad_url_output="$simurail_test_work/bad-wrapper-url/simurail.jar"
mkdir -p "$(dirname "$simurail_bad_url_output")"
printf 'preserve output after bad URL\n' > "$simurail_bad_url_output"
: > "$LASTTRAIN_SIMURAIL_TEST_LOG"
if LASTTRAIN_SIMURAIL_GH_MODE=fail \
  LASTTRAIN_SIMURAIL_WRAPPER_URL_MODE=wrong \
  LASTTRAIN_SIMURAIL_WRAPPER_HASH_MODE=missing \
  "$simurail_fake_repo/scripts/fetch-simurail.sh" "$simurail_bad_url_output" \
  > "$simurail_test_work/bad-wrapper-url.out" 2>&1; then
  simurail_test_fail "fetcher accepted an unapproved Gradle distribution URL"
fi
grep -q 'unexpected Gradle distribution URL' \
  "$simurail_test_work/bad-wrapper-url.out" ||
  simurail_test_fail "wrong wrapper URL did not produce the expected failure"
grep -qx 'preserve output after bad URL' "$simurail_bad_url_output" ||
  simurail_test_fail "URL validation failure overwrote the previous output"
if grep -q '^gradlew\|^mv|' "$LASTTRAIN_SIMURAIL_TEST_LOG"; then
  simurail_test_fail "wrong wrapper URL reached the build or final move"
fi

# An upstream wrapper that declares a different distribution hash must fail
# before Gradle runs and must leave an existing output byte-for-byte intact.
simurail_bad_hash_output="$simurail_test_work/bad-wrapper/simurail.jar"
mkdir -p "$(dirname "$simurail_bad_hash_output")"
printf 'preserve this old output\n' > "$simurail_bad_hash_output"
: > "$LASTTRAIN_SIMURAIL_TEST_LOG"
if LASTTRAIN_SIMURAIL_GH_MODE=fail \
  LASTTRAIN_SIMURAIL_WRAPPER_URL_MODE=downloads \
  LASTTRAIN_SIMURAIL_WRAPPER_HASH_MODE=wrong \
  "$simurail_fake_repo/scripts/fetch-simurail.sh" "$simurail_bad_hash_output" \
  > "$simurail_test_work/bad-wrapper.out" 2>&1; then
  simurail_test_fail "fetcher accepted an unexpected Gradle distribution hash"
fi
grep -q 'unexpected Gradle distribution hash' \
  "$simurail_test_work/bad-wrapper.out" ||
  simurail_test_fail "wrong wrapper hash did not produce the expected failure"
grep -qx 'preserve this old output' "$simurail_bad_hash_output" ||
  simurail_test_fail "failed build overwrote the previous output"
if grep -q '^gradlew\|^mv|' "$LASTTRAIN_SIMURAIL_TEST_LOG"; then
  simurail_test_fail "wrong wrapper hash reached the build or final move"
fi

# Refuse both a symbolic-link output and a symbolic-link output directory.
simurail_link_victim="$simurail_test_work/link-victim.jar"
printf 'link victim\n' > "$simurail_link_victim"
mkdir -p "$simurail_test_work/link-output"
ln -s "$simurail_link_victim" "$simurail_test_work/link-output/simurail.jar"
: > "$LASTTRAIN_SIMURAIL_TEST_LOG"
if "$simurail_fake_repo/scripts/fetch-simurail.sh" \
  "$simurail_test_work/link-output/simurail.jar" \
  > "$simurail_test_work/link-output.out" 2>&1; then
  simurail_test_fail "fetcher accepted a symbolic-link output"
fi
grep -qx 'link victim' "$simurail_link_victim" ||
  simurail_test_fail "fetcher wrote through a symbolic-link output"
[[ ! -s "$LASTTRAIN_SIMURAIL_TEST_LOG" ]] ||
  simurail_test_fail "symbolic-link output was processed before rejection"

simurail_link_directory_target="$simurail_test_work/link-directory-target"
mkdir -p "$simurail_link_directory_target"
ln -s "$simurail_link_directory_target" \
  "$simurail_test_work/link-directory"
: > "$LASTTRAIN_SIMURAIL_TEST_LOG"
if "$simurail_fake_repo/scripts/fetch-simurail.sh" \
  "$simurail_test_work/link-directory/simurail.jar" \
  > "$simurail_test_work/link-directory.out" 2>&1; then
  simurail_test_fail "fetcher accepted a symbolic-link output directory"
fi
[[ ! -e "$simurail_link_directory_target/simurail.jar" ]] ||
  simurail_test_fail "fetcher wrote through a symbolic-link output directory"
[[ ! -s "$LASTTRAIN_SIMURAIL_TEST_LOG" ]] ||
  simurail_test_fail "symbolic-link directory was processed before rejection"

echo "PASS: fetch-simurail fallback, pinning, symlink, and atomic-output checks"
