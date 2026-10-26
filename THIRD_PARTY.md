# Third-party code

Every crate LazyVM links that is not part of this workspace, with its license.
`python tools/licenses.py` fails when a dependency is missing here or has a
license outside the allowed list (`docs/decisions.md` D2).

| Crate | License | Used by | Why |
|---|---|---|---|
| `libm` | MIT | natives | `StrictMath`: a port of musl libm (fdlibm lineage), identical results on Windows and LazyOS |
