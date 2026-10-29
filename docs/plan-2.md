# LazyVM plan, part 2: S8-S14 and LazyOS items

Continues [`plan.md`](plan.md), which has the work-item format, the status
table and S0-S7. The same rules apply ([`../AGENTS.md`](../AGENTS.md)).

## S8. Verifier and hardening

Goal: LazyVM is safe to run class files it did not build. This stage is
required before S10's packages can install third-party jars and before S12-S13 run existing apps.

- [ ] S8-01 Structural checks (JVMS 4.8): code length, branch targets,
      exception ranges, constant-pool types per opcode, and access flags.
      Check: `cargo test -p vm verify::structural` with hand-made bad class files.
- [ ] S8-02 Type-checking verifier (JVMS 4.10.1) driven by `StackMapTable`,
      including `uninitializedThis`, subroutine-free code only, and the
      protected-access check. On failure it throws `VerifyError` with the
      method, offset and reason.
      Check: every class in `lazyrt.jar` and all tests verify; `tests/verify/bad/*` (one class per rule) all rejected.
- [ ] S8-03 Fuzzing: `cargo fuzz` targets for the parser and the verifier, plus
      a 1 h run in CI weekly.
      Check: 10 minutes of each target locally with no crash; the corpus is checked in.
- [ ] S8-04 Limits: stack depth (`StackOverflowError`), heap, class count and
      a per-method code size, all configurable and surfaced as errors, never
      aborts.
      Check: `tests/java/s8/DeepRecursion`, `HugeArray`.

Gate: `tools/test.py` with `-Xverify:all` (the default from now on) passes on both platforms.

## S11. Streams, `java.time`, logging, JDWP

Goal: the library items real apps use, a debugger, and the interpreter's
speed, before `lazy.ui` and LazyOS packaging.

S11 runs right after S8, ahead of S9 and S10, and keeps its number. By
then S4, S6 and S8 have finished the interpreter, so S11-05 tunes the
final one. Checks the verifier has proven may be dropped only where
verification cannot be turned off; malformed code is still an error,
never a panic.

- [ ] S11-01 `java.util.stream`: all stream types and collectors, sequential
      only (`parallel()` is accepted and ignored).
      Check: `tests/java/stream/*`.
- [ ] S11-02 `java.time` subset ([`decisions.md`](decisions.md) D6) and the
      `%t` conversions of `Formatter`.
      Check: `tests/java/time/*` (fixed clocks only).
- [ ] S11-03 `java.util.logging` minimal.
      Check: `tests/java/util/Logging`.
- [ ] S11-04 JDWP over a socket (`-agentlib:jdwp=transport=dt_socket,…`):
      breakpoints, stepping, stack frames, locals, and evaluate-free variable
      views, enough for IntelliJ's debugger.
      Check: `tools/jdwp_smoke.py` (a scripted JDWP client); owner check from IntelliJ recorded in the PR.
- [ ] S11-05 Performance pass: a benchmark suite (`bench/`) and quickening or
      inline caches only if a benchmark proves a 20% win. The suite is our
      own code, shaped like what games run: a sprite blit loop, a noise and
      flood-fill kernel like world generation, calls and field access, and
      `String` case mapping. Case mapping gets an ASCII fast path that also
      covers the `Locale` overloads (every locale but tr, az and lt), since
      games call it every tick. The Minicraft Plus port (GPL-3.0,
      `va1erian/minicraft-plus-revived-lz`, branch `lazyvm-port`) is an
      outside measure the owner runs with its `lazyvm/scripts/*.keys` and
      `-Dminicraft.lazyvm.stats=true`; no game code or game numbers go into
      the repo. Its frame time includes the port's own drawing loops and
      `lazy.gfx`, so its frame rate is not the interpreter's alone.
      Files: `bench/`, `tools/bench.py`, `crates/vm/src/interp`,
      `java/lazyrt/src/java/lang/StringCase.java`.
      Check: `tools/bench.py` before and after on both platforms, in the PR;
      `tests/java/lang/CaseMapping` and `StringCaseLocale`, with ASCII
      strings under tr, az and lt added and blessed.

