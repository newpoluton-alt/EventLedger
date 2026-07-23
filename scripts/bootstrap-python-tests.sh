#!/usr/bin/env bash
set -Eeuo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "${script_directory}/.." && pwd -P)"
bootstrap_python="${EVENTLEDGER_BOOTSTRAP_PYTHON:-python3}"
virtual_environment="${repository_root}/.venv"

if ! command -v "${bootstrap_python}" >/dev/null 2>&1; then
  echo "Python executable not found: ${bootstrap_python}" >&2
  exit 1
fi

if [[ ! -x "${virtual_environment}/bin/python" ]]; then
  "${bootstrap_python}" -m venv "${virtual_environment}"
fi

"${virtual_environment}/bin/python" -m pip install \
  --disable-pip-version-check \
  --requirement "${repository_root}/tests/requirements.txt"

echo "Python test environment is ready at ${virtual_environment}"
