# Pending tests

Differential tests blessed on the oracle before the classes they need exist in
`lazyrt`. `tools/test.py` only discovers `tests/java`, so these do not run yet.
Each one moves back under `tests/java/<pkg>/` in the PR that implements it. That
PR also re-blesses it, and its Check in `docs/plan.md` names it.

| Test | Waits for |
|---|---|
| `util/Scanners`, `util/ScannerNumbers` | S5-05 `java.io` (`Readable`, `CharBuffer`, decoders) |
| `util/PropertiesIO` | S5-05 `java.io` (`BufferedWriter`, Latin-1 streams), `java.util.Date` |
| `util/UtilBuffers` | S5-06 `java.nio` buffers |