Gate: `tools/test.py` passes on both platforms, and `tools/bench.py` shows
no regression against the S8 baseline.

## S9. `lazy.ui` on xui

Goal: a Java app with a real window on Windows, then on LazyOS, with no AWT or Swing.

- [x] S9-00 (prototype, ahead of the stage) `lazy.gfx`: pixel windows for
      games, with a program-owned loop, polled input and PNG decoding
      ([`gfx.md`](gfx.md)). Windows host on winit + softbuffer, plus a
      headless backend; the LazyOS host waits for S9-04. First user: a
      Minicraft Plus port kept outside this repo (GPL-3.0).
      Check: `tools/test.py --filter gfx` on Windows and
      `tools/lazyos_test.py --filter gfx` on LazyOS (both headless); on
      Windows, a demo window screenshot showing the scaled frame.
- [x] S9-00b (prototype, ahead of the stage) `lazy.gfx.Surface`: native
      drawing on an `int[]` (clear, fill, rect, line, blit with flips, alpha
      test, key and solid colours, and a masked blend), so that a game's
      sprites cost one call each instead of a pixel loop in the interpreter
      ([`gfx.md`](gfx.md), "Drawing"). Plain Rust in `crates/gfx/src/raster.rs`,
      no new dependency.
      Check: `cargo test -p gfx raster` (fills, masks and blits against
      pixel-by-pixel references, blending against floating-point
      source-over, lines against the classic Bresenham loop);
      `tools/test.py --filter gfx` on Windows and `tools/lazyos_test.py
      --filter gfx` on LazyOS, where `SurfaceDraw`'s pixels are worked out by
      hand from the rules; the Minicraft Plus port drawn through `Surface`
      on Windows, its frames compared with the pixel-loop version and its
      frame times recorded in the PR (the port stays outside this repo).
- [x] S9-01 Design note [`docs/ui.md`](ui.md): read the xui README and
      `xui-core` at LazyOS's pinned rev, then map its update model, widget
      set, layout and theming onto a Java API. The owner chose xui's own
      retained model (build once, change widgets directly, one upcall per
      message) over a re-rendering `render()`. The note lists the xui gaps
      found so far ("xui rehauled", [`decisions.md`](decisions.md) D4); each
      is filed as an issue on the xui repo once S9-02 confirms it in code.
      Check: the note is reviewed by the owner before S9-02 starts.
- [ ] S9-02 `xui-bind` crate and natives, behind a `ui` cargo feature: the
      run loop with its one upcall (`Ui.dispatch`), the mount op list, the
      widget registry, `set0`/`get0`, `Label`, `Button`, the arrangements and
      `Panel`, on xui's offscreen backend driven by `lazy.internal.ui.Headless`. Measure the binary size with and without the
      feature first ([`ui.md`](ui.md), "Size").
      Check: `tests/lazy/ui/Counter` headless on both platforms, comparing a
      pixel checksum golden; `tests/lazy/ui/IdleCost` (hover and scroll a
      hundred times, zero upcalls); `tests/lazy/ui/StaleId` (a message queued
      for a widget a `Panel.set` destroyed does not fire its successor); `tests/lazy/ui/CallbackThrows`
      (`Ui.run` rethrows, the program exits nonzero, no dialog headless);
      `tools/size.py` with and without the feature, in the PR.
- [ ] S9-03 Windows window host via the xui `winit-backend`: one `Ui.run` per
      process, on the main thread (a second is `IllegalStateException`), with
      DPI, resize, close, the clipboard and the uncaught-exception message
      box ([`ui.md`](ui.md), "When a callback throws"). The message box is
      one `unsafe` call to `MessageBoxW`; it lives in `crates/lazyvm-win`,
      which this item creates for the Windows host, with a `// SAFETY:` comment (shared rule 4). Measure the
      size again with `winit-backend`.
      Check: `samples/counter` runs on Windows; `tools/ui_shot.py` captures a screenshot and checks non-blank pixels;
      a sample whose callback throws and does not catch it shows the error
      message box (screenshot in the PR), and one that catches it shows none;
      `tools/ui_shot.py --resize 100` resizes the window from outside and the
      `-Dlazy.ui.stats=true` line shows zero upcalls; `tools/size.py`, in the PR.
