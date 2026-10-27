# java.nio.file: behaviour beyond the Javadoc (S5-06)

The `Path`, `Files` and `java.nio.file.attribute` half of the behavioural specification whose `java.io`,
buffer and charset half is [`io.md`](io.md). The conventions are io.md's: rules read *input → observable
result*, the bracket names the test in `tests/java/nio` and its labels, and the tests follow io.md's platform
independence rules. io.md §14 (LazyOS), §15 (deferred) and §16 (free) cover this half too; section 3 lists the
known differences of both halves.

## 1. Paths

**P1.** Relative paths (shown with `/`): separators collapse, a trailing one goes, `.`/`..` are names; `""` has one
empty name, file name `""`, no parent or root. `getName`/`subpath` out of range → IAE. `Paths.get(first, more…)`
skips empty parts. `normalize` drops `.` and `name/..` pairs, keeps leading `..`, may give `""`. `equals` and
`compareTo` use the parsed string (`a/./b` ≠ `a/b`). [PathOps: […], normalize…, getName…, subpath…, Paths.get…]

**P2.** `resolve("")` → this, `"".resolve(x)` → x, an absolute argument wins; `resolveSibling` with no parent →
argument. `relativize` does not normalise (`a/./b` to `a/c` → `../../c`); equal → `""`; `""` to `a` → `../a`; `a`
to `""` → `../` (with the trailing separator); relative vs absolute → IAE. `startsWith`/`endsWith` match whole names
(`a/b` starts with `a/`, `ab` not with `a`); `""` starts with `""`, `a` does not; `a/b` does not end with `""`. [PathOps:
resolve…, relativize…, startsWith…, endsWith…]

**P3.** `getPathMatcher`: only `glob:` and `regex:`, lower case (`GLOB:` → `UnsupportedOperationException`), no
colon → IAE. Glob: `*`, `?` within a name, `**` across names, `[…]`, `[!…]`, `{a,b}`; nested or unclosed braces →
`PatternSyntaxException`; `*.txt` matches `.txt`; the whole path must match. [PathOps: glob…, regex, … syntax]

**P4.** Glob classes (found by review): `[!…]` never matches the separator; `/` (or the separator) inside a class →
`PatternSyntaxException("Explicit 'name separator' in class")` at its index; `]` right after `[` or `[!` closes an
empty class, which no name matches and which the oracle rejects as `"Unclosed character class"` from its own regex
(index and pattern are that regex's: not copied, section 3); an unclosed `[` or `{` → `"Missing ']"` or `"Missing '}"`
(no closing quote) at the end. `toUri` escapes, through the one helper `lazy.internal.UriText`, what a URI path
cannot hold (`[`, `]`, space) and the non-ASCII space and control chars (U+00A0, U+2028), as UTF-8 `%XX`; other
non-ASCII chars stay. [NioEdges: glob…, toUri…]

