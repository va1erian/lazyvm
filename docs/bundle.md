# Self-contained executables

`tools/bundle.py` turns an application into one executable: a copy of the
LazyVM launcher with the class library and the application appended. Users
need nothing else installed. Windows comes first. The format works on any
platform, but icons and `--gui` are Windows-only.

```powershell
python tools/bundle.py --out dist/App.exe --jar app.jar
python tools/bundle.py --out dist/App.exe --cp "build/classes;lib/a.jar" --main my.App --vm-arg=-Xmx512m -- --fixed-arg
```

| Option | Meaning |
|---|---|
| `--jar J` | The application jar. `Main-Class` is the default main class, and the jars and directories its `Class-Path` names are taken in too. |
| `--cp P` | Class path entries (directories and jars, `;`-separated on Windows). Repeatable. |
| `--main C` | The main class. |
| `--dir D` | A directory the application opens by path, not only as resources (see "Directories"). Repeatable. |
| `--vm V` | `lazyvm` (default, with `lazy.gfx`), `lazyvm-ui` (for `lazy.ui` apps) or a launcher's path. The named ones are built in release first, unless `--no-build` is given. |
| `--vm-arg A` | A VM option stored in the bundle, such as `--vm-arg=-Xmx512m` or `--vm-arg=-Dk=v`. Repeatable. |
| `--icon F` | The `.ico` file Explorer shows; `lazy.gfx` windows show it in their title bar and taskbar button too (`lazy.ui` windows, made by xui, do not yet). The default is LazyVM's (`tools/bundle/lazyvm.ico`, drawn by `tools/icon.py`). `none` leaves the icon out. |
| `--gui` | A windowed program: Windows opens no console for it, and stdout and stderr go nowhere, so launch errors are silent too. |
| `-- args` | Fixed program arguments. The arguments the executable is started with follow them. |

## Layout

The launcher checks the last 56 bytes of its own file at startup
(`crates/launcher/src/bundle.rs`). A plain `lazyvm` has no trailer and runs as
usual. A bundle ends with:

```
packed payload | packed len | lazyrt len | app len | files len | args len | CRC-32 | "LZVMBND1"
                 (six u64, little-endian; the CRC is of the unpacked payload)
```

Unpacked, the payload is `lazyrt.jar`, the application jar, the `--dir` jar
(empty if there is none), and the arguments (UTF-8, each ending in NUL: the
VM options, then the main class, then the fixed arguments).

**Size.** Every jar in the payload stores its entries uncompressed, sorted by
extension. The whole payload is then packed as one raw deflate stream at
level 9. Class files share most of their constant pools, so one stream
compresses them far better than deflating each entry: Minicraft and lazyrt go
from 3.3 MB (deflated per entry, as jars store them) to 2.4 MB. The launcher
inflates the payload with the `miniz_oxide` it already links for jars, so the
binary does not grow. Signature files (`META-INF/*.SF|RSA|DSA|EC`) are dropped,
because they no longer match once jars are merged.

The bundle sees the class library on the boot path, the merged application
jar on the application path, and `java.class.path` set to the executable
itself (then the `--dir` directory, if any). The application jar's
`Class-Path` is not followed, since the bundler already merged those jars.
When the same entry appears more than once, the first one on the class path
wins, as it does with `-cp`.

A damaged payload is an error (`damaged bundle: ...`, exit status 1), never a
panic: the lengths must fit the file and the CRC must match. The launcher
refuses a trailer claiming more than 1 GiB unpacked before it inflates
anything. The bundler refuses to build anything larger, or any entry over the
jar reader's 256 MiB limit.

## Directories

Some applications list or open their resources as files. Minicraft's port, for
example, looks for the class path directory that holds `assets/` and walks it.
A jar inside the executable can't serve that. `--dir` packs such a directory
too. On the first run of each build, the launcher unpacks it to
`%LOCALAPPDATA%\LazyVM\bundles\<name>-<hash>` (on other systems,
`$XDG_CACHE_HOME/lazyvm/bundles` or `~/.cache/lazyvm/bundles`) and puts it on
the class path after the application. It never falls back to a directory other
users can write to, such as the system temp directory, because whatever is
there goes on the class path. With none of those variables set, the bundle
refuses to start. The hash covers the packed payload, so
a rebuilt executable unpacks again, and later runs reuse the directory. The
launcher unpacks to a temporary sibling and renames it into place, so a run
never sees half a directory. Entry names that would leave the directory
(`..`, absolute paths, drive letters), or that Windows would not keep as
written (device names such as `nul.txt`, trailing dots or spaces), are refused.
Directories from older builds are not removed yet. Delete the folder by hand
to reclaim the space.

## Minicraft

The owner's Minicraft Plus port (`E:\repos\minicraft-plus-lazyvm`, built with
its `lazyvm/build.py`):

```powershell
$MC = "E:\repos\minicraft-plus-lazyvm"
python tools/bundle.py --gui --out dist/Minicraft.exe --main minicraft.core.Game --cp "$MC\build\classes;$MC\lib\annotations-23.0.0.jar;$MC\lib\tinylog-api-2.4.1.jar;$MC\lib\tinylog-impl-2.4.1.jar" --dir "$MC\src\client\resources" --vm-arg=-Xmx512m
```

The resulting file is 4.8 MB: a 2.3 MB launcher and a 2.4 MB payload.

## Checks

`python tools/bundle_test.py` bundles the launcher test's application and
checks that arguments and VM options reach it, that a `Class-Path` directory
is bundled, that `--gui` and the icon are written, that a `--dir` is unpacked
and on the class path, and that a damaged payload is refused.
