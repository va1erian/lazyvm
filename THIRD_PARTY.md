# Third-party code

Every crate LazyVM links that is not part of this workspace, with its license.
`python tools/licenses.py` fails when a dependency is missing here or has a
license outside the allowed list (`docs/decisions.md` D2).

| Crate | License | Used by | Why |
|---|---|---|---|
| `libm` | MIT | natives | `StrictMath`: a port of musl libm (fdlibm lineage), identical results on Windows and LazyOS |
| `miniz_oxide` | MIT OR Zlib OR Apache-2.0 | classfile | Inflating deflated jar entries |
| `adler2` | 0BSD OR MIT OR Apache-2.0 | classfile (through miniz_oxide) | Adler-32, used by miniz_oxide |