**PW1.** Windows, pinned by PathsWin (table there): roots `\`, `C:`, `C:\`, `\\host\share\`; `/a`, `C:a` relative;
`\\host\share` has no names; `resolve` of `\b` keeps the drive, of `D:b` → `D:b`; `relativize` across roots → IAE
`"'other' has different root"`, absolute vs relative → `"'other' is different type of Path"`; comparison, hash,
`startsWith`, glob and regex ignore case; `InvalidPathException` messages `"Illegal char <c> at index i: input"`,
`"Trailing char < > at index i: input"`, `"UNC path is missing hostname: input"` (or `sharename`), `getInput()` the
original; `toUri` → `file:///C:/x%20y/z`, `file://host/share/x`. Found by the implementation's fuzz (the resolve and
relativize ones pinned by the later rows): a drive-relative path's `toAbsolutePath` keeps a trailing separator when it
has no names (`C:` → `C:\cwd\`), and on a missing drive throws `IOError(IOException("Unable to get working directory
of drive 'B'"))`; `resolve` of a drive-relative argument joins only an absolute base on the same drive, appended as
written (`c:\b` + `C:` → `c:\b\`), else the argument wins; for `relativize` a drive root and a UNC root are different
types; a `/` in a glob matches the separator.

**PW2.** Found by the third review: canonical forms (`File.getCanonicalPath`) on Windows. They drop the `\\?\` and
`\\?\UNC\` (→ `\\`) prefixes and keep a UNC host and share as given. `.` and `..` are applied by name first; then a
name before another loses one final dot (`nope.\x` → `nope\x`, `nope .\x` → `nope \x`, `nope. \x` kept) and the last
name all its trailing dots and spaces. The part that exists is resolved by the OS, which reads `d2 \in` as `d2\in`; a
name of only dots and spaces (`. .\x` → `. \x`) is not looked up, and it and the rest are appended as written. First of
all, on a drive (not a share), a last name `NUL` or `NUL:` as written (its trailing dots and spaces aside, before `.`
and `..` apply) is `\\.\NUL`, even below a missing directory or a name ending in two dots (`nope..\NUL`); a `NUL` that
only `..` makes last is a name (`d2\NUL\x\..` → `d2\NUL`, `nope\nul:\in\..` → `nope\nul:`), and on a share NUL is
always a name. No canonical form (`IOException`): a wildcard; a name before another ending in two dots once `..` is
applied (`"Bad pathname"`: `nope..\x`, `d2..\in`, ` ...\x`; `nope..\..\x` is fine); a `:` in a name whose parent
exists as a directory under the parent's name as written (after the one-dot trim), a share's root included (`"The
filename, directory name, or volume label syntax is incorrect"`: `f2:x`, `f2::$DATA`, `d2::$INDEX_ALLOCATION\in`,
`C:\a:b`, `d2.\a:b`), while below a missing directory, a file, or a parent named with trailing spaces it stays
(`nope\a:b`, `f2\a:b`, `d2 \a:b` → `d2\a:b`, `d2. \in:x`). On Unix the existing part is resolved as written, so
`/t/link/../x` is the `x` beside the link's target, and a dangling link is followed to its target; a `..` after a
missing name is applied by name (`/t/nope/../top` → `/t/top`, though the path does not open). A `\\.\` path keeps its
prefix (`\\.\C:\d\NUL` names a file NUL, `C:\d\NUL` the device). `Path.toRealPath` opens the path with every name's
trailing dots and spaces ignored (`d2..\in` resolves) and reports one it cannot resolve with this trimming but no
"Bad pathname" (`nope..\x` stays). NUL in an existing directory of a drive is its own real path in a path of at most
247 units; in a longer one, or on a share, a DOS device name (CON, PRN, AUX, NUL, COM1-9, LPT1-9, CONIN$, CONOUT$,
any extension) is an ordinary name: a real file of that name (made through `\\?\`) is its real path, else
`NoSuchFileException`. [CanonWin; NioEdges: toRealPath of a missing path]

**U1.** `java.net.URI` (the part file names need; S7-02 adds the rest) parses left to right, so the leftmost bad
component is reported: scheme (`"Expected scheme name"` for a leading `:`, `"Illegal character in scheme name"`),
`"Expected scheme-specific part"` for nothing (or only a fragment) after it, then the opaque part, or `//` and the
authority (`"Expected authority"` when nothing follows), path, query, fragment, each `"Illegal character in <part>"`
at the char, `"Malformed escape pair"` at a bad `%`. [UriText]

**U2.** An authority is a server authority when it parses as `[userinfo@]host[:port]` (host: IPv4, a host name of
letters, digits and inner `-` whose last label starts with a letter when there are several, or a bracketed IPv6
literal with an optional zone); else, if its chars allow, a registry name with no host and port -1; chars legal for
neither → `"Illegal character in authority"` at its start. `parseServerAuthority` and the four-argument constructor
(which brackets an IPv6 host) require a server authority and report its error (`"Expected hostname"`, `"Illegal
character in hostname/user info/port number"`, `"Malformed port number"`, the IPv6 messages). `getAuthority`,
`getSchemeSpecificPart`, `getQuery` and `getFragment` decode escapes except inside `[...]`. Found by the third review:
a char after a host name is reported before a last label that starts with a digit (`a.1b!` at the `!`); a zone after
`%` is one or more ASCII letters and digits, checked after the address (`"scope id expected"` with index -1 when
empty, else `"Illegal character in scope id"` at the char). IPv6 literals (rules derived from about 170,000 probed
ones, `lazy.internal.UriHosts`): groups of 1 to 4 hex digits (`"IPv6 hexadecimal digit sequence too long"` at the
group), at most one `::`; after a single `:` a non-hex char is `"Expected digits for an IPv6 address"` (a second `::`
after the first: `"Expected hex digits or IPv4 address"`); an IPv4 address must end the literal in digits and dots,
else `"Expected IPv4 address"` (after `::`: `"Expected hex digits or IPv4 address"`) at its start, and then each
number is at most 255 (leading zeros allowed) with `"Malformed IPv4 address"` at the first misplaced char; a literal
that starts with one is `"Malformed IPv6 address"`; more than eight groups are `"IPv6 address too long"`, eight with
`::` `"Malformed IPv6 address"`, fewer without `"IPv6 address too short"`. [UriText: authority…, ctor host check,
decoded…]

**U3.** `equals` and `hashCode`: scheme and host ignoring case, user info, port, path, query, fragment and other
parts by raw form with escapes' hex digits ignoring case; a registry authority as a whole. `compareTo`: scheme
ignoring case (undefined first), opaque after hierarchical, then user info, host ignoring case and port (or the
authority), path, query, fragment, case-sensitive, undefined first. [UriText: … vs …]

## 2. Files

**NF1.** `NoSuchFileException`, `FileAlreadyExistsException`, `DirectoryNotEmptyException`, `NotDirectoryException`
carry the path as given; `getMessage()` is the file, `"file -> other"` when there is another, plus `": reason"`
when there is one. Copying into a missing directory → `NoSuchFileException("src -> target")`. [FilesOps; FilesWalk:
stream missing, stream on file]

**NF2.** `write(p, bytes, opts)`: default CREATE, TRUNCATE_EXISTING, WRITE; `CREATE_NEW` on an existing file →
`FileAlreadyExistsException`; `APPEND` or `WRITE` on a missing one → `NoSuchFileException`; `READ` → IAE `"READ not
allowed"`; `APPEND` + `TRUNCATE_EXISTING` → IAE `"APPEND + TRUNCATE_EXISTING not allowed"`. `write(p, lines)`
writes each line + `line.separator`, UTF-8, REPORT (`UnmappableCharacterException("Input length = 1")`).
[FilesOps: write…]

**NF3.** `readAllLines` uses L1 (no empty last line after a final terminator, empty file → `[]`) and REPORT
(`MalformedInputException("Input length = 1")`), like `newBufferedReader`; `newBufferedWriter` encodes with
REPORT. `newInputStream`: `available()` = bytes left, `skip` stops at the end, no mark; reading after close → an
`IOException` (the oracle's is `java.nio.channels.ClosedChannelException`). [FilesOps]

**NF4.** `copy`/`move`: existing target → `FileAlreadyExistsException` unless `REPLACE_EXISTING`; missing source →
`NoSuchFileException(source)`; a file copied onto itself is untouched; `COPY_ATTRIBUTES` keeps the time;
`ATOMIC_MOVE` works within a directory; the stream forms of `copy` return the byte count. [FilesOps: copy…, move…]

**NF5.** `createDirectory` on an existing name → FAE, missing parent → NSFE; `createDirectories` accepts an existing
directory, an existing file → FAE; `createFile` existing → FAE; `delete` missing → NSFE, non-empty → DNEE;
`deleteIfExists` → true/false. `isSameFile` of equal paths → true without I/O, otherwise both must exist. Temp names:
prefix (`""` if null) + digits + suffix (`.tmp` if null); directories have no suffix. [FilesOps]

**NF6.** `readAttributes(p, "a,b")` (or `"basic:a,b"`) returns those keys only; unknown name → IAE `"'x' not
recognized"`, unknown view → `UnsupportedOperationException("View 'v' not available")`;
`getAttribute`/`setAttribute` know `lastModifiedTime`. `FileTime.toString()`: ISO-8601 UTC, seconds always, a
fraction only if non-zero and without trailing zeros (`.0015`), times before 1970 too; equality ignores the unit.
[FilesOps: attribute…, FileTime…]

**NF7.** `DirectoryStream` yields `dir.resolve(name)` per entry, no `.`/`..`, in directory order (LazyVM: sorted);
a glob filter sees the file name only (`a/*` matches nothing). Second `iterator()` → `IllegalStateException(
"Iterator already obtained")`; `iterator()` after close → `"Directory stream is closed"`; after close `hasNext()`
false; `next()` at the end → `NoSuchElementException`; `remove()` → `UnsupportedOperationException`. [FilesWalk:
stream…]

**NF8.** `walkFileTree`: `preVisitDirectory(start)`, each entry in order (directories entered in place),
`postVisitDirectory`. Entries at depth `maxDepth` get `visitFile`, directories too; depth 0 → `visitFile(start)`; a
file start → `visitFile` only; negative → IAE `"'maxDepth' is negative"`. `SKIP_SUBTREE` from pre-visit also skips
the post-visit; `SKIP_SIBLINGS` from `visitFile` skips the rest, the parent's post-visit still runs; `TERMINATE`
stops at once. Missing start → `visitFileFailed(start, NoSuchFileException)`, rethrown by `SimpleFileVisitor`.
Returns `start`. [FilesWalk: walk…]

**NF9.** Found by review. Temp names: a prefix or suffix that is not part of one plain name (a separator, `..`) →
`IllegalArgumentException("Invalid prefix or suffix")`, so the file stays in its directory. `move` with
`REPLACE_EXISTING` deletes what is in the way first, so a rename that then fails (the source open in `java.io`)
leaves the target gone; a directory moved into itself → `FileSystemException`, nothing created. `delete` of a file
another handle holds → `FileSystemException(path, null, OS text)` (Windows ends it in `\r\n`); of a read-only file
on Windows → `AccessDeniedException`. `getAttribute(p, "*")` or two names → IAE with the name;
`setAttribute` knows the three times, else IAE `"'basic:x' not recognized"`; a directory's times can be set. A null
option → NPE; an unknown `OpenOption` → `UnsupportedOperationException` (no message); stream `copy` allows
`REPLACE_EXISTING` only (`"ATOMIC_MOVE not supported"`). `FileTime.toString` trims the fraction (`.1Z` for 100 ms).
[NioEdges: temp…, move into itself, getAttribute…, … option…, FileTime…]

**NF10.** Found by the second review. A failed rename is `NoSuchFileException`, `AccessDeniedException` (both with
source and target) or `FileSystemException` with the OS's reason, as the OS reports it (a missing target directory
is NSFE; a source open in `java.io` is the sharing violation). `ATOMIC_MOVE` is the rename alone: no existence or
same-file check first (a missing source is `"source -> target"`; a case-only one renames), and a directory in the way
(not inside the source) → AccessDenied. `copy` into a parent that is a file → NSFE. On Windows `CREATE_NEW` onto a
directory → AccessDenied. `toRealPath` reports a missing path absolute and normalized; on Windows one below a file is
`FileSystemException(path, null, "The directory name is invalid.\r\n")` (PW2 has the rest of `toRealPath`).
`available()` of a `Files` stream is size minus position, so `readAllBytes(NUL)` is empty. [NioEdges: move into a
missing directory…; FilePathsWin: NUL…]

## 3. Known differences

Where LazyVM knowingly differs from the oracle; the reviewer's probes show each one.

- The default charset is UTF-8 everywhere (D12, N3).
- Huge times: `File.setLastModified(Long.MAX_VALUE)` and `Files.setLastModifiedTime` of a time Windows cannot hold
  are false or `FileSystemException("The parameter is incorrect.")`; the oracle reports success and stores a wrong
  time.
- `Files.exists(Paths.get("NUL"))` is false (the oracle says true; `new File("NUL").exists()` is false on both).
- The `dos` attribute view is not provided (`UnsupportedOperationException("View 'dos' not available")`).
- The index and pattern of the `PatternSyntaxException` for an empty glob class (nio-file.md P4).
- An array too large for LazyVM's heap is `"Java heap space"` where the oracle's larger default heap allocates it
  (from `Integer.MAX_VALUE - 1` elements, in any dimension of `multianewarray` too, both say `"Requested array size
  exceeds VM limit"`).
- NUL through `java.nio.file`: `Files.write` and `newOutputStream` succeed (the oracle: `"Incorrect function."`), and
  `Files.size` is `NoSuchFileException` (the oracle: `"The parameter is incorrect."`).
- Windows junctions are symbolic links to LazyVM (the oracle: not a link, "other").
- `Files.delete` of a read-only file that another handle holds is `AccessDeniedException` (the oracle reports the
  sharing violation first).
- Canonical forms: a `<` or `>` in a name is not matched as a wildcard (the oracle's `C:\<` can come back as a real
  directory), and some names of dots that the oracle refuses with `"Invalid argument"` are `"Bad pathname"`. A mapped
  network drive resolves to its share (`Z:\x` → `\\server\share\x`; the oracle keeps the drive) and a `:` below its
  root follows PW2's rule (the oracle keeps the name); `toRealPath` of `NUL.` is the NUL path (the oracle: `"The
  parameter is incorrect."`).
- `URI.toASCIIString` of a lone surrogate escapes it as `%3F`; the oracle throws `NullPointerException`.
- Canonical forms not matched (the third and fourth reviews' fuzz): a `\\?\` prefix is dropped (the oracle refuses
  it with `"Invalid argument"`); some names of dots or spaces that the oracle refuses
  with `"Invalid argument"` are `"Bad pathname"` or kept; a non-last `NUL ` keeps its space; a `nul:` below a
  file or under a name with trailing dots is kept (the oracle: the syntax error); and `toRealPath` of a missing
  `NUL` is `NoSuchFileException` where the oracle sometimes reports `"\\.\NUL: The parameter is incorrect."`.
- `getFileAttributeView` and the attribute view classes are not provided (out of scope with the non-basic views).
- The length of a directory is what the file system reports (0 on NTFS, often 4096 on Linux).
- On Unix, file names are bytes: a string becomes its generalized UTF-8, so a lone surrogate is its own name and
  comes back from a listing (LazyOS's file system, which takes UTF-8 names only, refuses it: "No such file or
  directory"). Every string maps to one name, but not every name to one string: bytes that are not of that form
  (made by another program, a pair spelled as two three-byte surrogates too) read as U+FFFD, so `x\xff`,
  `x\xfe` and a real `x\xef\xbf\xbd` all list as `x\uFFFD`, and a CESU-8 name as `FFFD FFFD FFFD DC00`, which a
  real name of those units also gives. That is inherent unless names were escaped; a listed name that does not
  round-trip may not open. A relative path under a current directory whose name is not UTF-8 resolves against
  that lossy `user.dir`.
- Unix devices (no Linux oracle): a block device's `length()` is what `fstat` reports, 0, so `skipBytes` at
  the end can return a negative count, and `setLength` on one fails with the OS's error.
- Stack traces show LazyVM's line numbers and frames.
- How often a wrapped stream is called is matched only where a rule above pins it; LazyVM has no finalizers, so an
  unreferenced stream is not closed; a file open in `java.io` cannot be deleted on Windows (nio streams allow it).
- Speed: LazyVM's interpreter runs about 15–30 times slower than the oracle with `-Xint`; bulk buffer copies and
  file copies are close to it. `getCanonicalPath` costs what the OS lookups cost, about the oracle's speed with its
  canonical cache off (`-Dsun.io.useCanonCaches=false`); the oracle caches results by default, LazyVM does not.

## 4. API to provide

The `java.nio.file` rows of io.md §18's table, with the same conventions.

| Package | Class | Kind | Members |
|---|---|---|---|
| java.nio.file | `Path` | interface extends Comparable<Path>, Iterable<Path>, Watchable | compareTo(Path): int; endsWith(Path): boolean; endsWith(String): boolean; equals(Object): boolean; getFileName(): Path; getFileSystem(): FileSystem; getName(int): Path; getNameCount(): int; getParent(): Path; getRoot(): Path; hashCode(): int; isAbsolute(): boolean; iterator(): Iterator<Path>; normalize(): Path; relativize(Path): Path; resolve(Path): Path; resolve(String): Path; resolveSibling(Path): Path; resolveSibling(String): Path; startsWith(Path): boolean; startsWith(String): boolean; subpath(int,int): Path; toAbsolutePath(): Path; toFile(): File; toRealPath(LinkOption...): Path; toString(): String; toUri(): java.net.URI |
| java.nio.file | `Paths` | final class | static get(String,String...): Path; static get(java.net.URI): Path |
| java.nio.file | `Files` | final class | static copy(InputStream,Path,CopyOption...): long; static copy(Path,OutputStream): long; static copy(Path,Path,CopyOption...): Path; static createDirectories(Path,FileAttribute<?>...): Path; static createDirectory(Path,FileAttribute<?>...): Path; static createFile(Path,FileAttribute<?>...): Path; static createTempDirectory(Path,String,FileAttribute<?>...): Path; static createTempDirectory(String,FileAttribute<?>...): Path; static createTempFile(Path,String,String,FileAttribute<?>...): Path; static createTempFile(String,String,FileAttribute<?>...): Path; static delete(Path): void; static deleteIfExists(Path): boolean; static exists(Path,LinkOption...): boolean; static getAttribute(Path,String,LinkOption...): Object; static getLastModifiedTime(Path,LinkOption...): FileTime; static isDirectory(Path,LinkOption...): boolean; static isExecutable(Path): boolean; static isHidden(Path): boolean; static isReadable(Path): boolean; static isRegularFile(Path,LinkOption...): boolean; static isSameFile(Path,Path): boolean; static isSymbolicLink(Path): boolean; static isWritable(Path): boolean; static move(Path,Path,CopyOption...): Path; static newBufferedReader(Path): BufferedReader; static newBufferedReader(Path,Charset): BufferedReader; static newBufferedWriter(Path,Charset,OpenOption...): BufferedWriter; static newBufferedWriter(Path,OpenOption...): BufferedWriter; static newDirectoryStream(Path): DirectoryStream<Path>; static newDirectoryStream(Path,DirectoryStream.Filter<? super Path>): DirectoryStream<Path>; static newDirectoryStream(Path,String): DirectoryStream<Path>; static newInputStream(Path,OpenOption...): InputStream; static newOutputStream(Path,OpenOption...): OutputStream; static notExists(Path,LinkOption...): boolean; static readAllBytes(Path): byte[]; static readAllLines(Path): List<String>; static readAllLines(Path,Charset): List<String>; static <A extends BasicFileAttributes> readAttributes(Path,Class<A>,LinkOption...): A; static readAttributes(Path,String,LinkOption...): Map<String, Object>; static setAttribute(Path,String,Object,LinkOption...): Path; static setLastModifiedTime(Path,FileTime): Path; static size(Path): long; static walkFileTree(Path,FileVisitor<? super Path>): Path; static walkFileTree(Path,Set<FileVisitOption>,int,FileVisitor<? super Path>): Path; static write(Path,Iterable<? extends CharSequence>,Charset,OpenOption...): Path; static write(Path,Iterable<? extends CharSequence>,OpenOption...): Path; static write(Path,byte[],OpenOption...): Path |
| java.nio.file | `FileSystems` | final class | static getDefault(): FileSystem |
| java.nio.file | `FileSystem` | abstract class implements Closeable | protected new(); abstract close(): void; abstract getPath(String,String...): Path; abstract getPathMatcher(String): PathMatcher; abstract getRootDirectories(): Iterable<Path>; abstract getSeparator(): String; abstract isOpen(): boolean; abstract isReadOnly(): boolean |
| java.nio.file | `PathMatcher` | interface | matches(Path): boolean |
| java.nio.file | `DirectoryStream` | interface extends Closeable, Iterable<T> | iterator(): Iterator<T> |
| java.nio.file | `DirectoryStream.Filter` | interface | accept(T): boolean |
| java.nio.file | `FileVisitor` | interface | postVisitDirectory(T,IOException): FileVisitResult; preVisitDirectory(T,BasicFileAttributes): FileVisitResult; visitFile(T,BasicFileAttributes): FileVisitResult; visitFileFailed(T,IOException): FileVisitResult |
| java.nio.file | `SimpleFileVisitor` | class implements FileVisitor<T> | protected new(); postVisitDirectory(T,IOException): FileVisitResult; preVisitDirectory(T,BasicFileAttributes): FileVisitResult; visitFile(T,BasicFileAttributes): FileVisitResult; visitFileFailed(T,IOException): FileVisitResult |
| java.nio.file | `FileVisitResult` | enum | constants CONTINUE TERMINATE SKIP_SUBTREE SKIP_SIBLINGS |
| java.nio.file | `FileVisitOption` | enum | constants FOLLOW_LINKS |
| java.nio.file | `OpenOption` | interface |  |
| java.nio.file | `CopyOption` | interface |  |
| java.nio.file | `StandardOpenOption` | enum implements OpenOption | constants READ WRITE APPEND TRUNCATE_EXISTING CREATE CREATE_NEW DELETE_ON_CLOSE SPARSE SYNC DSYNC |
| java.nio.file | `StandardCopyOption` | enum implements CopyOption | constants REPLACE_EXISTING COPY_ATTRIBUTES ATOMIC_MOVE |
| java.nio.file | `LinkOption` | enum implements OpenOption, CopyOption | constants NOFOLLOW_LINKS |
| java.nio.file | `FileSystemException` | class extends IOException | new(String); new(String,String,String); getFile(): String; getMessage(): String; getOtherFile(): String; getReason(): String |
| java.nio.file | `NoSuchFileException` | class extends FileSystemException | new(String); new(String,String,String) |
| java.nio.file | `FileAlreadyExistsException` | class extends FileSystemException | new(String); new(String,String,String) |
| java.nio.file | `DirectoryNotEmptyException` | class extends FileSystemException | new(String) |
| java.nio.file | `NotDirectoryException` | class extends FileSystemException | new(String) |
| java.nio.file | `AccessDeniedException` | class extends FileSystemException | new(String); new(String,String,String) |
| java.nio.file | `AtomicMoveNotSupportedException` | class extends FileSystemException | new(String,String,String) |
| java.nio.file | `InvalidPathException` | class extends IllegalArgumentException | new(String,String,int); new(String,String); getIndex(): int; getInput(): String; getMessage(): String; getReason(): String |
| java.nio.file | `FileSystemLoopException` | class extends FileSystemException | new(String) |
| java.nio.file | `DirectoryIteratorException` | final class extends ConcurrentModificationException | new(IOException); getCause(): IOException |
| java.nio.file.attribute | `BasicFileAttributes` | interface | creationTime(): FileTime; fileKey(): Object; isDirectory(): boolean; isOther(): boolean; isRegularFile(): boolean; isSymbolicLink(): boolean; lastAccessTime(): FileTime; lastModifiedTime(): FileTime; size(): long |
| java.nio.file.attribute | `FileTime` | final class implements Comparable<FileTime> | compareTo(FileTime): int; equals(Object): boolean; static from(java.time.Instant): FileTime; static from(long,TimeUnit): FileTime; static fromMillis(long): FileTime; hashCode(): int; to(TimeUnit): long; toInstant(): java.time.Instant; toMillis(): long; toString(): String |
| java.nio.file.attribute | `FileAttribute` | interface | name(): String; value(): T |
