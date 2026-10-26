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

## S9. `lazy.ui` on xui

Goal: a Java app with a real window on Windows, then on LazyOS, with no AWT or Swing.

- [ ] S9-01 Design note `docs/ui.md`: read the xui README and `xui-core` at
      LazyOS's pinned rev, then map its update model, widget set, layout and
      theming onto a Java API (a `render()` method returning a node tree,
      state changes triggering a re-render, callbacks on the UI thread,
      `Ui.executor()` for completions). The note lists the xui gaps found for
      Windows ("xui rehauled", [`decisions.md`](decisions.md) D4) as issues on
      the xui repo.
      Check: the note is reviewed by the owner before S9-02 starts.
- [ ] S9-02 `xui-bind` crate and natives: Java node trees become xui nodes,
      events are routed back to Java callbacks, and Java objects are kept alive
      by handles for as long as xui holds them.
      Check: `tests/lazy/ui/Counter` in headless mode (xui's offscreen surface), comparing a pixel checksum golden.
- [ ] S9-03 Windows window host via the xui `winit-backend`: one window per
      `Ui.run(app)`, with DPI, resize, close and the clipboard.
      Check: `samples/counter` runs on Windows; `tools/ui_shot.py` captures a screenshot and checks non-blank pixels.
- [ ] S9-04 LazyOS window host via xuid (`os.lazy.display.v1` client mode, as
      `xui-app` does), with input routing and the trace markers
      `LAZYVM:UI:UP:PASS`.
      Needs: LOS-02.
      Check: LazyOS `tools/screenshot/qemu_shot.py` on the `--lazyvm-ui` demo plus `pngstats.py --min-nonblack 0.01`.
- [ ] S9-05 Widget coverage: the xui widgets apps need (labels, buttons, text
      fields, lists, scroll, tabs, menus, dialogs, canvas drawing), each with a
      headless golden.
      Check: `tests/lazy/ui/*` headless goldens.

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

## S11. Streams, `java.time`, logging, JDWP

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
      inline caches only if a benchmark proves a 20% win.
      Check: `tools/bench.py` before and after, in the PR.

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
Proposed in [`compiler.md`](compiler.md); not confirmed. It needs the D6
proposal for the `javax` compiler packages and `java.util.regex`.
Needs: S8, S11.

- [ ] S14-01 Run javac 8 (`tools.jar` from a local JDK 8, given by
      `LAZYVM_JAVAC8_TOOLS`; this repo does not carry it) on LazyVM with
      `-bootclasspath dist/lazyrt.jar`, and fill what it is missing in `lazyrt`,
      from the Javadoc. First rerun the `compiler.md` probe on the whole
      `tests/java` tree on the oracle JVM; if javac uses an internal class, stop
      and report.
      Files: `java/lazyrt/javax/{lang/model,tools,annotation/processing}`,
      `java/lazyrt/java/util/regex`, `tools/javac_on_lazyvm.py`.
      Check: `tools/javac_on_lazyvm.py` compiles every `tests/java` source with javac 8 on LazyVM and on the oracle JVM, and the class files are byte-identical, on both.

Gate: on LazyOS, `lazyvm -cp tools.jar com.sun.tools.javac.Main` compiles
`samples/notes` and the result runs, with a screenshot.

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
