# `lazy.ui`: Java bindings for xui (design)

Status: S9-01 in [`plan-2.md`](plan-2.md), reviewed by the owner (decisions
at the end). Nothing here is built yet. It was written against xui at
`71dc3b5903ac94dba00466a6d6a00d69251beeab`, the revision LazyOS's `xui-app`
pins, after reading its README, `docs/architecture.md`, `docs/widgets.md`,
`docs/forms.md`, `docs/headless-screenshots.md` and `xui-core`'s `Backend`,
`Ui` and `Canvas`.

The note is deliberately high level. Threads (S6) do not exist yet, so the
first version is single-threaded by design, and the API is shaped so that
threads add one method rather than change the model.

## Goals

1. **Small.** A handful of public types, one native entry per kind of
   operation, not one per widget property. The Rust side adds one crate.
2. **Cheap on a slow interpreter.** Bytecode runs only when the program's own
   logic must: on a click, a selection, a timer. Hover, focus, typing inside
   a field, scrolling, layout, resize, DPI and painting cost no bytecode.
3. **Cross-platform by construction.** The Java API names no backend. The
   same program runs on Windows, on LazyOS and headless, and the headless run
   is the test.
4. **One way.** One type per widget, used both to describe it and to change
   it later. One callback path from Rust to Java.

## What xui is, and what that means for us

xui is a retained widget toolkit with an Elm-like message loop:

- The app describes a window once as nested layouts of widget builders
  (`column().gap(8).children((label(..), button(..).on_click(Msg::Bump)))`),
  and `Ui::root` creates the widgets. A `Handle` reaches a widget the app
  changes later (`label.get().set_text(..)`).
- Events are mapped to the app's own `Msg` by closures fixed at build time.
  The runtime queues them and calls `App::update(msg, ui)`, which is **never
  re-entered**: a message raised while `update` runs waits until it returns.
- Layout is automatic. A change that can alter a natural size only marks the
  layout dirty, and one pass runs after the event that caused it.
- The backend (`Backend` trait) owns the event loop (`run` blocks until
  `quit`), the windows, the nodes, painting and text. `xui-canvas` gives a
  winit + tiny-skia backend and an `OffscreenBackend`; LazyOS's `xui-app`
  gives `LazyOSBackend` over xuid.
- Most widgets also expose a generic `Properties` surface, but xui says it is
  for designer tooling, not the primary API.

**Deviation from the plan.** S9-01 and [`decisions.md`](decisions.md) D8
assumed a `render()` method that rebuilds a node tree from state on every
change. That is not xui's model, and on our interpreter it is the expensive
choice: every change would re-run the program's whole `render`, allocate the
whole tree again and diff it, in bytecode. This note proposes xui's own
shape instead: **build once, then change widgets directly**. Dynamic parts
of a window (a list of cards, a page that swaps) are rebuilt locally by
replacing a container's children, which is what xui's `Ui::mount` /
`Mounted` already does. A diffing layer can be added later in Java on top of
this if real apps want it; the reverse is not true.

## The API, by example

```java
import lazy.ui.*;
import static lazy.ui.Ui.*;

public final class Counter {
    public static void main(String[] args) {
        Label clicks = label("0 clicks");
        int[] n = {0};
        Ui.run(window("Counter", 320, 160).content(
            column().padding(16).gap(8).add(
                clicks,
                button("Click me").onClick(() -> clicks.text(++n[0] + " clicks")))));
    }
}
```

- **A widget object is its builder and its handle.** `label("0 clicks")`
  returns a `Label`. Before the window opens, `text(..)`, `padding(..)` and
  `onClick(..)` record options in Java fields. After it opens, the same
  methods also tell xui. There is no `bind`, no `Handle`, no second type.
