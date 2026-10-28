# Third-party code

Every crate LazyVM links that is not part of this workspace, with its license.
`python tools/licenses.py` fails when a dependency is missing here or has a
license outside the allowed list (`docs/decisions.md` D2).

| Crate | License | Used by | Why |
|---|---|---|---|
| `libm` | MIT | natives | `StrictMath`: a port of musl libm (fdlibm lineage), identical results on Windows and LazyOS |
| `miniz_oxide` | MIT OR Zlib OR Apache-2.0 | classfile | Inflating deflated jar entries |
| `adler2` | 0BSD OR MIT OR Apache-2.0 | classfile (through miniz_oxide) | Adler-32, used by miniz_oxide |
| `png` | MIT OR Apache-2.0 | gfx | Decoding PNGs for `lazy.gfx.Image` |
| `fdeflate` | MIT OR Apache-2.0 | gfx (through png) | Fast inflate for PNG data |
| `flate2` | MIT OR Apache-2.0 | gfx (through png) | Inflate fallback for PNG data, on miniz_oxide |
| `crc32fast` | MIT OR Apache-2.0 | gfx (through png) | PNG chunk checksums |
| `simd-adler32` | MIT | gfx (through png) | Adler-32 for PNG data |
| `bitflags` | MIT/Apache-2.0 | gfx (through png, winit) | Flag types |
| `cfg-if` | MIT OR Apache-2.0 | gfx (through crc32fast, winit) | Conditional compilation |
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
| `windows-sys` | MIT OR Apache-2.0 | gfx, Windows only (through winit, softbuffer) | Win32 bindings |
| `windows-targets` | MIT OR Apache-2.0 | gfx, Windows only (through windows-sys) | Win32 import libraries |
| `windows_x86_64_msvc` | MIT OR Apache-2.0 | gfx, Windows only (through windows-targets) | The same, for x86_64 MSVC |
| `windows-link` | MIT OR Apache-2.0 | gfx, Windows only (through windows-sys) | Win32 linking |
