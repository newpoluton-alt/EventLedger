#!/usr/bin/env bash
set -Eeuo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "${script_directory}/.." && pwd -P)"
test_python="${EVENTLEDGER_TEST_PYTHON:-${repository_root}/.venv/bin/python}"

if [[ ! -x "${test_python}" ]]; then
  echo "Python test environment is missing. Run:" >&2
  echo "  ${repository_root}/scripts/bootstrap-python-tests.sh" >&2
  exit 1
fi

exec "${test_python}" -m pytest \
  -c "${repository_root}/tests/pytest.ini" \
  "${repository_root}/tests" \
  "$@"
