# Pending tests

Differential tests blessed on the oracle before the classes they need exist in
`lazyrt`. `tools/test.py` only discovers `tests/java`, so these do not run yet.
Each one moves back under `tests/java/<pkg>/` in the PR that implements it. That
PR also re-blesses it, and its Check in `docs/plan.md` names it.

| Test | Waits for |
|---|---|
| `util/Scanners`, `util/ScannerNumbers` | Scanner (S5-05 `java.io` is in) |
| `util/PropertiesIO` | Properties and `java.util.Date` |
| `util/UtilBuffers` | the `Base64` and `BitSet` buffer overloads |
