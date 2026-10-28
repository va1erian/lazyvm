# AGENTS.md

Guidance for AI agents working on LazyVM, a VM for Java 8 bytecode written in
Rust that runs on Windows and on LazyOS. The plan and its work items are
[`docs/plan.md`](docs/plan.md) and [`docs/plan-2.md`](docs/plan-2.md); the decisions behind them are
[`docs/decisions.md`](docs/decisions.md). Pick a work item, read its stage,
and follow the rules below.

The block between the `lazyvm-rules` markers is shared: every agent file in
this repo, and LazyOS's `docs/agents/lazyvm.md`, carries an identical copy.
`python tools/check_rules.py` enforces that. Change it here first, then copy
it everywhere.

<!-- lazyvm-rules:begin -->
## Shared rules for LazyVM work

**1. What you may read and copy (clean room).**
- You may read: the JVMS SE 8, the JLS SE 8 and the Java SE 8 API Javadoc
  (docs.oracle.com/javase/specs and docs.oracle.com/javase/8/docs/api); class
  files you compiled yourself, and `javap` output of them; the observable
  behaviour of a JVM running our tests (stdout, exceptions, timing); this repo;
  LazyOS; xui; and the documentation of crates we depend on.
- You must not open, read, search, decompile or copy: OpenJDK or any other
  JDK's source (`src.zip`, github.com/openjdk, mirrors, code search results),
  decompiled or disassembled JDK classes (`rt.jar`, `jmods`), or the source of
  another class library or JVM (GNU Classpath, Apache Harmony, Android
  libcore, J9, GraalVM). This applies to snippets in web results and to code
  "from memory". Write it from the spec instead. If a spec is unclear, write
  a test, bless it on the oracle JVM and match its output.
- Do not paste spec or Javadoc prose into the repo. Write doc comments in your
  own words, and cite the section (`// JVMS 5.4.3.3`) instead.

**2. Namespace and naming.**
- Add no `public` or `protected` type, field or method to `java.*` or
  `javax.*` beyond what the Java SE 8 API declares, with its exact signature.
  Helpers are package-private or go in `lazy.internal.*`. Our API is `lazy.*`.
- Never call the product "Java" or "Java compatible", and never use Oracle
  marks in names. It is "LazyVM".

**3. What counts as done (evidence over assertion).**
- A work item is done only when its **Check** in `docs/plan.md` (or `plan-2.md`) passes,
  run by you, on every platform it names. Do not claim behaviour from reading
  the source or from a build that merely compiles.
- `java.*` behaviour is judged by differential tests: `tests/java/...` goldens
  blessed on the oracle JVM with `tools/golden.py --bless`. Never hand-edit a
  `java.*` golden to make a test pass. `lazy.*` goldens are hand-written and
  reviewed.
- From S3 onward, "both" means Windows and LazyOS (`tools/lazyos_test.py`). UI
  work is judged by pixels (goldens or screenshots), not by log lines.
- Say plainly what you could not check, for example an IntelliJ step that
  needs the owner.

**4. Small, clean, one way.**
- Files stay under 500 lines; functions are small and single-purpose;
  comments say why. There is one way to do each thing: reuse the existing
  helper, and do not add a second.
- Every `unsafe` block is minimal, lives in `crates/vm/src/heap` or
  `crates/vm/src/interp` unless the plan says otherwise, and has a
  `// SAFETY:` comment.
- Malformed class files, jars and network input are errors, never panics.
- New dependencies must have a permissive license (MIT, Apache-2.0, BSD, Zlib,
  ISC), be recorded in `THIRD_PARTY.md`, and pass `python tools/licenses.py`.
  Never add `ring`, `aws-lc` or a GPL crate outside `crates/lazyvm-lazyos`.
- The release VM binary (stripped, gzipped) plus `lazyrt.jar` stays under
  4,000,000 bytes on every platform (`python tools/size.py`, decisions.md D14).

**5. Review and merge.**
- Before every push, run the CI basics (`AGENTS.md`, "Before you push"). A
  failing gate is fixed at its cause, never skipped, ignored or loosened.
