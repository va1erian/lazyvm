# Tests

`tests/java/<pkg>/<Name>.java` is one test: a class in package `<pkg>` with a
`main`, whose transcript (stdout, then stderr and the exit status when
present) must equal `<Name>.out`. The model and the optional `// ARGS:`,
`// VMARGS:` and `// TIMEOUT:` header lines are described in
`tools/testlib.py`.

- `java.*` behaviour: write the test, then `python tools/golden.py --bless
  <file>` on the oracle JVM. Never edit such a golden by hand.
- `lazy.*` tests live under `tests/java/lazy/` and have hand-written goldens.
- Tests compile against `lazyrt`, never the JDK, so a test can only use APIs
  LazyVM provides.
- Print only deterministic output: no hash codes of objects, timings, or the
  iteration order of unordered sets unless the spec fixes it, and no stack
  frames inside JDK classes.

```bash
python tools/test.py --filter s1
python tools/golden.py --bless tests/java/s1/Hello.java
```
