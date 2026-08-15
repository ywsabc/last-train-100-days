#!/usr/bin/env bash
set -euo pipefail

# NeoForm's upstream Vineflower recipe requests a 4 GiB heap. Developers on
# constrained machines can point LASTTRAIN_TOOLS_JAVA at this wrapper and set
# LASTTRAIN_TOOL_XMX (for example 1280m) without changing the locked recipe.
lasttrain_tool_heap="${LASTTRAIN_TOOL_XMX:-}"
lasttrain_real_java="${LASTTRAIN_REAL_JAVA:-$(command -v java || true)}"

if [[ -z "$lasttrain_real_java" ]]; then
  echo "java-tool-wrapper: Java was not found on PATH." >&2
  exit 1
fi
if [[ -n "$lasttrain_tool_heap" ]] \
  && [[ ! "$lasttrain_tool_heap" =~ ^[0-9]+[kKmMgG]$ ]]; then
  echo "java-tool-wrapper: LASTTRAIN_TOOL_XMX must look like 1280m or 2g." >&2
  exit 2
fi

lasttrain_tool_args=()
lasttrain_heap_replaced=false
for lasttrain_tool_arg in "$@"; do
  if [[ -n "$lasttrain_tool_heap" && "$lasttrain_tool_arg" == -Xmx* ]]; then
    lasttrain_tool_args+=("-Xmx$lasttrain_tool_heap")
    lasttrain_heap_replaced=true
  else
    lasttrain_tool_args+=("$lasttrain_tool_arg")
  fi
done
if [[ -n "$lasttrain_tool_heap" && "$lasttrain_heap_replaced" == false ]]; then
  lasttrain_tool_args=("-Xmx$lasttrain_tool_heap" "${lasttrain_tool_args[@]}")
fi

exec "$lasttrain_real_java" "${lasttrain_tool_args[@]}"
