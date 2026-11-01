# Third-party code

Every crate LazyVM links that is not part of this workspace, with its license.
`python tools/licenses.py` fails when a dependency is missing here or has a
license outside the allowed list (`docs/decisions.md` D2).

| Crate | License | Used by | Why |
|---|---|---|---|
| `libm` | MIT | natives | `StrictMath`: a port of musl libm (fdlibm lineage), identical results on Windows and LazyOS |
| `miniz_oxide` | MIT OR Zlib OR Apache-2.0 | classfile; gfx (through png, and flate2, which pins a second version) | Inflating deflated jar entries; png and flate2 depend on it, but PNG decoding inflates with fdeflate |
| `adler2` | 0BSD OR MIT OR Apache-2.0 | classfile, gfx (through miniz_oxide) | Adler-32, used by miniz_oxide |
| `png` | MIT OR Apache-2.0 | gfx | Decoding PNGs for `lazy.gfx.Image` |
| `fdeflate` | MIT OR Apache-2.0 | gfx (through png) | Fast inflate for PNG data |
| `flate2` | MIT OR Apache-2.0 | gfx (through png) | png's encoder dependency; LazyVM only decodes, so its code is unused |
| `crc32fast` | MIT OR Apache-2.0 | gfx (through png) | PNG chunk checksums |
| `simd-adler32` | MIT | gfx (through fdeflate, miniz_oxide) | Adler-32 for PNG data |
| `bitflags` | MIT/Apache-2.0 | gfx (through png, winit) | Flag types |
| `cfg-if` | MIT OR Apache-2.0 | gfx (through crc32fast) | Conditional compilation |
| `winit` | Apache-2.0 | gfx, Windows only | The desktop window and its input events (docs/gfx.md) |
| `softbuffer` | MIT OR Apache-2.0 | gfx, Windows only | Putting a frame of pixels in the window |
| `raw-window-handle` | MIT OR Apache-2.0 OR Zlib | gfx, Windows only (through winit, softbuffer) | Window handles shared between winit and softbuffer |
| `cfg_aliases` | MIT | gfx, Windows only (winit's build script) | Build-time cfg names |
| `cursor-icon` | MIT OR Apache-2.0 OR Zlib | gfx, Windows only (through winit) | Cursor names |
| `dpi` | Apache-2.0 AND MIT | gfx, Windows only (through winit) | Physical and logical sizes |
| `smol_str` | MIT OR Apache-2.0 | gfx, Windows only (through winit) | Key text |
| `unicode-segmentation` | MIT OR Apache-2.0 | gfx, Windows only (through winit) | Text boundaries in key input |
| `tracing` | MIT | gfx, Windows only (through winit) | winit's internal logging, unused by LazyVM |
| `tracing-core` | MIT | gfx, Windows only (through tracing) | The same |
| `pin-project-lite` | Apache-2.0 OR MIT | gfx, Windows only (through tracing) | The same |
| `mio` | MIT | platform | The reactor's poll and waker (docs/async.md) |
| `windows-sys` | MIT OR Apache-2.0 | gfx, platform, Windows only (through winit, softbuffer, mio) | Win32 bindings |
| `windows-targets` | MIT OR Apache-2.0 | gfx, Windows only (through windows-sys) | Win32 import libraries |
| `windows_x86_64_msvc` | MIT OR Apache-2.0 | gfx, Windows only (through windows-targets) | The same, for x86_64 MSVC |
| `windows-link` | MIT OR Apache-2.0 | gfx, Windows only (through windows-sys) | Win32 linking |

## xui (the `lazy.ui` S9-02 spike)

The crates `xui-bind` and `lazyvm-win` pull in through xui (docs/ui.md). Generated
from `cargo metadata` for the spike: the "Why" column is each crate's own summary
and is to be curated, and the list trimmed, before S9-02 merges.

| Crate | License | Used by | Why |
|---|---|---|---|
| `arboard` | MIT OR Apache-2.0 | xui-bind (through xui-canvas) | Image and text handling for the OS clipboard |
| `arrayref` | BSD-2-Clause | xui-bind (through tiny-skia) | Macros to take array references of slices |
| `arrayvec` | MIT OR Apache-2.0 | xui-bind (through tiny-skia) | A vector with fixed capacity, backed by an array (it can be stored on the stack too). Impl |
| `bytemuck` | Zlib OR Apache-2.0 OR MIT | xui-bind (through softbuffer) | A crate for mucking around with piles of bytes |
| `bytemuck_derive` | Zlib OR Apache-2.0 OR MIT | xui-bind (through bytemuck) | derive proc-macros for `bytemuck` |
| `clipboard-win` | BSL-1.0 | xui-bind (through arboard) | Provides simple way to interact with Windows clipboard |
| `core_maths` | MIT | xui-bind (through harfrust) | Extension trait for full float functionality in `#[no_std]` backed by `libm` |
| `cosmic-text` | MIT OR Apache-2.0 | xui-bind (through xui-canvas) | Pure Rust multi-line text handling |
| `error-code` | BSL-1.0 | xui-bind (through clipboard-win) | Error code |
| `font-types` | MIT OR Apache-2.0 | xui-bind (through read-fonts) | Scalar types used in fonts |
| `font-types` | MIT OR Apache-2.0 | xui-bind (through read-fonts) | Scalar types used in fonts |
| `fontconfig-parser` | MIT | xui-bind (through fontdb) | fontconfig file parser in pure Rust |
| `fontdb` | MIT | xui-bind (through cosmic-text) | A simple, in-memory font database with CSS-like queries |
| `gl_generator` | Apache-2.0 | xui-bind (through glutin_egl_sys) | Code generators for creating bindings to the Khronos OpenGL APIs |
| `glow` | MIT OR Apache-2.0 OR Zlib | xui-bind (through xui-canvas) | GL on Whatever: a set of bindings to run GL (Open GL, OpenGL ES, and WebGL) anywhere, and  |
| `glutin` | Apache-2.0 | xui-bind (through xui-canvas) | Cross-platform OpenGL context provider |
| `glutin_egl_sys` | Apache-2.0 | xui-bind (through glutin) | The egl bindings for glutin |
| `glutin_wgl_sys` | Apache-2.0 | xui-bind (through glutin) | The wgl bindings for glutin |
| `harfrust` | MIT | xui-bind (through cosmic-text) | A complete HarfBuzz shaping algorithm port to Rust |
| `khronos_api` | Apache-2.0 | xui-bind (through gl_generator) | The Khronos XML API Registry, exposed as byte string constants |
| `libc` | MIT OR Apache-2.0 | platform (through mio); xui-bind (through winit) | Raw FFI bindings to platform libraries like libc |
| `libloading` | ISC | xui-bind (through glutin) | Bindings around the platform's dynamic library loading primitives with greatly improved me |
| `linebender_resource_handle` | Apache-2.0 OR MIT | xui-bind (through cosmic-text) | Raw resource handle for immutable blobs with dynamic lifetimes |
| `log` | MIT OR Apache-2.0 | xui-bind (through arboard) | A lightweight logging facade for Rust |
| `memmap2` | MIT OR Apache-2.0 | xui-bind (through softbuffer) | Cross-platform Rust API for memory-mapped file IO |
| `once_cell` | MIT OR Apache-2.0 | xui-bind (through glutin) | Single assignment cells and lazy values |
| `proc-macro2` | MIT OR Apache-2.0 | xui-bind (through bytemuck_derive) | A substitute implementation of the compiler's `proc_macro` API to decouple token-based lib |
| `quote` | MIT OR Apache-2.0 | xui-bind (through wasm-bindgen-macro) | Quasi-quoting macro quote!(...) |
| `rangemap` | MIT/Apache-2.0 | xui-bind (through cosmic-text) | Map and set data structures whose keys are stored as ranges.  Contiguous and overlapping r |
| `read-fonts` | MIT OR Apache-2.0 | xui-bind (through harfrust) | Reading OpenType font files |
| `read-fonts` | MIT OR Apache-2.0 | xui-bind (through harfrust) | Reading OpenType font files |
| `roxmltree` | MIT OR Apache-2.0 | xui-bind (through fontconfig-parser) | Represent an XML as a read-only tree |
| `rustc-hash` | Apache-2.0 OR MIT | xui-bind (through cosmic-text) | A speedy, non-cryptographic hashing algorithm used by rustc |
| `self_cell` | Apache-2.0 OR GPL-2.0-only | xui-bind (through cosmic-text) | Safe-to-use proc-macro-free self-referential structs in stable Rust |
| `skrifa` | MIT OR Apache-2.0 | xui-bind (through cosmic-text) | Metadata reader and glyph scaler for OpenType fonts |
| `skrifa` | MIT OR Apache-2.0 | xui-bind (through cosmic-text) | Metadata reader and glyph scaler for OpenType fonts |
| `slotmap` | Zlib | xui-bind (through glow) | Slotmap data structure |
| `smallvec` | MIT OR Apache-2.0 | xui-bind (through harfrust) | 'Small vector' optimization: store up to a small number of items on the stack |
| `strict-num` | MIT | xui-bind (through tiny-skia-path) | A collection of bounded numeric types |
| `swash` | Apache-2.0 OR MIT | xui-bind (through cosmic-text) | Font introspection, complex text shaping and glyph rendering |
| `syn` | MIT OR Apache-2.0 | xui-bind (through windows-implement) | Parser for Rust source code |
| `syn` | MIT OR Apache-2.0 | xui-bind (through windows-implement) | Parser for Rust source code |
| `sys-locale` | MIT OR Apache-2.0 | xui-bind (through cosmic-text) | Small and lightweight library to obtain the active system locale |
| `tiny-skia` | BSD-3-Clause | xui-bind (through xui-canvas) | A tiny Skia subset ported to Rust |
| `tiny-skia-path` | BSD-3-Clause | xui-bind (through tiny-skia) | A tiny-skia Bezier path implementation |
| `tinyvec` | Zlib OR Apache-2.0 OR MIT | xui-bind (through fontdb) | `tinyvec` provides 100% safe vec-like data structures |
| `ttf-parser` | MIT OR Apache-2.0 | xui-bind (through fontdb) | A high-level, safe, zero-allocation font parser for TrueType, OpenType, and AAT |
| `unicode-bidi` | MIT OR Apache-2.0 | xui-bind (through cosmic-text) | Implementation of the Unicode Bidirectional Algorithm |
| `unicode-ident` | (MIT OR Apache-2.0) AND Unicode-3.0 | xui-bind (through wasm-bindgen-shared) | Determine whether characters have the XID_Start or XID_Continue properties according to Un |
| `unicode-linebreak` | Apache-2.0 | xui-bind (through cosmic-text) | Implementation of the Unicode Line Breaking Algorithm |
| `unicode-script` | MIT OR Apache-2.0 | xui-bind (through cosmic-text) | This crate exposes the Unicode `Script` and `Script_Extension` properties from [UAX #24](h |
| `version_check` | MIT/Apache-2.0 | xui-bind (through slotmap) | Tiny crate to check the version of the installed/running rustc |
| `windows` | MIT OR Apache-2.0 | xui-bind (through xui-canvas) | Rust for Windows |
| `windows-collections` | MIT OR Apache-2.0 | xui-bind (through windows) | Windows collection types |
| `windows-core` | MIT OR Apache-2.0 | xui-bind (through windows) | Core type support for COM and Windows |
| `windows-future` | MIT OR Apache-2.0 | xui-bind (through windows) | Windows async types |
| `windows-implement` | MIT OR Apache-2.0 | xui-bind (through windows-core) | The implement macro for the Windows crates |
| `windows-interface` | MIT OR Apache-2.0 | xui-bind (through windows-core) | The interface macro for the Windows crates |
| `windows-numerics` | MIT OR Apache-2.0 | xui-bind (through windows) | Windows numeric types |
| `windows-result` | MIT OR Apache-2.0 | xui-bind (through windows-core) | Windows error handling |
| `windows-strings` | MIT OR Apache-2.0 | xui-bind (through windows-core) | Windows string types |
| `windows-threading` | MIT OR Apache-2.0 | xui-bind (through windows-future) | Windows threading |
| `xml-rs` | MIT | xui-bind (through gl_generator) | An XML library in pure Rust |
| `xui-canvas` | MIT | xui-bind | Cross-platform software-rendered backend for xui (tiny-skia) |
| `xui-core` | MIT | xui-bind | Backend-agnostic core of xui: geometry, units, colour, layout, theming and input vocabular |
| `xui-gpu` | MIT | xui-bind (through xui-canvas) | Shared OpenGL surface seam for xui backends (context handle, frame lifecycle, offscreen re |
| `yazi` | Apache-2.0 OR MIT | xui-bind (through swash) | DEFLATE/zlib compression and decompression |
| `zeno` | Apache-2.0 OR MIT | xui-bind (through swash) | High performance, low level 2D path rasterization |
| `zune-core` | MIT OR Apache-2.0 OR Zlib | xui-bind (through zune-jpeg) | Core utilities for image processing in the zune family of crates |
| `zune-jpeg` | MIT OR Apache-2.0 OR Zlib | xui-bind (through xui-core) | A fast, correct and safe jpeg decoder |