- **Fluent setters, plain getters.** `text(String)` sets and returns `this`;
  `text()` reads. Getters read the Java-side copy, so they cost no native
  call; values the user changes (a field's text, a check box, a selection)
  are copied into it before the callback runs.
- **Callbacks are lambdas of `java.util.function` types**: `Runnable` for a
  click, `IntConsumer` for a selection, `Consumer<String>` for text,
  `DoubleConsumer` for a slider. No listener interfaces of our own.
- **Layouts are widgets.** `column()`, `row()`, `grid(..)`, `scroll(..)`,
  `group(title, ..)` and `tabs()` hold children. `add(..)` appends before
  mount; `set(..)` replaces all children at any time, which is how a dynamic
  part of a window is rebuilt.
- Design units are xui's `Dip`, given as `int`. Colours are `0xAARRGGBB`
  `int`s, as in `lazy.gfx`.

### Public types (first version)

| Type | Role |
|---|---|
| `Ui` | Static entry: `run(Window)`, the widget factories, `after`/`every` timers, `later(Runnable)`, `theme(..)`, `clipboard`; `executor()` from S6 |
| `Window` | Title, size, content, `onClose`, `close()`, `capture()` |
| `Widget` | The base: `visible`, `enabled`, `fill`, `width`, `height`, `align`, `focus()`, `tooltip` |
| `Label`, `Button`, `CheckBox`, `Edit`, `ListView`, `Slider`, `ComboBox`, `ProgressBar` | The common widgets (S9-02 starts with `Label` and `Button`) |
| `Layout` | `column`, `row`, `grid`, `scroll`, `group`, `tabs`: one class with a kind, since they differ only in options |
| `Drawing`, `Canvas` | A recorded list of draw commands, and the widget that shows one (below) |
| `Timer` | `cancel()` |

The widget list grows with S9-05, one class per xui widget an app needs. A
type that only a few apps need (rich text, the code editor, a web view) stays
out until an app asks; each costs binary size.

## How it runs

```text
 Java (VM thread = UI thread)            Rust (crates/xui-bind)              xui
 ─────────────────────────────           ───────────────────────            ─────
 Ui.run(window) ── encode tree ──▶ run0(ops, args) ── build widgets ──▶ Backend::run
                                                                            │ events
 Ui.dispatch(id, kind, i, d, s) ◀── one upcall per message ◀── App::update ◀┘
   └▶ registry[id].fire(..) ──▶ widget setters ── set0(id, prop, ..) ──▶ widget API
```

### The loop and the thread

- `Ui.run(window)` opens the window and does not return until the last
  window closes. The thread that calls it becomes the UI thread; before S6
  that is the only thread. This matches xui, whose backends own the loop,
  and it is why `lazy.ui` differs from `lazy.gfx`, where the program owns it.
- Rust implements xui's `App` with a message that carries a Java widget id,
  an event kind and a small payload (an `int`, a `double` or a string).
  `App::update` makes **one upcall** to a single static method,
  `lazy.ui.Ui.dispatch(int id, int kind, int i, double d, String s)`, which
  indexes the registry and fires the widget's callback. Natives already
  re-enter the interpreter (`Method.invoke`, [`design/reflection.md`](design/reflection.md)),
  so this needs no new VM mechanism, only a known method to call.
- xui never re-enters `update`, so callbacks never nest, even when one
  changes a widget whose change would raise another event. A modal dialog
  runs a nested loop inside a callback; its callbacks run inside that one,
  in order, which is the same rule.
- An exception thrown by a callback ends the loop: the windows close and
  `Ui.run` rethrows it. Silent recovery hides bugs, and a test must fail.
- If nothing catches it, the program stops as for any uncaught exception
  (stack trace on stderr, nonzero exit), and, because a UI app often has no
  console, the VM first shows an xui message dialog with the exception and
  its trace when a `lazy.ui` window was opened in this run and the host is
  not headless. The dialog runs on its own after the app's windows are gone,
  so a broken app cannot break the report.
- `Ui.later(r)` queues `r` to run after the current callback, on the same
  thread. `Ui.after(ms, r)` and `Ui.every(ms, r)` use the backend's timers.

### Building: one native call per mount

`Ui.run` and `Layout.set` walk the Java tree once and encode it into a flat
`int[]` of opcodes plus an `Object[]` of strings and arrays, then make one
native call. Rust decodes it into xui builders and mounts them. So building
a window of 200 widgets costs one Java walk and one native call, not 200
calls with their argument checks, and Rust never reads Java objects field by
field. The encoding is internal (`lazy.internal.ui`) and versioned with the
crate; the opcode list is the contract in [`design/natives.md`](design/natives.md).

### Identity and lifetime

- Each mounted widget gets an `int` id from Java: its index in a registry
  array (with a free list). Rust maps the id to the xui widget it built and
  stores nothing that points into the Java heap.
- So the Java objects stay alive because the registry, a static array,
  holds them, and the GC needs no new kind of root. A window's closing, or a
  `set(..)` that replaces children, frees their slots in Java and drops the
  xui side in Rust (xui widgets destroy their nodes in `Drop`).
- A widget belongs to one window and is mounted once. Adding a mounted
  widget elsewhere is `IllegalStateException`.

### Changing a widget

After mount, a setter updates the Java copy and calls one generic native,
`set0(int id, int prop, int i, double d, Object o)`, where `prop` is a small
`int` constant per property. Rust dispatches on (widget kind, prop) to xui's
typed method; it does not go through the string-keyed `Properties` surface.
Ten setters in one callback cost ten native calls but one layout pass and one
paint, since xui batches both until the callback returns. Bad values (a
negative size, an unknown prop, a list index out of range) are Java
exceptions, checked before the call where Java can check them and in Rust
otherwise; nothing a program passes may panic the VM.

### Lists

`ListView` takes its rows pushed from Java: `rows(String[] cells, int
columns)` copies them once. xui's list is virtual, so a list of 10,000 rows
paints only what is visible, and scrolling costs no bytecode. A pulled model
(an upcall per visible cell) is not offered: it would run bytecode on every
scroll. `row(i, String...)` updates one row.

### Drawing

`Canvas` shows a `Drawing`: a recorded list of commands (fill and stroke
rects, rounded rects, ellipses, lines, paths, text, images, clips) that Java
builds into an `int[]` and hands over with one native call. Rust replays it
on every paint through xui's `Canvas` trait. Repaints caused by the window
(expose, resize, overlap) therefore cost no bytecode, and the program
re-records only when its own state changes. A pixel buffer (an `int[]` of
ARGB, a `lazy.gfx.Image`, or a `lazy.gfx.Surface`'s array) is one command,
copied at record time, so `Surface`'s native drawing serves inside a UI too.
A painter that calls Java on each paint is not offered, for the same reason
as pulled lists.

### Theme and text

`Ui.theme(Theme.LIGHT | DARK | SYSTEM)` maps to xui's `Theme`. Custom
palettes and fonts come later. Strings cross the boundary once per call as
UTF-16 to UTF-8; no Java code measures or lays out text.

## Backends

As in `lazy.gfx`, the host is picked by `-Dlazy.ui=`:

| `-Dlazy.ui=` | Where | Backend |
|---|---|---|
| (unset) | Windows | `xui-canvas`'s `WinitBackend` (S9-03) |
| (unset) | LazyOS | `xui-app`'s `LazyOSBackend` over xuid, from `crates/lazyvm-lazyos`, since `xui-app` is GPL (S9-04, needs LOS-02) |
| `headless` | both | `xui-canvas`'s `OffscreenBackend` |

`crates/xui-bind` (MIT OR Apache-2.0, like the other VM crates) depends on
`xui-core` and `xui-canvas` with its default features off, and the platform
backends plug in from `lazyvm-win` and `lazyvm-lazyos` (D4, D10). Every xui
crate must be at the same revision as LazyOS's `xui-app`, so one `xui_core`
links on LazyOS; bumping it is a change in both repos.

`lazy.gfx` and `lazy.ui` both want winit on Windows, and winit allows one
event loop per process. Until xui can be pumped from outside (gap 1 below),
a program uses one or the other: opening a `lazy.ui` window after a
`lazy.gfx` one, or the reverse, is `IllegalStateException`, on every host
(headless too), so a program that passes its tests headless does not fail
later on a desktop. The Javadoc of `Ui.run` and `lazy.gfx.Window.open` says
so, and [`gfx.md`](gfx.md) "Not yet" lists it.

## Evidence and tests

- **Headless runs are the tests.** `lazy.internal.ui.Headless` runs a window
  with no display, injects clicks, keys and text through xui's offscreen
  `Stage`, and returns `capture()`d pixels. Tests in `tests/lazy/ui` print a
  pixel checksum and the state their callbacks saw, against hand-written
  `lazy.*` goldens (shared rule 3).
- **Fonts make pixels differ per platform.** Headless tests load one font
  file from the test tree (`-Dlazy.ui.font=`), so a golden is the same on
  Windows and LazyOS and no font ships in the binary.
- **Cost is measured, not claimed.** `-Dlazy.ui.stats=true` prints, at exit,
  the upcalls and natives made. A test hovers, resizes and scrolls a mounted
  window a hundred times and asserts zero upcalls; another types ten
  characters into an `Edit` with an `onChange` and asserts ten.
- Real windows are judged by screenshots: `tools/ui_shot.py` on Windows,
  LazyOS's `qemu_shot.py` there (S9-03, S9-04).

## Threads (S6 and after)

Nothing above needs threads. When S6 lands:

- `Ui.executor()` returns an `Executor` that any thread may use. It sends a
  `Runnable` to the UI thread through xui's `Proxy` (a `Send` wake that
  coalesces bursts), and it is the executor `lazy.async` completions are
  handed to in UI code (D8).
- Every other `lazy.ui` call checks, in Java, that it is on the UI thread,
  and throws `IllegalStateException` otherwise. The check is one field
  comparison.
- Until S6, `Ui.executor()` does not exist, and blocking I/O inside a
  callback freezes the window. The note says so rather than pretending.

## Size

`xui-canvas` brings tiny-skia and cosmic-text (with font discovery and
shaping). Together they may be the largest thing in the binary, and D14's
budget is 4,000,000 bytes with the sum near 1.4 MB today. So:

- The UI sits behind a `ui` cargo feature, like `lazy.gfx`'s `desktop`, so a
  VM can be built without it.
- S9-02's PR measures `tools/size.py` with and without the feature on both
  platforms before any widget work, and stops for the owner if it passes
  80 % of the budget.
- Only the widgets apps use are bound. xui's optional crates (rich text, the
  code editor, web views, forms, scripts) stay out.

