#!/usr/bin/env bash
set -euo pipefail

lasttrain_fake_output="${1:?output JAR is required}"
mkdir -p "$(dirname "$lasttrain_fake_output")"
printf 'simurail e68481d fixture\n' > "$lasttrain_fake_output"
