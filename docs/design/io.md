# java.io and java.nio: behaviour beyond the Javadoc (S5-05, S5-06)

This is the behavioural specification for LazyVM's `java.io`, heap `java.nio` buffers, `java.nio.charset` and
`java.nio.file`. It lists where the Java 8 oracle (Temurin 1.8.0_504, Windows) is more specific than the Java SE 8
Javadoc and a test can see it: calls made on wrapped streams, buffer sizes, replacement output, path strings,
messages. Implement the Javadoc, then these rules. Files are reached through `lazy.internal.FileSystem`
(`files.md`).

Rules read *input → observable result*; the bracket at the end names the test in `tests/java/io` or
`tests/java/nio` and its line labels. *Unpinned* means no test checks it. Call logs: `rN=k` is a `read(b, off, N)`
on the wrapped stream that returned `k`, `wN` a write of N units, `fl` a `flush()`, `av` an `available()`. In
UTF-8 rules `?` is one U+FFFD. Section 16 lists what is free.

**Platform independence.** Goldens are blessed on Windows and must pass inside LazyOS too (Unix paths, `\n` line
separator, Linux error texts). The tests work only in a fresh directory from `File.createTempFile` or
`Files.createTempDirectory` and delete it; print paths relative to it (`<tmp>`) with the separator shown as `/`;
build inputs from relative paths and `File.separator`; show `line.separator` as `$`; print an OS `IOException` as
its class, or as `"<path> (<os reason>)"` after checking that shape; and use names on which NTFS order and sorted
order agree. Windows-only path rules (W1, PW1) are in `FilePathsWin` and `PathsWin`, which compare with an embedded
table only when `File.separatorChar == '\\'` and print the same summary line either way. Their Unix side is
unpinned: no Linux oracle was available.

## 1. Stream defaults

**S1.** `InputStream.read(b, off, len)`: null `b` → NPE (also for `len` 0); `off < 0`, `len < 0` or `len >
b.length - off` → `IndexOutOfBoundsException`; `len` 0 → 0, no `read()`. Else `read()` until `len` bytes or −1 (−1
first → −1). An `IOException` from a later `read()` ends the call with the count so far and is dropped; from the
first it propagates. [Streams: read…]

**S2.** `InputStream.skip(n)`: `n ≤ 0` → 0, no calls; else `read(buf, 0, min(2048, left))` until done or < 0.
`available()` 0, `markSupported()` false, `mark` no-op, `reset()` → `IOException("mark/reset not supported")`,
`close()` no-op (still readable). [Streams: skip…, available, reset, close twice]

**S3.** `OutputStream.write(b, off, len)`: null `b` → NPE; bounds → IOOBE (`off + len > b.length`); else one
`write(int)` per byte with the int unchanged (300 stays 300). `flush`, `close` no-ops. [Streams: out…]

**S4.** `Reader`: `read()` → `read(c, 0, 1)` on a new 1-char array; `read(CharBuffer t)` → one `read(tmp, 0,
t.remaining())`. `skip(n < 0)` → `IllegalArgumentException("skip value is negative")`; else a buffer of `min(n,
8192)`, reads of `min(left, 8192)`. `ready()` false; `mark` → `IOException("mark() not supported")`, `reset` →
`IOException("reset() not supported")`. [Streams: reader…]

**S5.** `Writer`: `write(int)` keeps 16 bits and calls `write(arr, 0, 1)` on a 1024-char array it reuses;
`write(String, off, len)` with `len ≤ 1024` uses that array, longer ones a new array of exactly `len` (subclasses
see `arr.length`). Out of range → `StringIndexOutOfBoundsException("String index out of range: " + (off + len))`.
`append(null)` writes `"null"`, `append(null, s, e)` its substring; `append` returns `this`. [Streams: writer…]

**S6.** `ByteArrayInputStream`: `read(b, off, 0)` at the end → −1; `skip(n < 0)` → 0; `reset()` unmarked → back to
the constructor offset. `new ByteArrayOutputStream(-1)` → `IllegalArgumentException("Negative initial size: -1")`.
`StringReader`: negative `skip` moves back, not past 0, returning the distance; `reset()` unmarked → 0; `ready()`
true when empty; after `close` → `IOException("Stream closed")`; `mark(-1)` → `IllegalArgumentException("Read-ahead
limit < 0")`. `CharArrayReader`: `skip(n < 0)` → 0; `reset()` unmarked → offset. [Streams: bais…, baos…;
ReadLines: StringReader…, CharArrayReader…]

## 2. Buffered streams

**B1.** Default sizes 8192 (bytes or chars) for all four; size ≤ 0 → `IllegalArgumentException("Buffer size <=
0")`. [BufferedCalls: …default…, … size 0]

**B2.** `BufferedInputStream` refills ask for the buffer's free space (`r8192` when empty). `read(b, off, len)`
(`len` 0 → 0, no calls) runs steps. A step copies from the buffer; on an empty buffer it reads straight into `b`
when the wanted rest ≥ buffer length and no mark is set, else refills. After a step: total `len` → return; got
nothing → total, or −1 if none; else `av`, return if ≤ 0, else next step. [BufferedCalls: bis read…, bis short
chunks…, bis eof]

**B3.** `available()` = buffered + `in.available()`, saturating at `Integer.MAX_VALUE`. `skip(n)`: `n ≤ 0` → 0;
empty and unmarked → `in.skip(n)`; empty and marked → one refill first; then `min(n, buffered)`, no further calls.
[BufferedCalls: bis available…, bis skip…]

**B4.** Marks. A refill of an exhausted buffer with a mark: mark not at index 0 → bytes from the mark move to the
front, read the free space; mark at 0 and length < `readlimit` → grow to `min(2 × length, readlimit)`, read the new
part; else drop the mark and refill all. So reading past `readlimit` invalidates only once a refill happens.
`reset()` without valid mark → `IOException("Resetting to invalid mark")`. [BufferedCalls: bis mark…, bis reset
no mark, bis read 9000 after mark]

**B5.** `close()` closes `in` once; then `read`, `available`, `skip` → `IOException("Stream closed")`.
[BufferedCalls: bis close, bis closed…]

**B6.** `BufferedOutputStream`: `write(int)` on a full buffer writes it out first; `write(b, off, len)` with `len ≥`
size → out the buffer (if any), then one direct write; `len` > free → out the buffer, then copy. `flush()` = out +
`fl`; `close()` = `flush()` + `close`, on every call. It checks no bounds itself: a bad range with `len ≥` size goes
to the wrapped stream as is, a shorter one fails in the copy (`ArrayIndexOutOfBoundsException`). [BufferedCalls: bos…,
bos bad range…]

**B7.** `BufferedReader` refills ask for the free space. `read(c, off, len)`: first step direct when the buffer is
empty, `len ≥` size, no mark and no `\n` to skip; else refill and copy; more steps while short and `in.ready()`.
`ready()` on an empty buffer asks `in.ready()`, else true. `skip(n < 0)` → `IllegalArgumentException("skip value
is negative")`. `close()` once; then `read`, `ready`, `readLine` → `IOException("Stream closed")`. [BufferedCalls:
br…]

**B8.** `BufferedWriter`: when the buffer fills it goes out as one `w<size>` before more is taken (strings are split
over loads); `write(char[], off, len ≥ size)` → out the buffer, then direct. `flush()` = out + `fl`; `close()` = out
+ `close` (no `fl`), once; then `write`/`flush` → `IOException("Stream closed")`. `newLine()` writes
`line.separator`. [BufferedCalls: bw…]

## 3. readLine and BufferedReader marks

**L1.** `readLine()` ends lines at `\n`, `\r`, `\r\n` (one terminator even across refills, any buffer size), drops
the terminator, returns `null` at the end. A last unterminated line is returned; `""` → `null`; `"\n"` → `""`,
`null`; `"\n\r"` → two `""`. U+2028, U+0085, `\f` are ordinary. [ReadLines: lines…]