## xui gaps found so far

To be filed on `va1erian/xui` once the owner agrees (S9-01's Check). Each is
a candidate until S9-02 confirms it in code.

1. **No way to pump the loop from outside.** `Backend::run` blocks until
   quit. A `pump(timeout)` step would let `lazy.gfx`'s program-owned loop
   share xui's winit loop, and so let one process use both (and let S9-03
   move `lazy.gfx` onto xui, as [`gfx.md`](gfx.md) intends).
2. **Size controls for text.** A way to build `xui-canvas` with a smaller
   text stack, or with cosmic-text's system font discovery off, if S9-02's
   measurement shows text dominates.
3. **Panics on caller input.** The binding validates what Java passes, but
   xui's own panics on odd values (huge sizes, empty lists, out-of-range
   selections) should become errors; the binding cannot catch them all
   without `catch_unwind` around every call.
4. **Windows features for real apps** ("xui rehauled", D4): menus, file
   dialogs, the clipboard and DPI on `WinitBackend` are listed by S9-03 as
   it finds them, not guessed here.

## Not in the first version

Several windows at once (`Window.open()` from a callback; S9-05), menus and
dialogs (S9-05), accessibility, drag and drop, forms (`.lfm`), custom themes
and fonts, animation helpers, and a diffing `render()` layer.

## Decisions from the owner's review (S9-01)

1. Retained "build once, change directly", as xui itself works, with the API
   above. D8 is reworded to match.
2. An exception in a callback ends `Ui.run`, is rethrown and stops the
   program if uncaught, with an error dialog when there is a window host
   ("The loop and the thread").
3. `lazy.gfx` and `lazy.ui` are exclusive in one process until gap 1 is
   fixed, and that is documented ("Backends").
4. Headless goldens use a font from the test tree.
