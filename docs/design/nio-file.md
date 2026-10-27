# java.nio.file: behaviour beyond the Javadoc (S5-06)

The `Path`, `Files` and `java.nio.file.attribute` half of the behavioural specification whose `java.io`,
buffer and charset half is [`io.md`](io.md). The conventions are io.md's: rules read *input → observable
result*, the bracket names the test in `tests/java/nio` and its labels, and the tests follow io.md's platform
independence rules. io.md §14 (LazyOS), §15 (deferred), §16 (free) and §17 (known differences) cover this half
too.

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
(index and pattern are that regex's: not copied, io.md §17); an unclosed `[` or `{` → `"Missing ']"` or `"Missing '}"`
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

## 3. API to provide

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