**L2.** After a line ended by `\r`, a next `\n` is swallowed by the first of `read()`, `read(char[]…)`, `skip`,
`readLine`. `read()` alone never folds (`"\r\n"` → 13, 10, −1). After the line of `"a\r\n"`, `ready()` → true,
then `read()` → −1. [ReadLines: mixed…, … after \r line, read() of \r\n]

**L3.** `mark(n < 0)` → `IllegalArgumentException("Read-ahead limit < 0")`; unmarked `reset()` →
`IOException("Stream not marked")`; `reset()` → `IOException("Mark invalid")` exactly when a refill happened after
≥ `n` chars were read since the mark (so `mark(0)` before the first refill is invalid after one `read()`); a limit
above the size grows the buffer; a mark survives repeated resets. [ReadLines: br mark…, br reset…]

## 4. InputStreamReader, OutputStreamWriter, PrintStream, PrintWriter

**R1.** `InputStreamReader` reads into an 8192-byte buffer, each read asking for the free space after leftovers move
to the front (`r8192` throughout the tests). `read(c, off, len)` decodes buffered bytes; when they run out with ≥ 1
char produced it calls `av` and returns if ≤ 0; with none it reads again. At the end each call reads again and returns
−1. `len` 0 → 0, no calls. A full buffer returns at once, without `av`. A read of one char decodes two and keeps the
second; the next call returns that char first, and decodes more only if bytes are buffered or `in.available() > 0`
(one `av`), the kept char not counting as produced. A coding error is thrown as soon as it is met, even after chars
were produced in the same call (they are lost). At the end the decoder is reset before what is left is decoded, so
`UTF-16` reads a dangling last unit big-endian even after `FF FE` (`FF FE 41 00 3D D8` → `A`, U+3DD8). [ReaderCalls:
isr read…, isr eof, isr spare…, isr full buffer…, isr error after chars; CharsetsEncode: reader UTF-16]

**R2.** `ready()`: bytes or chars left → true, else `in.available() > 0`. `mark` → `IOException("mark() not
supported")`. `close()` once; then `read`, `ready` → `IOException("Stream closed")`. Sequences split between
reads decode as if whole. Built from a `CharsetDecoder`, its actions apply: with REPORT the first `read()` of `41 C3
42` throws `MalformedInputException("Input length = 1")`. [ReaderCalls: isr…; Charsets: trickle, edge;
CharsetsEncode: reader with REPORT decoder]

**R3.** `OutputStreamWriter` encodes into an 8192-byte buffer, written as soon as the next char does not fit
(3000 × U+20AC → `w8190`). `flush()` = pending bytes (if any) + `fl`; `close()` = pending + `close` (no `fl`),
once; then `write`/`flush` → `IOException("Stream closed")`, `getEncoding()` → null. A trailing high surrogate
waits for the next write; at `close` it becomes the replacement. UTF-16 writes its BOM with the first char given to
the encoder, not on an earlier flush. Built from a REPORT `CharsetEncoder`, a lone surrogate throws
`MalformedInputException("Input length = 1")`. [ReaderCalls: osw…; CharsetsEncode: writer…]

**R4.** `getEncoding()` gives historical names: `UTF8`, `ISO8859_1`, `ASCII`, `UTF-16`, `UnicodeBigUnmarked`
(UTF-16BE), `UnicodeLittleUnmarked` (UTF-16LE). [CharsetsEncode: getEncoding]

**R6.** Found by review. A stream whose `read(b, off, len > 0)` returns 0 → `IOException("Underlying input stream
returned zero bytes")` from `InputStreamReader` (`BufferedReader` retries). After `close`, `read()` still returns a
kept second char of a pair, `read(char[]…)` throws. `OutputStreamWriter.write(char[]…)` checks the closed state
first; `write(String, off, len)`: `len < 0` → IOOBE, then `String.getChars` judges the range, then the closed
state. `Writer.write(String)` uses its reused 1024-char array for up to 1024 chars. `CharsetEncoder.canEncode(
CharSequence)` during a coding, or after a convenience `encode` that failed, → `IllegalStateException("Current state =
CODING_END, new state = CODING")`; it leaves the coder reset. `canEncode(char)` neither checks nor changes the state
(all six charsets: after a flush the coder stays flushed). [IoEdges: isr…, osw…, write…; NioEdges: canEncode…]

**R5.** `PrintWriter(OutputStream)` buffers (a `BufferedWriter` over an `OutputStreamWriter`); `checkError()`
flushes; auto-flush acts on `println` only. `PrintStream(OutputStream)` writes each `print` at once; `println(s)`
is two writes (`s`, separator); with auto-flush a `print` containing `\n` flushes (two `fl`), `write(int)` only
for `\n`, `write(b, off, len)` always. Both close once; printing after close sets the error flag. [ReaderCalls:
PrintWriter…, PrintStream…]

## 5. Charsets through String and the readers

UTF-8 decoding replaces each *malformed unit* by one U+FFFD, the same in `new String` and in `InputStreamReader`
(whole, byte by byte, and across its 8192-byte reads). Units, by lead byte [Charsets; CharsetCoders: utf8…]:

**U1.** `00–7F` a char; `80–BF`, `C0`, `C1`, `F5–FF` a unit of 1 (`C0 80` → `??`).

**U2.** `C2–DF` + continuation (`80–BF`) → a char; else a unit of 1 and the next byte starts afresh (`41 C3 C3 A9
42` → `A?éB`).

**U3.** `E0–EF`: second byte not a continuation, or `E0` + `80–9F` (overlong) → unit of 1, later bytes judged
alone (`E0 80 80` → `???`); third not a continuation → unit of 2 (`E2 82 41` → `?A`); `ED A0–BF xx` (a surrogate)
→ unit of 3 (a CESU pair is two units). `EF BF BE`, `EF BF BF` → U+FFFE, U+FFFF; `EF BB BF` → U+FEFF (kept).

**U4.** `F0–F4`: second byte not a continuation, `F0 80–8F` or `F4 90–BF` → unit of 1; third bad → 2; fourth bad →
3 (`F0 9F 98 41` → `?A`); valid → a surrogate pair.

**U5.** A valid prefix cut by the end of input is one unit covering it (`E0 A0` → `?`, `F0 9F 98` → `?`).

**C1.** US-ASCII decodes `80–FF` to U+FFFD; ISO-8859-1 maps every byte. [CharsetsEncode: new String;
CharsetCoders: ascii, latin1]

**C2.** UTF-16 (2-byte units): `UTF-16` takes `FE FF` (big-endian) or `FF FE` (little-endian) only at the very
start and consumes it, default big-endian; a later U+FEFF unit is a char. In all three flavours a unit valued U+FFFE
is malformed (2), so the reversed BOM is `?` and the own-order BOM is U+FEFF. Odd last byte → unit of 1; high
surrogate + non-low unit → one unit of 4 (`D8 3D 00 41` → `?`); lone low → 2; high at the end → 2. A reader honours
the BOM only at the start. [CharsetsEncode: UTF-16… new String, reader UTF-16; CharsetCoders: UTF-16…]

**E1.** `getBytes` (REPLACE): US-ASCII above `7F`, ISO-8859-1 above `FF` → `3F`, a pair as one unit; lone
surrogates → `3F` in those and UTF-8, U+FFFD in the UTF-16 flavours. `"\uD83D\uD83D\uDE00"` → replacement, then the
pair. `UTF-16` output starts with `FE FF`, except `""` → no bytes. `getBytes(Charset)` = `getBytes(name)`.
[CharsetsEncode: getBytes…]

