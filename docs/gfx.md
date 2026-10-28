# `lazy.gfx`: pixel windows (prototype)

Status: prototype, item S9-00 in [`plan-2.md`](plan-2.md). The API may change
before S9 settles `lazy.ui`.

## Why

`lazy.ui` (S9) is a retained widget tree on xui. Games and emulators need
something else: one buffer of pixels per frame, raw key and mouse input, and a
loop the program owns. `lazy.gfx` is that, and it is small enough to exist
before threads (S6). Its first user is a port of Minicraft Plus, a 2D game
written for AWT, whose loop is one thread doing tick, render and sleep.

## Model

- **The program owns the loop.** It draws into an `int[]`, calls
  `Window.present(pixels, w, h)`, and drains input with `Window.poll(event)`.
  Nothing calls back into Java, so no UI thread and no locking are needed. A
  window belongs to the thread that opened it.
- **Scaling is the host's job.** `present` scales the frame by nearest
  neighbour to the largest size that fits the window and keeps the aspect
  ratio, then centres it on black. Pixel art stays sharp at any window size,
  and the Java side never touches the window's pixels.
- **Input is polled.** `Event` is a mutable record that `poll` fills in, so a
  frame allocates nothing. Key codes (`Key`) name physical keys on a US
  layout, so WASD stays under the same fingers on other layouts; typed text
  arrives separately as `TEXT` events, one UTF-16 unit each. A close request
  is an event (`CLOSE`), and the window stays open, `isOpen()` true, until
  the program calls `close()`.
- **Images.** `Image.decode` reads a PNG into 0xAARRGGBB. That is the one
  format a game's sprite sheets need; others can come with `javax.imageio`
  (S13-03).
- **Evidence.** `Window.capture()` returns the window's pixels as last
  presented, scaling included, so tests and screenshots judge pixels
  (shared rule 3).
- **Memory.** A host keeps one frame outside the Java heap, so a window is at
  most 2^25 pixels (128 MiB). PNGs are capped at 2^24 pixels (64 MiB as
  ARGB), checked from the header; the Java array is allocated before
  decoding, so `-Xmx` is charged first.

## Backends

| `-Dlazy.gfx=` | Where | What |
|---|---|---|
| (unset) | Windows | A real window: winit for the window and input, softbuffer for the pixels |
| (unset) | LazyOS | Not yet: `open` throws `IllegalStateException`. The host will speak xuid's `os.lazy.display.v1` from `crates/lazyvm-lazyos` (S9-04, needs LOS-02) |
| `headless` | both | No window. Frames stay in memory for `capture()`; `-Dlazy.gfx.dump=<file>` writes the last one as a binary PPM when the window closes |

winit and softbuffer are what `xui-canvas` uses, so S9-03 can move the
Windows host onto xui without adding a second window stack. They are
Windows-only dependencies: the musl build carries only the headless backend
and the PNG decoder. On Windows they sit behind the `desktop` cargo feature
(on by default, forwarded by `natives` and `launcher`), so
`cargo build -p launcher --no-default-features` gives a smaller VM whose
windows are headless only. Without it, `open` fails like it does on LazyOS
today.

winit allows one event loop per process and never gives it back, so the loop
lives in a thread-local on the VM thread and is pumped without waiting each
time the program presents or polls. Only one desktop window can be open at a
time.

## Layout

- `java/lazyrt/src/lazy/gfx`: `Window`, `Event`, `Key`, `Image`. D10 puts the
  `lazy.*` API in `java/lazy-api`, but that tree and its build do not exist
  yet. Until they do, `lazy.*` lives in `lazyrt` beside `lazy.internal`.
- `crates/gfx`: the window table, the scaler, the headless and Windows hosts,
  and PNG decoding. It has no `unsafe` code.
- `crates/natives/src/lazy_gfx.rs`: the natives (contract in
  [`design/natives.md`](design/natives.md)).

## Not yet

- The LazyOS host (above), several windows at once on the desktop, the mouse
  in frame coordinates rather than window pixels, window icons, fullscreen.
- Sound. Minicraft Plus plays short WAV clips; a `lazy.audio` with
  `Clip.play()` over a mixer thread in Rust is the likely next prototype.
- Gamepads.
