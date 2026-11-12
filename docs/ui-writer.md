# LazyWriter on LazyVM: the port, its size and its speed

LazyOS's word processor, LazyWriter (`xui-app/crates/writer` and
`xui-app/src/bin/writer.rs` in the LazyOS repo, Rust), ported to Java on
`lazy.ui` as [`samples/writer`](../samples/writer), to answer one question: does
an app become smaller once it is written for LazyVM? Measured on 2026-11-11,
LazyOS at the S9 branch, xui `832c61f`, release builds, stripped.

## The port

| | LazyWriter (Rust) | `samples/writer` (Java) |
|---|---|---|
| Code | 3,224 lines in `src/` (with its inline tests) + 96 in the binary | 823 lines in 6 files |
| Editor | `xui-rich-text`, linked into the app | the same widget, in `lazyvm-ui`, as `lazy.ui.RichText` |
| Kept | window, toolbar, formatting row and its sync with the selection, page view, page setup, tables, pictures with wrap, links, `.lzw` open and save, `.txt` open, Markdown export with its pictures, shortcuts, unsaved-changes guard | all of it |
| Left out | | printing (the print spooler is a Messenger service: S10), the desktop theme (light only) |

The document never crosses into Java: Java runs commands on the editor,
hears about changes (word count, the selection's style as one packed int),
and loads and saves it whole as JSON, which the Rust side parses.

## Size

Bytes of the files as shipped on LazyOS (static musl, stripped; gzip `-9` in
brackets).

| What | Rust | Java |
|---|---|---|
| The app | `xui-writer.elf` 5,042,712 (2,332,118) | `writer.jar` 24,879 |
| Its package (`.lzp`, icons included) | 2,423,894 | 35,294 |
| Fonts it needs | 904,392 inside the binary | the same 904,392, as shared files in `/system/share/fonts/xui` |
| Shared runtime | none | `lazyvm-ui` 7,444,560 (3,328,205) + `lazyrt.jar` 1,555,168 |

So, per app, yes, by two orders of magnitude: the jar is 0.5 % of the Rust
binary, because every Rust xui app carries its own copy of xui, the LazyOS
backend and its fonts. LazyOS's own Counter is 3,113,136 bytes; the Java
Counter (`samples/counter`) is a 1,292-byte jar. Of the 40 xui programs a
LazyOS build makes, 35 are between 3.0 and 7.8 MB for the same reason.

The first Java app pays for the runtime: 9.0 MB, against 5.0 MB for the Rust
Writer alone. The Java side is smaller in total from the second app on when
the Rust apps keep embedding their fonts (2 x 5.04 MB > 9.0 + 0.9 MB of font
files + 2 jars), and from the third when fonts are counted for neither. A
LazyOS desktop already ships ~34 such apps.

What the runtime costs is mostly xui, not the VM: `lazyvm` without UI is
2,020,840 bytes; `lazyvm-ui` adds every bound widget, the rich-text editor
(~470 KB gzipped), the LazyOS backend and the text stack. Measured as D14
does (binary gzipped + `lazyrt.jar`), `lazyvm-ui` was 4,883,373 B on LazyOS
and 4,627,932 B on Windows, over the VM's 4,000,000 B. The owner gave it its
own budget of 6,000,000 B (D14), and third-party crates are now built for
size (`opt-level = "z"`, except the few the VM leans on and the
rasteriser): 4,495,902 B on
LazyOS (75 %) and 4,318,541 B on Windows (72 %); the LazyOS binary, stripped,
went from 7,444,560 to 7,159,544 bytes. The table above has the earlier
figures.

## Speed

**Start-up on LazyOS**: from `init` spawning the app (`INIT:AUTOSTART:PASS`) to
its first frame on screen (`WRITER:UP:PASS` for Rust, `LAZYVM:UI:UP:PASS` for
Java), stamped on the host every 5 ms from the serial console, 7 boots of
each image in QEMU (the two images differ only in which Writer starts):

| | median | fastest | slowest |
|---|---|---|---|
| Rust | 1,204 ms | 981 ms | 2,247 ms |
| Java | 1,425 ms | 943 ms | 3,608 ms |

The desktop session starts other programs at the same time, so boots vary a
lot; the Java Writer is about 0.2 s slower at the median: the VM starts and
loads its classes, and 900 KB of fonts are read from disk instead of being in
the binary.

**Steady state**: typing, moving the caret, hovering, scrolling, resizing and
painting run no bytecode: the editor and the widgets are the same Rust code
in both. Java runs once per message the app listens to. Headless on Windows
(the owner's desktop), typing 70 characters and running a few commands made 205
upcalls and 544 native calls, with 2.7 ms of Java in total (13 µs a message,
67 µs at most). On Windows a real window is built 61 ms after `Ui.run`
(Counter: 46 ms), and 100 resizes from outside cost zero upcalls
(`tools/ui_shot.py --resize 100`).

**Built for size.** Headless on Windows, typing 7,200 characters into the
Writer and then repainting it 100 times took 0.50 s and 0.50 s with every
crate at full speed. With third-party crates built for size it took 0.75 s
and 1.17 s; keeping the rasteriser (tiny-skia, zeno, swash) at full speed
brings the repaints back to 0.56 s (5.6 ms a frame) for 77 KB, and typing
stays at 0.70 s (0.1 ms a character). Keeping shaping and the rich-text
layout fast too would cost another 168 KB for 0.03 ms a character, so they
stay built for size. The LazyOS start-up above was measured before this.

## How to reproduce

```bash
python tools/sample.py writer                       # dist/samples/writer.jar
python tools/ui_shot.py --out build/shots/writer.png -- -jar dist/samples/writer.jar
python tools/lazyos_demo.py --lazyos <LazyOS checkout>   # stages lazyvm, lazyvm-ui, lazyrt.jar, writer.jar
# then, in the LazyOS checkout:
python tools/xui/core_packages.py
LAZYOS_DESKTOP=1 LAZYOS_LAZYVM=1 LAZYOS_XUI_AUTOSTART=writerj cargo build
python tools/screenshot/qemu_session.py --image target/lazyos.img --out shots/writerj \
    --script tools/screenshot/examples/xui_writerj.json --fail-on "WRITER:[A-Z]+:FAIL"
```
