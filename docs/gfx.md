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
  the program calls `close()`. On the desktop, a run of mouse moves keeps
  only the latest, and at most 4096 events wait: a program that never polls
  loses the oldest. Text leaves out control characters and Ctrl or Alt
  shortcuts; AltGr (Ctrl+Alt on Windows) still types.
- **Images.** `Image.decode` reads a PNG into 0xAARRGGBB. That is the one
  format a game's sprite sheets need; others can come with `javax.imageio`
  (S13-03).
- **Drawing.** `Surface` wraps an `int[]` of 0xAARRGGBB (its own, an
  `Image`'s, or the program's frame) and draws on it natively: `clear`,
  `fill`, `rect`, `line`, `blit` and `blendMask`, all cut to a clip
  rectangle. In an interpreter a sprite drawn pixel by pixel costs
  thousands of bytecodes; as one `blit` it costs one call. The pixels stay in
  the Java array, so the program can still touch them and present them, and
  there is no native object to free.
  - A colour's alpha decides how it lands: 0 leaves the pixel, 255 replaces
    it, anything between is drawn over it: source-over on unpremultiplied
    pixels, each channel rounded to nearest, so paint on a transparent
    surface keeps its colour.
  - `blit` flags: `FLIP_X` and `FLIP_Y` mirror the source; `ALPHA_TEST`
    copies every pixel whose alpha is not 0 as it is, as older games do
    (Minicraft's sheets mark opaque pixels with alpha 1); `KEY` turns pixels
    equal to a key colour into another colour (Minicraft's tinted white);
    `SOLID` draws every other visible pixel in one colour (flashes and
    shadows). A game sets them per call, so no option objects are needed.
  - `blendMask` draws one colour over every pixel at an opacity read from a
    mask, scaled by a strength: a light map darkened by the time of day.
  - `line` gives Bresenham's pixels, the same as AWT's `drawLine`, and
    works out the clipped part directly, so a line far off the surface
    costs no more than one across it. `rect` outlines exactly `w` by `h`
    (AWT's `drawRect` covers one more each way).
  - The heap lends natives one array at a time, so `blit` copies the part of
    the source it will draw (and `blendMask` the part of the mask) into a
    buffer kept for the next call; a surface can therefore blit onto itself.
- **Evidence.** `Window.capture()` returns the window's pixels as last
  presented, scaling included, so tests and screenshots judge pixels
  (shared rule 3).
- **Memory.** Hosts keep frames outside the Java heap: the headless host one
  frame, the desktop host softbuffer's surface plus a copy of the last frame
  for `capture()`. So a window opens at most 2^25 pixels (128 MiB per
  frame); the user may then resize a desktop window larger, up to the
  display. PNGs are capped from the header at 2^24 pixels (64 MiB as ARGB)
  and a raw row of 1 MiB, since the png crate's own limits do not cover its
  row buffers; the Java array is allocated before decoding, so `-Xmx` is
  charged first, and a panic inside the png crate becomes an `IOException`.
  `Surface` keeps one buffer per thread outside the heap, as large as the
  largest blit source or mask it has copied (see "Drawing").

## Backends

| `-Dlazy.gfx=` | Where | What |
|---|---|---|
| (unset) | Windows | A real window: winit for the window and input, softbuffer for the pixels |
| (unset) | LazyOS | Not yet: `open` throws `IllegalStateException`. The host will speak xuid's `os.lazy.display.v1` from `crates/lazyvm-lazyos` (S9-04, needs LOS-02) |
| `headless` | both | No window. Frames stay in memory for `capture()`; `-Dlazy.gfx.dump=<file>` writes the last one as a binary PPM when the program calls `close()` (not at exit) |

winit and softbuffer are what `xui-canvas` uses, so S9-03 can move the
Windows host onto xui without adding a second window stack. They are
Windows-only dependencies: the musl build carries only the headless backend
and the PNG decoder. On Windows they sit behind the `desktop` cargo feature
(on by default, forwarded by `natives` and `launcher`), so
`cargo build -p launcher --no-default-features` gives a smaller VM whose
windows are headless only. Without it, `open` fails like it does on LazyOS
today.

winit allows one event loop per process and never gives it back, so the loop
lives in a thread-local on the VM thread and is pumped, without waiting for
input, each time the program presents or polls. While the user drags or
resizes the window, Windows runs its own modal loop inside that pump, so the
program's loop stalls until the drag ends. Only one desktop window can be
open at a time.

## Layout

- `java/lazyrt/src/lazy/gfx`: `Window`, `Event`, `Key`, `Image`, `Surface`. D10 puts the
  `lazy.*` API in `java/lazy-api`, but that tree and its build do not exist
  yet. Until they do, `lazy.*` lives in `lazyrt` beside `lazy.internal`.
- `crates/gfx`: the window table, the scaler, the headless and Windows hosts,
  PNG decoding, and the drawing (`raster.rs`). It has no `unsafe` code.
- `crates/natives/src/lazy_gfx.rs` and `lazy_gfx_draw.rs`: the natives
  (contract in [`design/natives.md`](design/natives.md)).

## Not yet

- The LazyOS host (above), several windows at once on the desktop, the mouse
  in frame coordinates rather than window pixels, window icons, fullscreen.
- Sharing a process with `lazy.ui`: both need winit's one event loop, so a
  program opens windows from one or the other, and the second is
  `IllegalStateException` ([`ui.md`](ui.md), "Backends").
- Sound. Minicraft Plus plays short WAV clips; a `lazy.audio` with
  `Clip.play()` over a mixer thread in Rust is the likely next prototype.
- Gamepads.
