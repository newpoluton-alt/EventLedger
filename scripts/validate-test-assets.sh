#!/usr/bin/env bash
set -Eeuo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "${script_directory}/.." && pwd -P)"
validation_python="${EVENTLEDGER_VALIDATION_PYTHON:-python3}"

if ! command -v "${validation_python}" >/dev/null 2>&1; then
  echo "Python executable not found: ${validation_python}" >&2
  exit 1
fi

"${validation_python}" - "${repository_root}" <<'PYTHON'
import ast
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
python_files = sorted((root / "tests").rglob("*.py"))
json_files = sorted((root / "contracts").glob("*.json"))

for path in python_files:
    ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
for path in json_files:
    with path.open(encoding="utf-8") as handle:
        json.load(handle)

print(f"Validated {len(python_files)} Python files and {len(json_files)} JSON files")
PYTHON

for shell_script in "${repository_root}"/scripts/*.sh; do
  bash -n "${shell_script}"
done

if command -v node >/dev/null 2>&1; then
  node --input-type=module --check < "${repository_root}/load-tests/idempotency.js"
  echo "Validated k6 JavaScript syntax with Node"
elif command -v k6 >/dev/null 2>&1; then
  k6 inspect "${repository_root}/load-tests/idempotency.js" >/dev/null
  echo "Validated k6 JavaScript with k6"
else
  echo "Skipped JavaScript parser check: neither Node nor k6 is installed" >&2
fi

echo "Validated shell script syntax"
