# Existing Android replay-shell runner

The APK extraction utility and replay-shell generator have been removed. This
directory retains only the runner for previously built, owned disposable fixtures.
Historical results are in [the evidence report](../../artifacts/adversarial/README.md).

If an existing fixture APK is available:

```sh
python3 tools/adversarial/run_shell.py path/to/shell.apk \
  --serial emulator-5580 --output build/adversarial/shell-run --expect abort
```

The runner accepts emulator serials only. It installs the disposable
`dev.lsp.adversarial.shell` fixture, uninstalls that package between runs, and clears
the emulator log buffer before launch. Native SIGABRT, Java loading errors and
inconclusive runs are classified separately. A loading error never counts as a
successful native guard rejection. Use `--expect recover` only for a historical
pre-guard fixture.