- Before opening a PR, or marking one ready, review the committed branch in
  a fresh context: the `/code-review` skill (`high` effort for the VM core,
  the class-file parser, the verifier, natives and anything parsing untrusted
  input), or a separate reviewer agent given the diff and not your
  conclusions. Fix every real finding, re-run until a pass finds nothing
  real, and note in the PR which review ran and what changed.
- A PR may be merged (auto-merge included) only when the full CI suite is
  green.
<!-- lazyvm-rules:end -->

## Workflow

1. Pick the first unchecked item of the current stage in `docs/plan.md` or `plan-2.md` whose
   `Needs` are done. Work on one item per PR, with its ID in the title.
2. Add or update the tests named in its **Check** first, bless the `java.*`
   goldens, and watch them fail.
3. Implement. Keep the change inside the files the item names. If you need
   more, say why in the PR.
4. Run the check, then `python tools/check_rules.py` and `cargo test --workspace`.
5. Tick the item in its plan file and update the stage's state in "Status".

## Before you push (required)

Run every gate that applies before each push. "It compiles" is not enough.

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
python tools/check_rules.py            # shared blocks, 500 lines, SAFETY comments
python tools/licenses.py               # permissive dependency tree
python tools/buffers.py --check        # generated java.nio buffers are up to date
python tools/lazyrt.py                 # the class library builds
python tools/test.py                   # the differential suite
python tools/test.py --jar             # the same, from tests.jar with lazyrt.jar
python tools/launcher_test.py          # -jar, manifests and jar errors
python tools/size.py                   # binary + lazyrt.jar under the 4 MB budget (D14)
python tools/lazyos_test.py --lazyos <LazyOS checkout>   # the suite inside LazyOS; slow, before each PR
cargo build -p launcher --target x86_64-unknown-linux-musl
```

`.github/workflows/ci.yml` is the authoritative list.

## Pre-PR review (required)

This mirrors LazyOS's rule. When the work is committed and the gates above
are green, run the `/code-review` skill through the `Skill` tool on the
branch (`high` with `--max-findings all` for `crates/vm`, `crates/classfile`,
`crates/natives` and any code reading files, jars or the network; the default
level elsewhere). An agent without the skill uses a separate reviewer agent at
its highest effort, given the diff only. Give the reviewer the risks to
attack: panics or unbounded allocation on malformed class files and jars,
integer overflow on sizes from input, GC roots missed across an allocation, a
`Ref` used after its object was collected, wrong JVMS semantics (arithmetic,
resolution, initialization order, exception tables), lock order and races
once threads exist, and resource exhaustion. Fix every real finding, or say in
the PR why one was declined. Then run the review again until a pass finds
nothing real. The PR description says which review ran, what it found, what
changed, and which gates ran locally.

## Commands

```bash
cargo build --workspace
cargo test --workspace
python tools/lazyrt.py                 # dist/lazyrt.jar
python tools/test.py --filter <area>   # differential suite on lazyvm
python tools/golden.py --bless tests/java/<area>/<Name>.java
python tools/lazyos_test.py --lazyos <LazyOS checkout> --filter <area>   # inside LazyOS (QEMU)
python tools/sdk.py                    # dist/lazyvm-sdk for IntelliJ (docs/intellij.md)
python tools/check_rules.py
python tools/licenses.py
python tools/unicode.py                # regenerate + verify crates/natives/data/unicode.bin on the Java 8 oracle
python tools/regex_data.py             # regenerate java.util.regex's block/script tables on the oracle (--check verifies)
```

These tools are created by S0. Until then, only `cargo build` exists.

## Layout

The repo layout is [`docs/decisions.md`](docs/decisions.md) D10. Rust code
goes in `crates/`, the `java.*` class library in `java/lazyrt`, the `lazy.*` API
in `java/lazy-api`, and tests in `tests/`. `crates/lazyvm-lazyos` is the only
GPL-3.0-or-later crate. Nothing else may depend on it.

## LazyOS

LazyVM runs on LazyOS as a static-musl `std` program through its Linux ABI,
like `xui-app`. Before touching `crates/lazyvm-lazyos`, the reactor or
`lazy.msg`, read LazyOS's `AGENTS.md`, `docs/messenger.md` (sections 1, 5, 6,
7 and 15), `docs/architecture/call-completion.md` and
`docs/architecture/endpoint-fd.md`. Work in the LazyOS repo follows that
repo's rules as well as the shared block above.
