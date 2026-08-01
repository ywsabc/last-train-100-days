#!/usr/bin/env bash
set -euo pipefail

simurail_fake_jar="${1:?Simurail path is required}"
simurail_fake_result="invalid"
if [[ -f "$simurail_fake_jar" ]] &&
  grep -qx 'simurail e68481d fixture' "$simurail_fake_jar"; then
  simurail_fake_result="valid"
fi
printf 'verify|%s|%s\n' "$simurail_fake_jar" "$simurail_fake_result" \
  >> "${LASTTRAIN_SIMURAIL_TEST_LOG:?}"
[[ "$simurail_fake_result" == "valid" ]]
