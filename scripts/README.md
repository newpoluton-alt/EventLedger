# Test helper scripts

- `bootstrap-python-tests.sh` creates `.venv` when absent and installs only
  `tests/requirements.txt`. It never deletes or replaces an existing virtual
  environment.
- `run-python-tests.sh` runs the pytest suite from that virtual environment and
  forwards any extra pytest arguments.
- `run-k6.sh` runs the idempotency workload and forwards k6 flags placed after
  the script name.
- `validate-test-assets.sh` parses Python and Pact JSON, checks every helper with
  `bash -n`, and checks the k6 module when Node or k6 is installed.

All paths are resolved relative to the scripts, so they can be invoked from any
working directory. Executable overrides are single program paths/names, not
shell command strings.
