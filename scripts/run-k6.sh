#!/usr/bin/env bash
set -Eeuo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "${script_directory}/.." && pwd -P)"
k6_executable="${EVENTLEDGER_K6_EXECUTABLE:-k6}"

if ! command -v "${k6_executable}" >/dev/null 2>&1; then
  echo "k6 executable not found: ${k6_executable}" >&2
  echo "Install k6 or set EVENTLEDGER_K6_EXECUTABLE." >&2
  exit 1
fi

exec "${k6_executable}" run "$@" "${repository_root}/load-tests/idempotency.js"
