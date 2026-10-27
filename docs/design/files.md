# Files: the native contract (S5-05, S5-06)

`java.io` and `java.nio.file` in `lazyrt` reach the file system through
`lazy.internal.FileSystem`, whose natives are below. Paths are passed as
the strings Java code built (relative paths resolve against the process's
current directory, which is `user.dir`), converted exactly (`vm::wtf`): on
Windows the UTF-16 units as they are, lone surrogates included; on Unix
their generalized UTF-8, so a lone surrogate is its own three-byte form.
Names the OS gives back (listings, canonical forms) convert the other way;
bytes that are not of that form read as U+FFFD, a surrogate pair spelled as
two three-byte forms (CESU-8) included, so two different names never read
as the same string. Distinct strings never name the same file. Messages that
name a file (`FileNotFoundException`, `NoSuchFileException`) hold the path
exactly as the caller's string, lone surrogates too; so do `user.dir` and
`java.io.tmpdir`. On LazyOS the same calls go through the Linux ABI.

Open files are numbered handles in a process-wide table
(`platform::files`), like descriptors. A handle that is never closed stays
open until the VM exits: `finalize` is not supported (S5-07), so streams
must be closed, as the Javadoc asks.

## Errors

An OS error becomes `java.io.IOException` (or the subclass named below)
whose message is `"<path> (<reason>)"`, the form Java 8 uses: `<reason>` is
the operating system's text for the error without a trailing period or
error number (`The system cannot find the file specified` on Windows,
`No such file or directory` on Linux). Where `java.nio.file` shows the
reason itself (a `FileSystemException`'s reason), it is the full text as
Java 8 gives it: on Windows with its period and the line break the OS
message ends in (`...another process.` followed by `\r\n`).

## `lazy.internal.FileSystem` natives

Handles:

- `static native long open(String path, int mode)`: `0` read, `1` write
  (create, truncate), `2` append (create), `3` read and write (create, no
  truncate), `4` read and write, must exist; plus `0x10` (`SHARE_DELETE`)
  to let the file be deleted or renamed while open, which `java.nio.file`
  streams ask for and `java.io` streams do not (Windows). Another mode is
  `IllegalArgumentException`. Throws
  `FileNotFoundException` with the message above; a directory is
  `FileNotFoundException("<path> (Access is denied)")` on Windows and
  `"(Is a directory)"` on Linux, as the OS reports.
- `static native int read(long h, byte[] b, int off, int len)`: bytes read,
  `-1` at end of file. `len` 0 returns 0.
- `static native void write(long h, byte[] b, int off, int len)`
- `static native long position(long h)`, `static native void seek(long h, long pos)`
- `static native long size(long h)`, `static native void truncate(long h, long len)`
- `static native int available(long h)`: bytes from the position to the end, capped at `Integer.MAX_VALUE`
- Position, seek and truncate are the OS's for every handle, devices and
  volumes included. `truncate` first moves the position to the new length,
  so it stays there when the OS refuses the length (NUL, a read-only
  handle), and on success moves back unless it was past the new end, the
  order observed on Windows.
- On Windows NUL, whose metadata the OS refuses, has size 0; its `available`
  is the OS's error "Incorrect function", and a console (`CONOUT$`) gives
  that error for its size as well. NUL keeps a position that only seeks and
  truncates move: after `seek(10)` it is 10, `setLength(3)` fails with
  "Incorrect function" and leaves 3, and `skipBytes(4)` then returns -3.
- `static native void sync(long h)`
- `static native void close(long h)`: closing twice is not an error.

Paths (no handle):

- `static native int attributes(String path)`: bit 0 exists, 1 regular
  file, 2 directory, 3 symbolic link (the link itself), 4 readable, 5
  writable, 6 executable, 7 hidden; `0` when it does not exist.
- `static native long length(String path)`
- `static native long fileTime(String path, int which)`: `0` modified, `1`
  accessed, `2` created, in microseconds from the epoch; `Long.MIN_VALUE`
  when missing or not kept.
- `static native void setFileTime(String path, int which, long micros)`:
  directories too; a time the system cannot hold (or a missing file) is an
  `IOException` with the OS text, never a panic.
- `static native String[] list(String path)`: names in the directory (no `.`/`..`), sorted, or null
- `static native void delete(String path)`: a file or an empty directory.
  Failure is `NoSuchFileException`, `AccessDeniedException` or
  `DirectoryNotEmptyException` with the path, else an `IOException` with
  the OS text (`File.delete` turns any of them into false).
- `static native boolean rename(String from, String to)`
- `static native boolean move(String from, String to)`: a rename, which
  replaces a file in the way; false when the target is on another volume
  (the caller copies); a failure is thrown as `delete`'s are.
- `static native boolean mkdir(String path)`
- `static native boolean createNew(String path)`: atomically creates an empty file; false if it exists
- `static native String canonicalize(String path)`: of an absolute path,
  the longest existing part resolved by the OS (links, on-disk case), the
  rest appended as written. On Windows `.`/`..` are applied by name first,
  as the OS does, and names are trimmed and checked by nio-file.md PW2; on Unix the
  OS resolves the existing part as written (a `..` after a link leaves the
  link's target) and only the missing rest is resolved by name, so the
  result names the file the path opens.
- `static native String realPath(String path)`: the OS's real path of an
  existing file (`Path.toRealPath`); missing → `NoSuchFileException`,
  another failure → `IOException` whose message is the OS's reason.
- `static native String lexical(String path)`: an absolute path as
  `toRealPath` reports one it cannot resolve: `.`/`..` by name and, on
  Windows, the trimming of nio-file.md PW2 without its "Bad pathname".
- `static native boolean setReadOnly(String path)`: `setAccess(path, 2,
  false, …)`; on Windows false for a directory, as Java 8 reports.
- `static native boolean setAccess(String path, int access, boolean enable, boolean ownerOnly)`:
  `access` is 4 read, 2 write, 1 execute, as in a Unix mode digit, for the
  owner or for everyone. Windows has only the read-only flag: write access
  maps to it, and read and execute access can be granted but not taken away
  (taking them away returns false), as Java 8 reports there.

Every argument value a caller can pass is checked: a bad one is an
exception, never a VM abort. Bounds of `b`, `off`, `len` are checked again by every native
(`IndexOutOfBoundsException`), and a closed or unknown handle is
`IOException("Stream Closed")`, Java 8's message.