**E2.** Unknown or illegal names in `getBytes(String)`, `new String(…, String)` and the reader/writer
constructors → `UnsupportedEncodingException(name)`; null name → NPE. `new String(b, off, len, cs)` out of range →
`StringIndexOutOfBoundsException("String index out of range: " + (off + len))`. [CharsetsEncode: … bad name,
getBytes null; Charsets: window…]

## 6. java.nio.charset

**N1.** `Charset.forName` ignores case; `aliases()` is exactly: US-ASCII {646, ANSI_X3.4-1968, ANSI_X3.4-1986,
ASCII, IBM367, ISO646-US, ISO_646.irv:1991, ascii7, cp367, csASCII, default, iso-ir-6, iso_646.irv:1983, us};
ISO-8859-1 {819, 8859_1, IBM-819, IBM819, ISO8859-1, ISO8859_1, ISO_8859-1, ISO_8859-1:1987, ISO_8859_1, cp819,
csISOLatin1, iso-ir-100, l1, latin1}; UTF-8 {UTF8, unicode-1-1-utf-8}; UTF-16 {UTF_16, UnicodeBig, unicode,
utf16}; UTF-16BE {ISO-10646-UCS-2, UTF_16BE, UnicodeBigUnmarked, X-UTF-16BE}; UTF-16LE {UTF_16LE,
UnicodeLittleUnmarked, X-UTF-16LE}. `name()`, `displayName()`, `toString()` are the canonical name; `isRegistered()`
and `canEncode()` true; `StandardCharsets` constants are the objects `forName` returns. [CharsetNames]

**N2.** Null name → `IllegalArgumentException("Null charset name")`. A legal name is non-empty, starts with an
ASCII letter or digit, and continues with letters, digits and `- + : _ .`; else `IllegalCharsetNameException`
whose message is the name (also from `isSupported`). Legal but unknown → `UnsupportedCharsetException(name)`,
`isSupported` false. [CharsetNames: forName […], isSupported […]]

**N3.** `hashCode()` = `name().hashCode()`; `compareTo` compares names ignoring case; `contains`: UTF-8 contains
US-ASCII and UTF-16, UTF-16LE contains UTF-8, ISO-8859-1 contains US-ASCII, US-ASCII only itself. `defaultCharset()` follows `file.encoding`;
`availableCharsets()` keys ignore case. LazyVM's default charset is UTF-8 on every platform, where the oracle uses
Cp1252 on Windows (decisions.md D12): `FileReader`, `FileWriter`, `PrintStream(File)` and the no-charset
constructors and `getBytes()` differ there, and the tests name their charsets. [CharsetNames]

**K1.** `CharsetDecoder.decode(in, out, end)` reports each unit of §5 as `MALFORMED[n]` with `in` at its first byte.
With `end` false a valid prefix at the end stays unconsumed (UNDERFLOW); a unit already known bad is reported. A char,
or a pair, that does not fit → OVERFLOW with `in` before it. UTF-8 judges a whole sequence in this order: the lead,
the bytes before the last (the second's range included), then the room, then the last byte (and, for 3 bytes, the
surrogate check): so with no room `C2 41`, `E2 82 41` and `ED A0 80` are OVERFLOW while `80`, `E2 41` and `E0 80 80`
are MALFORMED[1]. A lone invalid lead is MALFORMED[1] even with no room. [CharsetCoders: utf8…, decode overflow,
decode pair into 1 char, utf8 no room…]

**K2.** Encoders: unmappable char → `UNMAPPABLE[1]`, unmappable pair → `UNMAPPABLE[2]`, lone surrogate →
`MALFORMED[1]`; a final high surrogate with `end` false → UNDERFLOW, unconsumed. [CharsetCoders: … encode…,
encode lone high, not at end]

**K3.** Defaults: actions REPORT; decoder replacement `"\uFFFD"`; encoder replacement `{63}`, UTF-16 and UTF-16BE
`{-1, -3}`, UTF-16LE `{-3, -1}`. Chars per byte (average/max): UTF-16, UTF-16BE, UTF-16LE 0.5/1.0, the others 1.0/1.0.
Bytes per char: US-ASCII, ISO-8859-1 1.0/1.0; UTF-8 1.1/3.0; UTF-16 2.0/4.0; BE, LE 2.0/2.0. `canEncode`: never a lone
surrogate, a pair only in the UTF charsets. [CharsetCoders: default actions, … averages, … encoder]

**K4.** `replaceWith(null)` → IAE `"Null replacement"`, empty → `"Empty replacement"`, longer than the max per unit
→ `"Replacement too long"`, bytes the charset cannot decode → `"Illegal replacement"`; null action → IAE `"Null
action"`. Coding with `end` true then false → `IllegalStateException("Current state = CODING_END, new state =
CODING")`; `flush` on a fresh coder → `"Current state = RESET, new state = FLUSHED"`; `reset()` allows reuse.
One-shot `decode(ByteBuffer)`/`encode(CharBuffer)` use the coder's actions (REPORT → `MalformedInputException` or
`UnmappableCharacterException`, `"Input length = n"`); `Charset.decode/encode` replace. IGNORE drops a unit, REPLACE
writes the replacement per unit. [CharsetCoders]

**K5.** `CoderResult.toString()`: `UNDERFLOW`, `OVERFLOW`, `MALFORMED[n]`, `UNMAPPABLE[n]`; equal lengths give the
same object; `length()` of under/overflow → `UnsupportedOperationException`; `n ≤ 0` → IAE `"Non-positive
length"`; `throwException()` throws `BufferUnderflowException`, `BufferOverflowException` or the coding exception
of length `n`. `new CharacterCodingException().getMessage()` is null. [CharsetCoders: CoderResult…,
throwException…]

## 7. java.io.File

**F1.** Paths (shown with `/`): repeated and trailing separators go (`a//b/` → `a/b`), `.` and `..` stay;
`getName` follows the last separator, `getParent` precedes it or is null; `""` has name `""`, parent null and
absolute path `user.dir`; relative paths resolve against `user.dir`. `File(parent, child)`: null parent → child;
`""` → separator + child; `File(a, "")` → `a`. Null → NPE. [Files1: […], File(…)]

**F2.** `equals`, `compareTo` compare path strings; `hashCode` = `path.hashCode() ^ 1234321`. On Windows both
ignore case and the hash uses the lower-cased path. [Files1: equals, hashCode of x, compareTo; FilePathsWin]

**F3.** Missing file: `exists`, `isFile`, `isDirectory`, `canRead`, `canWrite`, `isHidden`, `delete`, `renameTo`,
`setLastModified` → false; `length`, `lastModified` → 0; `list`, `listFiles` → null. [Files1: missing…]

**F4.** `mkdir`: true once, then false; false with a missing parent or on a file. `mkdirs` makes parents, false if
present, accepts a trailing separator. `createNewFile`: true then false; false on a directory; `IOException` in a
missing directory. Non-empty directory `delete` → false. `renameTo` works across directories and for directories,
false into a missing directory. Unpinned: onto an existing file (Windows false, Unix replaces). [Files1]

**F5.** `list()` gives bare names without `.`/`..`; LazyVM sorts them (files.md), which equals NTFS order for the
test names; on a file → null. `listFiles` entries are `new File(this, name)`. `setLastModified(t < 0)` →
`IllegalArgumentException("Negative time")`. [Files1: list…, setLastModified…]

**F6.** `createTempFile(prefix, suffix[, dir])`: prefix + digits + suffix (`.tmp` if null), in canonical
`java.io.tmpdir` or `dir`; prefix < 3 chars → `IllegalArgumentException("Prefix string too short")`; missing `dir`
→ `IOException`. `toURI()` of an existing directory ends with `/`, spaces become `%20`. [Files1: createTempFile…,
toURI…]