- [ ] S9-04 LazyOS window host via xuid (`xui-app`'s `LazyOSBackend`, from
      `crates/lazyvm-lazyos`), with input routing and the trace markers
      `LAZYVM:UI:UP:PASS`.
      Needs: LOS-02.
      Check: LazyOS `tools/screenshot/qemu_shot.py` on the `--lazyvm-ui` demo plus `pngstats.py --min-nonblack 0.01`.
- [ ] S9-05 Widget coverage: the xui widgets apps need (labels, buttons, text
      fields, lists, scroll, tabs, menus, dialogs, `Canvas` with a recorded
      `Drawing`, timers, secondary windows), each with a headless golden;
      timers run on the headless driver's clock. Modal dialogs and secondary
      windows are checked on the Windows host until xui's offscreen backend
      can run them ([`ui.md`](ui.md), gap 5).
      Check: `tests/lazy/ui/*` headless goldens; dialog and secondary-window
      screenshots on Windows, in the PR.
- [ ] S9-06 `Ui.executor()`: other threads hand work to the UI thread
      (a Java-side queue; only a payload-free wake crosses xui's `Proxy`), and
      every other `lazy.ui` call off the UI thread
      throws `IllegalStateException` ([`ui.md`](ui.md), "Threads").
      Needs: S6-01, S6-06.
      Check: `tests/lazy/ui/Executor` headless (a worker thread submits 100
      label updates; the label's final text and the 100 runs, in order, are
      the golden), and `samples/counter` with a worker thread on Windows.

Gate: `samples/notes` (a small notes app with a list, an editor and file
save, using `lazy.async` for I/O) runs on Windows and LazyOS with screenshots
in the PR.

## S10. Messenger and LazyOS integration

Goal: Java apps are first-class LazyOS apps. They talk Messenger with
`CompletionStage`, are packaged as `.lzp`, and can be launched from the GUI.

- [ ] S10-01 midlc Java backend (LazyOS repo, LOS-03): it generates, per
      interface, a Java interface whose methods return `CompletionStage<…>`,
      records for the structs, enums, and codecs over the `libmessenger` TLV
      wire, and puts them in `lazy-api-generated.jar`.
      Check: LazyOS `midlc` tests; `tests/lazy/msg/CodecRoundTrip` against fixtures encoded by the Rust codecs.
- [ ] S10-02 `lazy.msg` runtime: `Messenger.connect(Class<I>)` resolves and
      binds through the registry, calls go through the begin-call / completion
      path (no thread parked per call), `@Deadline`, and errors as
      `MessengerException` with the friendly error text.
      Check: on LazyOS `tests/lazy/msg/EchoCall` against `os.lazy.echo` (both deadline and peer-death cases).
- [ ] S10-03 Topics and services: `@Subscribe` handlers discovered by
      reflection at `Messenger.register(obj)`, QoS, retained values, and
      `@Service` implementations served from the loop with replies from
      completed stages.
      Check: `tests/lazy/msg/Topics`, `ServeEcho` on LazyOS; the same tests on Windows against the loopback broker.
- [ ] S10-04 `lazy.os`: well-known paths, notifications (`os.lazy.notify.v1`),
      the clipboard, `mimed` open-with, and `Trace.pass`.
      Check: `tests/lazy/os/*` on LazyOS.
- [ ] S10-05 Packaging (LazyOS repo, LOS-04): the `LAZYOS_LAZYVM` build
      switch, a core package carrying `lazyvm` and `lazyrt.jar`, a `.jar`
      MIME type that opens with LazyVM, and `tools/lazyvm/package.py --jar app.jar
      --out app.lzp` for app packages. The app is launchable from `run_demo.py`
      and from the GUI launcher, as LazyOS's AGENTS.md requires.
      Check: LazyOS `run_demo.py --lazyvm` boots and launches `samples/notes` from the launcher, with a screenshot.

Gate: `samples/healthwatch` subscribes to `system/health/*`, shows it in a
`lazy.ui` list and posts a notification on errors, on LazyOS.

## S12. Explore: AWT on xui

Goal: decide whether `java.awt` can be built on xui, tiny-skia and
cosmic-text, far enough to run drawing code and simple games, and to be the
base S13's Swing needs. There are no native peers: every AWT window is one xui
surface, and components are drawn by Java.

- [ ] S12-01 Feasibility note `docs/awt.md`. Map onto tiny-skia and
      cosmic-text: `Graphics2D`, `Color`, `Font`/`FontMetrics`, `BasicStroke`,
      `java.awt.geom`, `AffineTransform`, `BufferedImage` and `java.awt.image`
      (raster, color models for the common types). Map onto an xui surface:
      `Toolkit`, `EventQueue` (one dispatch thread, which is the xui UI thread
      or bridged to it), `Window`/`Frame`/`Dialog`, `Component`/`Container`,
      focus, the cursor, the five common layout managers, key, mouse and window
      events, and the clipboard through `java.awt.datatransfer`. Out:
      heavyweight peers (`Button`, `TextField` and the rest of the AWT widgets
      may be drawn simply or left out), printing, drag and drop,
      `java.awt.im`, and accessibility. The note lists classes, their estimated
      size, and what S13 needs from AWT.
      Check: the note ends with a go/no-go recommendation and an estimate; the owner decides.
- [ ] S12-02 (if go) Drawing spike: `Graphics2D` shapes, strokes, transforms,
      text and images in a `Frame` + `Canvas`, with key and mouse listeners, on
      both platforms.
      Check: `tests/awt/Shapes` headless pixel goldens compared against the oracle JVM's rendering within a tolerance; `samples/awt-paint` screenshots on both platforms.
- [ ] S12-03 (if go) Containers and events: `EventQueue`, `invokeLater`,
      focus, the layout managers, `Window` events and `Dialog` modality.
      Check: `tests/awt/Layouts` (prints component bounds for fixed preferred sizes, diffed with the oracle), `EventOrder`.

## S13. Explore: Swing and media

Goal: decide whether a clean-room Swing subset, plus image I/O and sampled
sound, is worth building on S12. Swing is large: the Javadoc is the only
source (shared rule 1), so this stage starts with a measured scope, not code.
Needs: S12 go.

- [ ] S13-01 Feasibility note `docs/swing.md`: the Swing core (`JComponent`
      painting and double buffering, `RepaintManager`, `SwingUtilities`,
      `Timer`, actions, key bindings, borders), the widgets (`JFrame`,
      `JDialog`, `JPanel`, `JLabel`, `JButton` and toggles, `JTextField`,
      `JTextArea`, `JList`, `JComboBox`, `JScrollPane`, `JTabbedPane`,
      `JSplitPane`, menus, `JOptionPane`, `JFileChooser` over the platform),
      their models (`ListModel`, `ButtonModel`, `Document`), `BoxLayout`, and
      **one** look and feel (`LazyLookAndFeel`, drawn from LazyOS's `uitheme`
      palette) instead of Metal or Nimbus. Out: `JTable` and `JTree` (later
      candidates), `javax.swing.text.html`, rich text kits, pluggable look and
      feels beyond ours, and accessibility. The note estimates each part and
      lists real apps it would or would not run.
      Check: the note ends with a go/no-go recommendation; the owner decides.
- [ ] S13-02 (if go) Swing spike: `JFrame` with labels, buttons, text fields,
      a list and a menu bar, on both platforms.
      Check: model and event tests diffed with the oracle (`tests/swing/Models`, `ActionEvents`); look-and-feel pixels judged by our own goldens and screenshots, since our look differs from Metal by design.
- [ ] S13-03 (if go) `javax.imageio`: `ImageIO.read`/`write` for PNG, JPEG
      (read) and BMP via permissive Rust decoders, into `BufferedImage`.
      Check: `tests/imageio/RoundTrip` (pixel checksums diffed with the oracle for PNG and BMP).
- [ ] S13-04 (if go) `javax.sound.sampled`: `AudioSystem`, `Clip`,
      `SourceDataLine` and WAV files. On LazyOS it goes through
      `os.lazy.audio.v1` (read LazyOS `docs/agents/hardware.md`, sound); on
      Windows through WASAPI. `javax.sound.midi` stays out.
      Check: `tests/sound/WavInfo` (format parsing, diffed with the oracle); on LazyOS, LazyOS's `tools/sound/run.py`-style recording check of a played tone.

## S14. Proposed: javac 8 on LazyVM

Goal: a LazyOS machine compiles Java sources with javac 8 running on LazyVM.
Proposed in [`compiler.md`](compiler.md); the owner has not confirmed it. It
needs the D6 "Compiler support" row confirmed.

- [ ] S14-01 Run javac 8 on LazyVM and fill what it is missing in `lazyrt`,
      from the Javadoc. `tools.jar` is the oracle's: the top-level
      `lib/tools.jar` of the JDK 8 the oracle resolves to
      (`LAZYVM_ORACLE_JAVA`, else `java` on `PATH`). This repo does not
      carry it, and `tools/lazyos_test.py` stages it into the test image only.
      Every run compiles the sources under `tests/java` with
      `-bootclasspath dist/lazyrt.jar` (a jar, so javac's own jar reading is
      exercised) and `-extdirs ""` (so both VMs search the same paths), the `-sourcepath` of `tools/testlib.py` and the flags of
      `tools/javac.py`, and copies the tests' resources next to the classes
      as `testlib.copy_resources` does. Debug from what javac prints, its exit
      status, and exception messages and stack traces only: no `-Xtrace:interp`, `javap`,
      or bytecode or verifier dumps of `tools.jar` classes (shared rule 1);
      a `VerifyError` or `ClassFormatError` on one is reproduced with a class
      we compiled, without reading its bytecode details. If
      javac on LazyVM fails with `NoClassDefFoundError` for a `sun.*` or
      `com.sun.*` class outside `tools.jar`, stop and report.
      Files: `java/lazyrt/src/javax/{lang/model,tools,annotation/processing}`,
      `java/lazyrt/src/java/{lang,util,util/zip,util/jar,text,net}` as far as
      javac needs them, `tools/javac_on_lazyvm.py`, `tools/lazyos_test.py`.
      Check: on Windows, `tools/javac_on_lazyvm.py` compiles the sources with javac 8 on the oracle JVM and with javac 8 on LazyVM, and every class file is byte-identical. On LazyOS, `tools/lazyos_test.py --javac` compiles each test with javac 8 on LazyVM into a writable guest directory (`/tmp`) with its resources beside it, runs it there, and its transcript matches the test's `.out`. Local only: both need the oracle's `tools.jar`, so CI skips them.
      Needs: the owner's confirmation of S14 and the D6 "Compiler support" row; S4, S5, S7, S8, S11 (the S11-05 performance pass comes first: an interpreted javac's speed is the main risk to the stage).

Gate: `tests/java/s4/Showcase` compiled by javac 8 on LazyVM matches its
`.out`, on Windows (`tools/javac_on_lazyvm.py --filter s4.Showcase --run`)
and on LazyOS (`tools/lazyos_test.py --javac --filter s4.Showcase`).

## LazyOS-side work items

These land in the LazyOS repo. Read its `AGENTS.md` and
`docs/agents/lazyvm.md` first.

- [ ] LOS-01 Test hook: a `LAZYOS_LAZYVM` build switch that embeds a staged
      directory (the musl `lazyvm`, `lazyrt.jar`, the tests) and a `run_demo.py
      --lazyvm-test` mode that runs the suite and prints `LAZYVM:TEST:<name>:PASS|FAIL`.
- [ ] LOS-02 A display grant and launcher entry for `lazyvm` UI apps (like any
      xui app), plus a `--lazyvm-ui` demo.
- [ ] LOS-03 The `midlc` Java backend (S10-01).
- [ ] LOS-04 The core package, MIME type and app packaging (S10-05).
