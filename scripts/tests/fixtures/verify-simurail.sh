#!/usr/bin/env bash
set -euo pipefail

lasttrain_fake_jar="${1:?Simurail fixture path is required}"
[[ -f "$lasttrain_fake_jar" ]]
if [[ -n "${LASTTRAIN_TEST_FAIL_SHA_PATTERN:-}" ]] \
  && [[ "$lasttrain_fake_jar" == *"$LASTTRAIN_TEST_FAIL_SHA_PATTERN"* ]]; then
  exit 1
fi