**F7.** Found by review. `createTempFile` keeps only the prefix's last name; a suffix that would make more than one
name → `IOException("Unable to create temporary file, <path>")`, so the file stays in the directory.
`renameTo` onto the same file (itself, or another case on Windows) → true; onto another existing file on Windows →
false. `getParentFile` keeps the parent string as it is (`\C:\` → `\C:`). `setLastModified` takes milliseconds to
microseconds; a time that does not fit → false; on Windows the first instant of 1601 leaves the time as it is and an
earlier one is "The parameter is incorrect.". On Windows a directory's write access cannot change (`setReadOnly`,
`setWritable` either way → false), and read and execute access can be granted (true, even for a missing file) but not
taken. `toURI` escapes as nio-file.md P4 (`u[v]` → `u%5Bv%5D`). `toURI` of `""` ends with `/`. A lone surrogate
never names the file of U+FFFD (on Windows it names its own). [IoEdges: temp…, renameTo…, … setLastModified, toURI…,
lone surrogate names; FilePathsWin: NUL…, dir…, unc…] Canonical and real paths: nio-file.md PW2.

**W1.** Windows, pinned by FilePathsWin (table there): `/` and `\` are separators, normalised to `\`; two or more
leading separators make an absolute UNC path (`///a` → `\\a`); `\a` and `C:a` are relative, `C:\` absolute; `C:` has
name `""`; `C:a` has parent `C:`; `1:` is a name, `a:b` is drive `a:`; `File("C:", "b")` → `C:\b`, `File("", "C:b")` →
`\C:b`; trailing dots and spaces stay in the path but not in `getCanonicalPath`, which also takes the on-disk case and
an upper-case drive; canonical of `\\` → `IOException`. Found by the implementation's fuzz and pinned by the later
rows: separators before a drive go (`\C:b` → `C:b`, `//c:` → `c:`); any char before `:\` keeps that separator as a
root (`" :\"`, `\:\`) though only a letter is a drive; `File(p, c)` drops one trailing `\` of `p`, a leading `\` or
`\\` of `c`, and joins with one `\` unless what is left of `c` starts with one, a `c` of only `\\` giving the
shortened `p` (`File("\", "\\")` → `""`, `File("a", "\")` → `a\`); the absolute path of `c:x` on the current drive
keeps the drive's case, on another drive that exists is `D:\\x`, on a missing one `B:\x` (`a:` stays `a:`).

## 8. File streams and RandomAccessFile

**FS1.** Opening a missing file, one in a missing directory, or a directory (`FileInputStream`, `FileOutputStream`,
`FileReader`, `RandomAccessFile`) → `FileNotFoundException("<path> (<os reason>)")`, path as passed. Null → NPE.
[FileStreams: fis…, fos…, raf…]

**FS2.** `FileOutputStream` truncates, or appends (creating if missing). After `close` (idempotent) `read`,
`available`, `skip`, `write` → `IOException("Stream Closed")`, capital C. Bounds → IOOBE. [FileStreams]

**FS3.** `FileInputStream`: `available()` = bytes left; `skip(n)` moves by `n` even past the end (then
`available` 0, `read` −1); negative `skip` moves back, below 0 → `IOException`; `read(b, 0, 0)` at the end → 0;
`reset` → `IOException("mark/reset not supported")`. `FileReader`/`FileWriter` use the default charset.
[FileStreams]

**FS4.** `RandomAccessFile`: a mode other than `r`, `rw`, `rws`, `rwd` → `IllegalArgumentException("Illegal mode
\"m\" must be one of \"r\", \"rw\", \"rws\", or \"rwd\"")`; `rw*` create and keep content; `write`/`setLength`
in `r` → `IOException`. `seek` past the end keeps the length, a write there zero-fills; `seek(-1)` →
`IOException("Negative seek offset")`; shrinking `setLength` pulls the pointer back; `setLength(-1)` →
`IOException`. Primitives as D1; `readInt` short → `EOFException` (null message) with the pointer after the bytes
taken; `skipBytes` clamps, ≤ 0 → 0; `readLine` reads bytes as Latin-1 with L1's terminators. [FileStreams: raf…]

## 9. Data streams

**D1.** `DataOutputStream` is big-endian; `writeShort`/`writeChar` keep 16 bits, `writeByte` 8; `writeFloat`,
`writeDouble` write the canonical NaN (`7FC00000`); `writeBytes` keeps low bytes, `writeChars` two bytes each;
`size()` counts bytes. [DataStreams]

**D2.** `writeUTF`: 2-byte length; U+0000 → `C0 80`, `0001–007F` one byte, ≤ `07FF` two, else three, surrogates one
by one (a pair = 6 bytes). Over 65535 bytes → `UTFDataFormatException("encoded string too long: <n> bytes")`, with
nothing written. Null → NPE. [DataStreams: writeUTF…]

**D3.** `readUTF` accepts `C0 80`, any 2-byte form (`C1 81` → `A`), 3-byte surrogates. For a unit at data index
`i`: lead `80–BF` or `F0–FF` → `"malformed input around byte i"`; bad continuation of a 2- or 3-byte unit →
`"… around byte i + 2"`; unit past the declared length → `"malformed input: partial character at end"`, checked
first. Missing data → `EOFException`. [DataStreams: readUTF…]

**D4.** Short `readInt`, `readLong`, `readChar`, `readBoolean`, `readByte`, `readFully` → `EOFException` (null
message); `readFully(b, off, 0)` at the end is fine; `skipBytes` clamps, negative → 0; reads gather across short
underlying reads; deprecated `readLine` as FS4. After a `\r` `readLine` pushes the next byte back in front of
the stream, reusing one pushback for every line; a `read(b, off, 0)` then returns 0 without a call. Reads of 2 and
4 bytes call `read()` per byte. [DataStreams; IoEdges: dis…]

## 10. System.in

**I1.** Unpinned (a test gets no stdin). Observed with a pipe: `System.in` is a `java.io.BufferedInputStream`,
`markSupported()` true; bytes arrive unchanged (`\r\n` too); at the end `read()` → −1 every time, `available()` 0;
after `close()` → `IOException("Stream closed")`. Not to copy: `skip` at the end of a pipe returned `n`;
`available()` on NUL threw. A `// STDIN:` header in `testlib.py` would let a test pin I1.

## 11. Heap buffers (java.nio)

**NB1.** `position(p)`/`limit(l)` outside `[0, capacity]` (and `p ≤ limit`) → IAE, no message; a limit below the
position moves the position; position or limit below the mark drops the mark, a position equal to it keeps it.
`reset()` unmarked → `InvalidMarkException`. `flip`, `rewind`, `clear`, `compact` drop the mark; `compact` moves the
remaining elements to 0, position = their count, limit = capacity. `allocate(-1)` → IAE. [Buffers: position…,
limit…, flip … compact…]

**NB2.** Relative `get` at the limit → `BufferUnderflowException`, `put` → `BufferOverflowException`; an absolute
index outside `[0, limit − size]` → IOOBE; bulk and multi-byte relative calls that lack room throw before moving
anything (`put(src)` leaves both); `put(self)` → IAE. Read-only: every `put`, `compact`, `array()`, `arrayOffset()`
→ `ReadOnlyBufferException`, even when full. [Buffers: get…, put…, ro…]

**NB3.** Views share content. `wrap(a, off, len)`: position `off`, limit `off + len`, capacity `a.length`,
`arrayOffset` 0. `slice()`: position 0, limit = capacity = remaining, `arrayOffset` + position. `duplicate()`: same
position, limit, mark. `asReadOnlyBuffer()`: same, `hasArray()` false. Views of read-only buffers are read-only.
`ByteBuffer` slices and duplicates are BIG_ENDIAN regardless of the source. [Buffers: wrap…, slice…, duplicate…,
asReadOnly]

**NB4.** Default order BIG_ENDIAN; multi-byte access follows `order()`; `putFloat`/`putDouble` keep NaN bits
(unlike D1). `asIntBuffer()` and the other views start at the position, capacity = remaining / size, take the order
at creation, write through. `CharBuffer.allocate`/`wrap` report the native order (LITTLE_ENDIAN on x86-64).
[Buffers: putInt…, multi-byte, as…; CharBuffers: allocate]

**NB5.** `equals`: same element type, equal remaining elements (order, capacity, offsets ignored; float and double
treat NaN = NaN, −0.0 = 0.0). `hashCode`: `h = 1`; for `i` from `limit − 1` down to `position`, `h = 31 * h + (int)
get(i)`. `compareTo`: first differing element, giving the difference for bytes and chars (−1 vs 1 → −2), for floats
NaN last and −0.0 = 0.0; on a common prefix `remaining() − other.remaining()`. `toString()`: CharBuffer → the
remaining chars; others → class name + `[pos=P lim=L cap=C]` (name free; tests strip it). [Buffers: equals…,
compareTo, hashCode…, toString; CharBuffers]

**NB6.** `CharBuffer` as `CharSequence`: `length()` = remaining; `charAt(i)` from the position, IOOBE outside;
`subSequence(s, e)` shares content, position + s to position + e, same capacity and read-only state.
`wrap(CharSequence[, s, e])`: read-only live view (later `StringBuilder` edits show), `hasArray()` false, `array()` →
`UnsupportedOperationException`, `put`/`compact` → `ReadOnlyBufferException`. `put(String, s, e)` indexes the string,
checking the range before the read-only state; overflow leaves the position. `read(t)`: copies the smaller remaining,
−1 when empty, 0 when `t` is full, IAE for itself (−1 first when it is empty). [CharBuffers: … put(String)…]

**NB7.** Found by review. Bulk `put` between overlapping windows of one array, or between views of one order over
direct bytes, copies as if through a temporary; other views copy element by element in order. Arrays, direct views
and byte buffers check the read-only state first (`put(self)`, `read(self)` → ROBE); views of heap bytes and text
buffers check for themselves first (IAE) and find the read-only state only at an element, so `put` of no elements
does nothing. `read(t)` into a read-only `t` throws before the source moves. A text buffer's `toString`, bulk `get`
and `subSequence` copy in bulk. `compact` on a view of a heap buffer → IAE when the view's start in the array plus its limit in bytes
passes the capacity of the buffer it was made from, which needs an array offset (zero-capacity views included);
direct views never fail. [NioEdges: overlapping…, view overlap table, … of itself, … read-only…, view compact…]

## 12. Paths and 13. Files

Moved to [`nio-file.md`](nio-file.md) (rules P1–P4, PW1, U1–U3, NF1–NF10), with the `java.nio.file` rows of §18.

## 14. LazyOS

There the separator is `/`, `pathSeparator` `:`, `line.separator` `\n`; paths are Unix paths (no drives, `\` an
ordinary char, case-sensitive comparison and hash, one root `/`); OS reasons are Linux texts; `isHidden` means a
leading dot; tests may run as root, so permission checks may pass. Apart from `line.separator`, sections 1–6, 9 and 11 do not depend
on the platform; W1 and PW1 run only on Windows.

## 15. Deferred

S11 (streams): `Files.list`, `walk`, `find`, `lines`, `BufferedReader.lines`, `CharBuffer.chars`. Out of scope:
channels (`getChannel`, `newByteChannel`), `FileDescriptor` and `getFD`, watch services (`Path.register`),
providers, `FileStore`, links, POSIX and other attribute views. `deleteOnExit` and `finalize` are not exercised.

## 16. Not observable: the implementation may choose

Temp-name digits; implementation class names (only `toString` prefixes show them, and tests strip them); how
`Files.copy` chunks; buffer sizes no user stream can see (`FileInputStream`, `Files.newInputStream`,
`readAllBytes`); `BufferedReader` growth beyond L3; `availableCharsets()` beyond the six; `DirectoryStream`
behaviour after close beyond NF7.

## 17. Known differences

Listed in [`nio-file.md`](nio-file.md) section 3, for both halves.

## 18. API to provide

Reflection on the oracle (public and protected members each class declares), limited to the plan's scope; section
15's members are left out. Packages are dropped from type names; `new(…)` is a constructor; enum rows list their
constants; interface members are abstract unless marked `default`.

| Package | Class | Kind | Members |
|---|---|---|---|
| java.io | `Closeable` | interface extends AutoCloseable | close(): void |
| java.io | `Flushable` | interface | flush(): void |
| java.io | `DataInput` | interface | readBoolean(): boolean; readByte(): byte; readChar(): char; readDouble(): double; readFloat(): float; readFully(byte[]): void; readFully(byte[],int,int): void; readInt(): int; readLine(): String; readLong(): long; readShort(): short; readUTF(): String; readUnsignedByte(): int; readUnsignedShort(): int; skipBytes(int): int |
| java.io | `DataOutput` | interface | write(byte[]): void; write(byte[],int,int): void; write(int): void; writeBoolean(boolean): void; writeByte(int): void; writeBytes(String): void; writeChar(int): void; writeChars(String): void; writeDouble(double): void; writeFloat(float): void; writeInt(int): void; writeLong(long): void; writeShort(int): void; writeUTF(String): void |
| java.io | `FileFilter` | interface | accept(File): boolean |
| java.io | `FilenameFilter` | interface | accept(File,String): boolean |
| java.io | `InputStream` | abstract class implements Closeable | new(); available(): int; close(): void; mark(int): void; markSupported(): boolean; abstract read(): int; read(byte[]): int; read(byte[],int,int): int; reset(): void; skip(long): long |
| java.io | `OutputStream` | abstract class implements Closeable, Flushable | new(); close(): void; flush(): void; write(byte[]): void; write(byte[],int,int): void; abstract write(int): void |
| java.io | `Reader` | abstract class implements Readable, Closeable | protected Object lock; protected new(); protected new(Object); abstract close(): void; mark(int): void; markSupported(): boolean; read(): int; read(CharBuffer): int; read(char[]): int; abstract read(char[],int,int): int; ready(): boolean; reset(): void; skip(long): long |
| java.io | `Writer` | abstract class implements Appendable, Closeable, Flushable | protected Object lock; protected new(); protected new(Object); append(CharSequence): Writer; append(CharSequence,int,int): Writer; append(char): Writer; abstract close(): void; abstract flush(): void; write(String): void; write(String,int,int): void; write(char[]): void; abstract write(char[],int,int): void; write(int): void |
| java.io | `FilterInputStream` | class extends InputStream | protected InputStream in; protected new(InputStream); available(): int; close(): void; mark(int): void; markSupported(): boolean; read(): int; read(byte[]): int; read(byte[],int,int): int; reset(): void; skip(long): long |
| java.io | `FilterOutputStream` | class extends OutputStream | protected OutputStream out; new(OutputStream); close(): void; flush(): void; write(byte[]): void; write(byte[],int,int): void; write(int): void |
| java.io | `BufferedInputStream` | class extends FilterInputStream | protected byte[] buf; protected int count; protected int pos; protected int markpos; protected int marklimit; new(InputStream); new(InputStream,int); available(): int; close(): void; mark(int): void; markSupported(): boolean; read(): int; read(byte[],int,int): int; reset(): void; skip(long): long |
| java.io | `BufferedOutputStream` | class extends FilterOutputStream | protected byte[] buf; protected int count; new(OutputStream); new(OutputStream,int); flush(): void; write(byte[],int,int): void; write(int): void |
| java.io | `BufferedReader` | class extends Reader | new(Reader,int); new(Reader); close(): void; mark(int): void; markSupported(): boolean; read(): int; read(char[],int,int): int; readLine(): String; ready(): boolean; reset(): void; skip(long): long |
| java.io | `BufferedWriter` | class extends Writer | new(Writer); new(Writer,int); close(): void; flush(): void; newLine(): void; write(String,int,int): void; write(char[],int,int): void; write(int): void |
| java.io | `InputStreamReader` | class extends Reader | new(InputStream,CharsetDecoder); new(InputStream,Charset); new(InputStream,String); new(InputStream); close(): void; getEncoding(): String; read(): int; read(char[],int,int): int; ready(): boolean |
| java.io | `OutputStreamWriter` | class extends Writer | new(OutputStream,CharsetEncoder); new(OutputStream,Charset); new(OutputStream); new(OutputStream,String); close(): void; flush(): void; getEncoding(): String; write(String,int,int): void; write(char[],int,int): void; write(int): void |
| java.io | `FileReader` | class extends InputStreamReader | new(String); new(File); new(FileDescriptor) |
| java.io | `FileWriter` | class extends OutputStreamWriter | new(FileDescriptor); new(File,boolean); new(File); new(String,boolean); new(String) |
| java.io | `ByteArrayInputStream` | class extends InputStream | protected byte[] buf; protected int pos; protected int mark; protected int count; new(byte[]); new(byte[],int,int); available(): int; close(): void; mark(int): void; markSupported(): boolean; read(): int; read(byte[],int,int): int; reset(): void; skip(long): long |
| java.io | `ByteArrayOutputStream` | class extends OutputStream | protected byte[] buf; protected int count; new(); new(int); close(): void; reset(): void; size(): int; toByteArray(): byte[]; toString(): String; toString(String): String; toString(int): String; write(byte[],int,int): void; write(int): void; writeTo(OutputStream): void |
| java.io | `CharArrayReader` | class extends Reader | protected char[] buf; protected int pos; protected int markedPos; protected int count; new(char[]); new(char[],int,int); close(): void; mark(int): void; markSupported(): boolean; read(): int; read(char[],int,int): int; ready(): boolean; reset(): void; skip(long): long |
| java.io | `CharArrayWriter` | class extends Writer | protected char[] buf; protected int count; new(); new(int); append(CharSequence): CharArrayWriter; append(CharSequence,int,int): CharArrayWriter; append(char): CharArrayWriter; close(): void; flush(): void; reset(): void; size(): int; toCharArray(): char[]; toString(): String; write(String,int,int): void; write(char[],int,int): void; write(int): void; writeTo(Writer): void |
| java.io | `StringReader` | class extends Reader | new(String); close(): void; mark(int): void; markSupported(): boolean; read(): int; read(char[],int,int): int; ready(): boolean; reset(): void; skip(long): long |
| java.io | `StringWriter` | class extends Writer | new(); new(int); append(CharSequence): StringWriter; append(CharSequence,int,int): StringWriter; append(char): StringWriter; close(): void; flush(): void; getBuffer(): StringBuffer; toString(): String; write(String): void; write(String,int,int): void; write(char[],int,int): void; write(int): void |
| java.io | `PrintStream` | class extends FilterOutputStream implements Appendable, Closeable | new(OutputStream); new(OutputStream,boolean); new(OutputStream,boolean,String); new(String); new(String,String); new(File,String); new(File); append(CharSequence): PrintStream; append(CharSequence,int,int): PrintStream; append(char): PrintStream; checkError(): boolean; protected clearError(): void; close(): void; flush(): void; format(Locale,String,Object...): PrintStream; format(String,Object...): PrintStream; print(Object): void; print(String): void; print(boolean): void; print(char): void; print(char[]): void; print(double): void; print(float): void; print(int): void; print(long): void; printf(Locale,String,Object...): PrintStream; printf(String,Object...): PrintStream; println(): void; println(Object): void; println(String): void; println(boolean): void; println(char): void; println(char[]): void; println(double): void; println(float): void; println(int): void; println(long): void; protected setError(): void; write(byte[],int,int): void; write(int): void |
| java.io | `PrintWriter` | class extends Writer | protected Writer out; new(OutputStream,boolean); new(String); new(File); new(File,String); new(String,String); new(Writer); new(OutputStream); new(Writer,boolean); append(CharSequence): PrintWriter; append(CharSequence,int,int): PrintWriter; append(char): PrintWriter; checkError(): boolean; protected clearError(): void; close(): void; flush(): void; format(Locale,String,Object...): PrintWriter; format(String,Object...): PrintWriter; print(Object): void; print(String): void; print(boolean): void; print(char): void; print(char[]): void; print(double): void; print(float): void; print(int): void; print(long): void; printf(Locale,String,Object...): PrintWriter; printf(String,Object...): PrintWriter; println(): void; println(Object): void; println(String): void; println(boolean): void; println(char): void; println(char[]): void; println(double): void; println(float): void; println(int): void; println(long): void; protected setError(): void; write(String): void; write(String,int,int): void; write(char[]): void; write(char[],int,int): void; write(int): void |
| java.io | `DataInputStream` | class extends FilterInputStream implements DataInput | new(InputStream); final read(byte[]): int; final read(byte[],int,int): int; final readBoolean(): boolean; final readByte(): byte; final readChar(): char; final readDouble(): double; final readFloat(): float; final readFully(byte[]): void; final readFully(byte[],int,int): void; final readInt(): int; final readLine(): String; final readLong(): long; final readShort(): short; final readUTF(): String; static final readUTF(DataInput): String; final readUnsignedByte(): int; final readUnsignedShort(): int; final skipBytes(int): int |
| java.io | `DataOutputStream` | class extends FilterOutputStream implements DataOutput | protected int written; new(OutputStream); flush(): void; final size(): int; write(byte[],int,int): void; write(int): void; final writeBoolean(boolean): void; final writeByte(int): void; final writeBytes(String): void; final writeChar(int): void; final writeChars(String): void; final writeDouble(double): void; final writeFloat(float): void; final writeInt(int): void; final writeLong(long): void; final writeShort(int): void; final writeUTF(String): void |
| java.io | `File` | class implements Serializable, Comparable<File> | static final char separatorChar; static final String separator; static final char pathSeparatorChar; static final String pathSeparator; new(String,String); new(String); new(File,String); new(java.net.URI); canExecute(): boolean; canRead(): boolean; canWrite(): boolean; compareTo(File): int; createNewFile(): boolean; static createTempFile(String,String): File; static createTempFile(String,String,File): File; delete(): boolean; deleteOnExit(): void; equals(Object): boolean; exists(): boolean; getAbsoluteFile(): File; getAbsolutePath(): String; getCanonicalFile(): File; getCanonicalPath(): String; getFreeSpace(): long; getName(): String; getParent(): String; getParentFile(): File; getPath(): String; getTotalSpace(): long; getUsableSpace(): long; hashCode(): int; isAbsolute(): boolean; isDirectory(): boolean; isFile(): boolean; isHidden(): boolean; lastModified(): long; length(): long; list(): String[]; list(FilenameFilter): String[]; listFiles(): File[]; listFiles(FileFilter): File[]; listFiles(FilenameFilter): File[]; static listRoots(): File[]; mkdir(): boolean; mkdirs(): boolean; renameTo(File): boolean; setExecutable(boolean): boolean; setExecutable(boolean,boolean): boolean; setLastModified(long): boolean; setReadOnly(): boolean; setReadable(boolean): boolean; setReadable(boolean,boolean): boolean; setWritable(boolean): boolean; setWritable(boolean,boolean): boolean; toPath(): Path; toString(): String; toURI(): java.net.URI; toURL(): java.net.URL |
| java.io | `FileInputStream` | class extends InputStream | new(FileDescriptor); new(File); new(String); available(): int; close(): void; read(): int; read(byte[]): int; read(byte[],int,int): int; skip(long): long |
| java.io | `FileOutputStream` | class extends OutputStream | new(FileDescriptor); new(File,boolean); new(File); new(String,boolean); new(String); close(): void; write(byte[]): void; write(byte[],int,int): void; write(int): void |
| java.io | `RandomAccessFile` | class implements DataOutput, DataInput, Closeable | new(String,String); new(File,String); close(): void; getFilePointer(): long; length(): long; read(): int; read(byte[]): int; read(byte[],int,int): int; final readBoolean(): boolean; final readByte(): byte; final readChar(): char; final readDouble(): double; final readFloat(): float; final readFully(byte[]): void; final readFully(byte[],int,int): void; final readInt(): int; final readLine(): String; final readLong(): long; final readShort(): short; final readUTF(): String; final readUnsignedByte(): int; final readUnsignedShort(): int; seek(long): void; setLength(long): void; skipBytes(int): int; write(byte[]): void; write(byte[],int,int): void; write(int): void; final writeBoolean(boolean): void; final writeByte(int): void; final writeBytes(String): void; final writeChar(int): void; final writeChars(String): void; final writeDouble(double): void; final writeFloat(float): void; final writeInt(int): void; final writeLong(long): void; final writeShort(int): void; final writeUTF(String): void |
| java.io | `IOException` | class extends Exception | new(Throwable); new(String,Throwable); new(String); new() |
| java.io | `FileNotFoundException` | class extends IOException | new(); new(String) |
| java.io | `EOFException` | class extends IOException | new(); new(String) |
| java.io | `UTFDataFormatException` | class extends IOException | new(); new(String) |
| java.io | `UnsupportedEncodingException` | class extends IOException | new(); new(String) |
| java.io | `UncheckedIOException` | class extends RuntimeException | new(String,IOException); new(IOException); getCause(): IOException |
| java.nio | `Buffer` | abstract class | abstract array(): Object; abstract arrayOffset(): int; final capacity(): int; final clear(): Buffer; final flip(): Buffer; abstract hasArray(): boolean; final hasRemaining(): boolean; abstract isDirect(): boolean; abstract isReadOnly(): boolean; final limit(): int; final limit(int): Buffer; final mark(): Buffer; final position(): int; final position(int): Buffer; final remaining(): int; final reset(): Buffer; final rewind(): Buffer |
| java.nio | `ByteBuffer` | abstract class extends Buffer implements Comparable<ByteBuffer> | static allocate(int): ByteBuffer; static allocateDirect(int): ByteBuffer; final array(): byte[]; final arrayOffset(): int; abstract asCharBuffer(): CharBuffer; abstract asDoubleBuffer(): DoubleBuffer; abstract asFloatBuffer(): FloatBuffer; abstract asIntBuffer(): IntBuffer; abstract asLongBuffer(): LongBuffer; abstract asReadOnlyBuffer(): ByteBuffer; abstract asShortBuffer(): ShortBuffer; abstract compact(): ByteBuffer; compareTo(ByteBuffer): int; abstract duplicate(): ByteBuffer; equals(Object): boolean; abstract get(): byte; get(byte[]): ByteBuffer; get(byte[],int,int): ByteBuffer; abstract get(int): byte; abstract getChar(): char; abstract getChar(int): char; abstract getDouble(): double; abstract getDouble(int): double; abstract getFloat(): float; abstract getFloat(int): float; abstract getInt(): int; abstract getInt(int): int; abstract getLong(): long; abstract getLong(int): long; abstract getShort(): short; abstract getShort(int): short; final hasArray(): boolean; hashCode(): int; abstract isDirect(): boolean; final order(): ByteOrder; final order(ByteOrder): ByteBuffer; put(ByteBuffer): ByteBuffer; abstract put(byte): ByteBuffer; final put(byte[]): ByteBuffer; put(byte[],int,int): ByteBuffer; abstract put(int,byte): ByteBuffer; abstract putChar(char): ByteBuffer; abstract putChar(int,char): ByteBuffer; abstract putDouble(double): ByteBuffer; abstract putDouble(int,double): ByteBuffer; abstract putFloat(float): ByteBuffer; abstract putFloat(int,float): ByteBuffer; abstract putInt(int): ByteBuffer; abstract putInt(int,int): ByteBuffer; abstract putLong(int,long): ByteBuffer; abstract putLong(long): ByteBuffer; abstract putShort(int,short): ByteBuffer; abstract putShort(short): ByteBuffer; abstract slice(): ByteBuffer; toString(): String; static wrap(byte[]): ByteBuffer; static wrap(byte[],int,int): ByteBuffer |
| java.nio | `CharBuffer` | abstract class extends Buffer implements Comparable<CharBuffer>, Appendable, CharSequence, Readable | static allocate(int): CharBuffer; append(CharSequence): CharBuffer; append(CharSequence,int,int): CharBuffer; append(char): CharBuffer; final array(): char[]; final arrayOffset(): int; abstract asReadOnlyBuffer(): CharBuffer; final charAt(int): char; abstract compact(): CharBuffer; compareTo(CharBuffer): int; abstract duplicate(): CharBuffer; equals(Object): boolean; abstract get(): char; get(char[]): CharBuffer; get(char[],int,int): CharBuffer; abstract get(int): char; final hasArray(): boolean; hashCode(): int; abstract isDirect(): boolean; final length(): int; abstract order(): ByteOrder; put(CharBuffer): CharBuffer; final put(String): CharBuffer; put(String,int,int): CharBuffer; abstract put(char): CharBuffer; final put(char[]): CharBuffer; put(char[],int,int): CharBuffer; abstract put(int,char): CharBuffer; read(CharBuffer): int; abstract slice(): CharBuffer; abstract subSequence(int,int): CharBuffer; toString(): String; static wrap(CharSequence): CharBuffer; static wrap(CharSequence,int,int): CharBuffer; static wrap(char[]): CharBuffer; static wrap(char[],int,int): CharBuffer |
| java.nio | `ShortBuffer` | abstract class extends Buffer implements Comparable<ShortBuffer> | static allocate(int): ShortBuffer; final array(): short[]; final arrayOffset(): int; abstract asReadOnlyBuffer(): ShortBuffer; abstract compact(): ShortBuffer; compareTo(ShortBuffer): int; abstract duplicate(): ShortBuffer; equals(Object): boolean; abstract get(): short; abstract get(int): short; get(short[]): ShortBuffer; get(short[],int,int): ShortBuffer; final hasArray(): boolean; hashCode(): int; abstract isDirect(): boolean; abstract order(): ByteOrder; put(ShortBuffer): ShortBuffer; abstract put(int,short): ShortBuffer; abstract put(short): ShortBuffer; final put(short[]): ShortBuffer; put(short[],int,int): ShortBuffer; abstract slice(): ShortBuffer; toString(): String; static wrap(short[]): ShortBuffer; static wrap(short[],int,int): ShortBuffer |
| java.nio | `IntBuffer` | abstract class extends Buffer implements Comparable<IntBuffer> | static allocate(int): IntBuffer; final array(): int[]; final arrayOffset(): int; abstract asReadOnlyBuffer(): IntBuffer; abstract compact(): IntBuffer; compareTo(IntBuffer): int; abstract duplicate(): IntBuffer; equals(Object): boolean; abstract get(): int; abstract get(int): int; get(int[]): IntBuffer; get(int[],int,int): IntBuffer; final hasArray(): boolean; hashCode(): int; abstract isDirect(): boolean; abstract order(): ByteOrder; put(IntBuffer): IntBuffer; abstract put(int): IntBuffer; abstract put(int,int): IntBuffer; final put(int[]): IntBuffer; put(int[],int,int): IntBuffer; abstract slice(): IntBuffer; toString(): String; static wrap(int[]): IntBuffer; static wrap(int[],int,int): IntBuffer |
| java.nio | `LongBuffer` | abstract class extends Buffer implements Comparable<LongBuffer> | static allocate(int): LongBuffer; final array(): long[]; final arrayOffset(): int; abstract asReadOnlyBuffer(): LongBuffer; abstract compact(): LongBuffer; compareTo(LongBuffer): int; abstract duplicate(): LongBuffer; equals(Object): boolean; abstract get(): long; abstract get(int): long; get(long[]): LongBuffer; get(long[],int,int): LongBuffer; final hasArray(): boolean; hashCode(): int; abstract isDirect(): boolean; abstract order(): ByteOrder; put(LongBuffer): LongBuffer; abstract put(int,long): LongBuffer; abstract put(long): LongBuffer; final put(long[]): LongBuffer; put(long[],int,int): LongBuffer; abstract slice(): LongBuffer; toString(): String; static wrap(long[]): LongBuffer; static wrap(long[],int,int): LongBuffer |
| java.nio | `FloatBuffer` | abstract class extends Buffer implements Comparable<FloatBuffer> | static allocate(int): FloatBuffer; final array(): float[]; final arrayOffset(): int; abstract asReadOnlyBuffer(): FloatBuffer; abstract compact(): FloatBuffer; compareTo(FloatBuffer): int; abstract duplicate(): FloatBuffer; equals(Object): boolean; abstract get(): float; get(float[]): FloatBuffer; get(float[],int,int): FloatBuffer; abstract get(int): float; final hasArray(): boolean; hashCode(): int; abstract isDirect(): boolean; abstract order(): ByteOrder; put(FloatBuffer): FloatBuffer; abstract put(float): FloatBuffer; final put(float[]): FloatBuffer; put(float[],int,int): FloatBuffer; abstract put(int,float): FloatBuffer; abstract slice(): FloatBuffer; toString(): String; static wrap(float[]): FloatBuffer; static wrap(float[],int,int): FloatBuffer |
| java.nio | `DoubleBuffer` | abstract class extends Buffer implements Comparable<DoubleBuffer> | static allocate(int): DoubleBuffer; final array(): double[]; final arrayOffset(): int; abstract asReadOnlyBuffer(): DoubleBuffer; abstract compact(): DoubleBuffer; compareTo(DoubleBuffer): int; abstract duplicate(): DoubleBuffer; equals(Object): boolean; abstract get(): double; get(double[]): DoubleBuffer; get(double[],int,int): DoubleBuffer; abstract get(int): double; final hasArray(): boolean; hashCode(): int; abstract isDirect(): boolean; abstract order(): ByteOrder; put(DoubleBuffer): DoubleBuffer; abstract put(double): DoubleBuffer; final put(double[]): DoubleBuffer; put(double[],int,int): DoubleBuffer; abstract put(int,double): DoubleBuffer; abstract slice(): DoubleBuffer; toString(): String; static wrap(double[]): DoubleBuffer; static wrap(double[],int,int): DoubleBuffer |
| java.nio | `ByteOrder` | final class | static final ByteOrder BIG_ENDIAN; static final ByteOrder LITTLE_ENDIAN; static nativeOrder(): ByteOrder; toString(): String |
| java.nio | `BufferOverflowException` | class extends RuntimeException | new() |
| java.nio | `BufferUnderflowException` | class extends RuntimeException | new() |
| java.nio | `InvalidMarkException` | class extends IllegalStateException | new() |
| java.nio | `ReadOnlyBufferException` | class extends UnsupportedOperationException | new() |
| java.nio.charset | `Charset` | abstract class implements Comparable<Charset> | protected new(String,String[]); final aliases(): Set<String>; static availableCharsets(): SortedMap<String, Charset>; canEncode(): boolean; final compareTo(Charset): int; abstract contains(Charset): boolean; final decode(ByteBuffer): CharBuffer; static defaultCharset(): Charset; displayName(): String; displayName(Locale): String; final encode(CharBuffer): ByteBuffer; final encode(String): ByteBuffer; final equals(Object): boolean; static forName(String): Charset; final hashCode(): int; final isRegistered(): boolean; static isSupported(String): boolean; final name(): String; abstract newDecoder(): CharsetDecoder; abstract newEncoder(): CharsetEncoder; final toString(): String |
| java.nio.charset | `CharsetDecoder` | abstract class | protected new(Charset,float,float); final averageCharsPerByte(): float; final charset(): Charset; final decode(ByteBuffer): CharBuffer; final decode(ByteBuffer,CharBuffer,boolean): CoderResult; protected abstract decodeLoop(ByteBuffer,CharBuffer): CoderResult; detectedCharset(): Charset; final flush(CharBuffer): CoderResult; protected implFlush(CharBuffer): CoderResult; protected implOnMalformedInput(CodingErrorAction): void; protected implOnUnmappableCharacter(CodingErrorAction): void; protected implReplaceWith(String): void; protected implReset(): void; isAutoDetecting(): boolean; isCharsetDetected(): boolean; malformedInputAction(): CodingErrorAction; final maxCharsPerByte(): float; final onMalformedInput(CodingErrorAction): CharsetDecoder; final onUnmappableCharacter(CodingErrorAction): CharsetDecoder; final replaceWith(String): CharsetDecoder; final replacement(): String; final reset(): CharsetDecoder; unmappableCharacterAction(): CodingErrorAction |
| java.nio.charset | `CharsetEncoder` | abstract class | protected new(Charset,float,float,byte[]); protected new(Charset,float,float); final averageBytesPerChar(): float; canEncode(CharSequence): boolean; canEncode(char): boolean; final charset(): Charset; final encode(CharBuffer): ByteBuffer; final encode(CharBuffer,ByteBuffer,boolean): CoderResult; protected abstract encodeLoop(CharBuffer,ByteBuffer): CoderResult; final flush(ByteBuffer): CoderResult; protected implFlush(ByteBuffer): CoderResult; protected implOnMalformedInput(CodingErrorAction): void; protected implOnUnmappableCharacter(CodingErrorAction): void; protected implReplaceWith(byte[]): void; protected implReset(): void; isLegalReplacement(byte[]): boolean; malformedInputAction(): CodingErrorAction; final maxBytesPerChar(): float; final onMalformedInput(CodingErrorAction): CharsetEncoder; final onUnmappableCharacter(CodingErrorAction): CharsetEncoder; final replaceWith(byte[]): CharsetEncoder; final replacement(): byte[]; final reset(): CharsetEncoder; unmappableCharacterAction(): CodingErrorAction |
| java.nio.charset | `CoderResult` | class | static final CoderResult UNDERFLOW; static final CoderResult OVERFLOW; isError(): boolean; isMalformed(): boolean; isOverflow(): boolean; isUnderflow(): boolean; isUnmappable(): boolean; length(): int; static malformedForLength(int): CoderResult; throwException(): void; toString(): String; static unmappableForLength(int): CoderResult |
| java.nio.charset | `CodingErrorAction` | class | static final CodingErrorAction IGNORE; static final CodingErrorAction REPLACE; static final CodingErrorAction REPORT; toString(): String |
| java.nio.charset | `StandardCharsets` | final class | static final Charset US_ASCII; static final Charset ISO_8859_1; static final Charset UTF_8; static final Charset UTF_16BE; static final Charset UTF_16LE; static final Charset UTF_16 |
| java.nio.charset | `CharacterCodingException` | class extends IOException | new() |
| java.nio.charset | `MalformedInputException` | class extends CharacterCodingException | new(int); getInputLength(): int; getMessage(): String |
| java.nio.charset | `UnmappableCharacterException` | class extends CharacterCodingException | new(int); getInputLength(): int; getMessage(): String |
| java.nio.charset | `IllegalCharsetNameException` | class extends IllegalArgumentException | new(String); getCharsetName(): String |
| java.nio.charset | `UnsupportedCharsetException` | class extends IllegalArgumentException | new(String); getCharsetName(): String |
| java.nio.charset | `CoderMalfunctionError` | class extends Error | new(Exception) |
