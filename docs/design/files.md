# Files: the native contract (S5-05, S5-06)

`java.io` and `java.nio.file` in `lazyrt` reach the file system through
`lazy.internal.FileSystem`, whose natives are below. Paths are passed as
the strings Java code built (relative paths resolve against the process's
current directory, which is `user.dir`). On LazyOS the same calls go
through the Linux ABI.

Open files are numbered handles in a process-wide table
(`platform::files`), like descriptors. A handle that is never closed stays
open until the VM exits: `finalize` is not supported (S5-07), so streams
must be closed, as the Javadoc asks.

## Errors

An OS error becomes `java.io.IOException` (or the subclass named below)
whose message is `"<path> (<reason>)"`, the form Java 8 uses: `<reason>` is
the operating system's text for the error without a trailing period or
error number (`The system cannot find the file specified` on Windows,
`No such file or directory` on Linux).

## `lazy.internal.FileSystem` natives

Handles:

- `static native long open(String path, int mode)`: `0` read, `1` write
  (create, truncate), `2` append (create), `3` read and write (create, no
  truncate), `4` read and write, must exist. Throws
  `FileNotFoundException` with the message above; a directory is
  `FileNotFoundException("<path> (Access is denied)")` on Windows and
  `"(Is a directory)"` on Linux, as the OS reports.
- `static native int read(long h, byte[] b, int off, int len)`: bytes read,
  `-1` at end of file. `len` 0 returns 0.
- `static native void write(long h, byte[] b, int off, int len)`
- `static native long position(long h)`, `static native void seek(long h, long pos)`
- `static native long size(long h)`, `static native void truncate(long h, long len)`
- `static native int available(long h)`: bytes from the position to the end, capped at `Integer.MAX_VALUE`
- `static native void sync(long h)`
- `static native void close(long h)`: closing twice is not an error.

Paths (no handle):

- `static native int attributes(String path)`: bit 0 exists, 1 regular
  file, 2 directory, 3 symbolic link (the link itself), 4 readable, 5
  writable, 6 executable, 7 hidden; `0` when it does not exist.
- `static native long length(String path)`, `static native long lastModified(String path)` (milliseconds, 0 when missing)
- `static native boolean setLastModified(String path, long millis)`
- `static native String[] list(String path)`: names in the directory (no `.`/`..`), sorted, or null
- `static native boolean delete(String path)` (a file or an empty directory)
- `static native boolean rename(String from, String to)`
- `static native boolean mkdir(String path)`
- `static native boolean createNew(String path)`: atomically creates an empty file; false if it exists
- `static native String canonicalize(String path)`: absolute, `.`/`..` resolved, links followed for the existing part
- `static native boolean setReadOnly(String path)`
- `static native boolean setAccess(String path, int access, boolean enable, boolean ownerOnly)`:
  `access` is 4 read, 2 write, 1 execute, as in a Unix mode digit, for the
  owner or for everyone. Windows has only the read-only flag: write access
  maps to it, and read and execute access can be granted but not taken away
  (taking them away returns false), as Java 8 reports there.
- `static native String tempDir()`

Bounds of `b`, `off`, `len` are checked again by every native
(`IndexOutOfBoundsException`), and a closed or unknown handle is
`IOException("Stream Closed")`, Java 8's message.
