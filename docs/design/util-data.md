# java.util Base64, UUID and BitSet: behaviour beyond the Javadoc

Part of the S5-04 specification (`docs/design/util.md` is the index). It lists what the Java 8
oracle (Temurin 1.8.0_504) does where the Java SE 8 Javadoc is silent or vague and a program can
see it: exact output, messages, the order of checks, sizes. Implement the Javadoc, then these rules.
How you build the classes is your choice; only what a program can observe is fixed here.

Each rule reads *input → observable result*. "Pinned by" names the test in `tests/java/util` and the
label its line starts with; every golden is blessed on the oracle. Formulas are Java expressions on
`int` or `long`. Section 7 lists what you may choose. Section 8 lists dependencies and the API.

Terms. The *basic alphabet* is `A`–`Z`, `a`–`z`, `0`–`9`, `+`, `/` (values 0–63, RFC 4648 table 1);
the *URL alphabet* replaces `+` and `/` with `-` and `_` (RFC 4648 table 2). The basic and MIME coders
use the basic alphabet, the URL coders the URL alphabet. A decoder's *alphabet bytes* are the 64
bytes of its alphabet; `=` is never one of them.

## 1. Base64 encoders

**B1.** Every encoder turns each 3 input bytes into 4 alphabet characters, high bits first. A final
group of 1 or 2 bytes becomes 2 or 3 characters followed by `==` or `=`. Empty input gives empty
output (`encode` returns an empty array). Only MIME encoders insert line separators.
Pinned by: Base64s ("enc 0" to "enc 8", "encode(byte[])", "fuzz encode").

**B2.** `withoutPadding()` returns an encoder like the receiver (same alphabet, lines and separator)
that leaves out the `=` characters. Pinned by: Base64s ("enc", "mime nopad"), Base64Streams ("enc tail").

**B3.** `getMimeEncoder()` writes lines of 76 characters separated by `\r\n`. No separator follows the
last line, even a full one: 57 bytes give 76 characters, 58 bytes give 82.
Pinned by: Base64s ("mime 57", "mime 114").

**B4.** `getMimeEncoder(lineLength, sep)` uses `lineLength` rounded down to a multiple of 4. When the
rounded value is ≤ 0 (any `lineLength` below 4, negative included) the output is one line with no
separator; `Integer.MAX_VALUE` is accepted. An empty `sep` is legal and joins the lines directly.
Pinned by: Base64s ("mime len", "sep empty", "two").

**B5.** `getMimeEncoder(lineLength, sep)` checks `sep` before anything else, whatever `lineLength` is:
`null` throws `NullPointerException`; the first byte of `sep` that is in the basic alphabet or is `=`
throws `IllegalArgumentException: Illegal base64 line separator character 0x` followed by the byte's
value in lower-case hex (`0x41`, `0x2b`, `0x3d`). Every other byte is allowed, `-`, `_`, NUL and bytes
≥ 0x80 included. Pinned by: Base64s ("sep", "bad second", "bad, len 0", "sep null").

**B6.** `encode(src, dst)` needs `dst.length` ≥ the exact output length (padding and separators
counted). If it is shorter: `IllegalArgumentException: Output byte array is too small for encoding all
input bytes`. Otherwise it writes the output at index 0, returns its length and leaves the rest of
`dst` alone. Pinned by: Base64s ("encInto").

**B7.** `getEncoder()`, `getUrlEncoder()`, `getMimeEncoder()`, `getDecoder()`, `getUrlDecoder()` and
`getMimeDecoder()` return the same object on every call. A `null` array, string or stream given to
`encode`, `encodeToString`, `decode` or `wrap` throws `NullPointerException`.
Pinned by: Base64s ("singletons", the "… null NPE" lines), Base64Streams ("bounds: null NPE").

## 2. Base64 decoders on arrays and strings

**B8.** A decoder reads its input byte by byte. An alphabet byte adds 6 bits to the current *unit*;
when a unit has 4 characters its 3 bytes are written out. A byte that is neither an alphabet byte
nor `=` is, for the basic and URL decoders, an error: `IllegalArgumentException: Illegal base64
character ` followed by `Integer.toString(b, 16)` of the *signed* byte (`a` for `\n`, `21` for `!`,
`-1` for 0xFF, `-80` for 0x80). The MIME decoder skips such bytes, including `-`, `_`, CR, LF and
everything ≥ 0x80. So the basic decoder refuses `-_` and the URL decoder refuses `+/`.
Pinned by: Base64s ("dec", "fuzz decode").

**B9.** `decode(String)` first turns the string into bytes as ISO-8859-1: a char ≤ U+00FF becomes
that byte, any other char becomes `?` (0x3F). Pinned by: Base64s ("latin1", "dec QUJD\u100").

**B10.** Padding. Let *p* be the number of characters in the current unit when a `=` is read:
- *p* = 0: `IllegalArgumentException: Input byte array has wrong 4-byte ending unit`;
- *p* = 1: `IllegalArgumentException: Last unit does not have enough valid bits`;
- *p* = 2: the very next byte must be `=` (the MIME decoder skips nothing in between), else the
  "wrong 4-byte ending unit" error; both `=` are consumed and the unit yields 1 byte;
- *p* = 3: the unit yields 2 bytes.

The bits below the last whole byte are dropped without a check (`AB==` gives `[0]`). After the
padding the decoder moves to B11. Pinned by: Base64s ("dec AA=", "dec A===", "dec QQ=\n=", "dec AB==").

**B11.** After padding. The basic and URL decoders accept nothing more: if any byte follows, the error
is `IllegalArgumentException: Input byte array has incorrect ending byte at ` + the index of the
first such byte. The MIME decoder skips bytes that are not alphabet bytes (further `=` included) and
fails on the first alphabet byte with the same message and that byte's index **plus one**.
Pinned by: Base64s ("dec AAA==", "dec AA==x", "dec AA===", "dec QQ==QUJD").

**B12.** Input that ends without padding: a unit with 1 character fails with "Last unit does not
have enough valid bits"; with 2 or 3 characters it yields 1 or 2 bytes. Empty input decodes to an
empty result. The basic and URL decoders (not MIME) first refuse any input of length 1, whatever
the byte, with `IllegalArgumentException: Input byte[] should at least have 2 bytes for base64
bytes`. Pinned by: Base64s ("dec A", "dec =", "dec !", "dec QUJDQ").

**B13.** `decode(src, dst)` computes an output size *E* before decoding (after the length-1 check of
B12) and throws `IllegalArgumentException: Output byte array is too small for decoding all input
bytes` when *E* > `dst.length`, writing nothing.
- Basic and URL: with `n = src.length`, `pad` is 2 if the last two bytes are `=`, 1 if only the
  last one is, else 0; if `pad` is 0 and `n % 4 != 0`, `pad = 4 - n % 4`; `E = 3 * ((n + 3) / 4) - pad`.
- MIME: with `c` the number of alphabet bytes before the first `=` (or in the whole input),
  `E = 3 * (c / 4) + max(c % 4 - 1, 0)`. So `E` = 0 never fails, even with an empty `dst`.

Decoding then writes into `dst` as it goes: when a later error is thrown, the bytes of every unit
completed before it are already in `dst`, and so are the padded unit's bytes when the error comes
from B11. It returns the number of bytes written; the rest of `dst` is untouched. `decode(byte[])`
and `decode(String)` return an array of exactly the decoded length.
Pinned by: Base64s ("into", "into big", "fuzz decode").

## 3. Base64 streams

**B14.** `Encoder.wrap(out)` returns a stream that writes each completed 3-byte group to `out` as soon
as it is complete and holds back 1 or 2 bytes until more data or `close()`. `write(int)` uses the low
8 bits. `flush()` does not emit a held-back group. For a MIME encoder, a separator is written only
when the first character of a new line is written, so never at the end. `close()` writes the held-back
group (padded unless the encoder is `withoutPadding()`), then closes `out`. Any split of the data into
`write` calls gives exactly `encodeToString` of the whole, except as B14a says.
Pinned by: Base64Streams ("enc stream", "enc tail", "mime stream", "enc close closes underlying",
"fuzz enc stream").

**B14a.** (Found by the implementer's differential fuzz; the first version of B14 said there was no
exception.) For `getMimeEncoder(lineLength, sep)` with `lineLength` 1, 2 or 3, arrays and strings
get one unbroken line (B4), but the stream writes `sep` once, before its first character, and never
again; with no data it writes nothing. `withoutPadding()` keeps this. `lineLength` ≤ 0 adds nothing.
So `getMimeEncoder(3, "|")` gives `AA==` from `encode` and `|AA==` from the stream. LazyVM matches.
Pinned by: Base64Streams ("mime short lines").

**B15.** After `close()`, `write(int)` and `write(b, off, len)` (even with `len` 0) throw
`IOException: Stream is closed`; `flush()` and a second `close()` do nothing.
Pinned by: Base64Streams ("closed").

**B16.** The encoding stream's `write(b, off, len)` with `off < 0`, `len < 0` or `off + len > b.length`
throws `ArrayIndexOutOfBoundsException` with no message; `off == b.length` with `len` 0 is fine.
Pinned by: Base64Streams ("bounds").

**B17.** `Decoder.wrap(in)` returns a stream of the decoded bytes. `read(b, off, len)` keeps reading
`in` until it has `len` bytes or reaches the end of the data, however few bytes each read of `in`
returns. It decodes as B8 (MIME skips non-alphabet bytes) but stops for good at the first padding:
after a unit ending in `=` (p = 3) or `==` (p = 2) it reports end of stream and never reads the rest,
whatever follows (`QUI=x` gives `AB`, `QUJDRA==QQ==` gives `ABCD`). Without padding, the end of `in`
ends the data. After the end, `read` keeps returning -1. The stream reads `in` only through
`in.read()`, one byte at a time, and only as far as the units the call needs: asking for 1 byte reads
one whole unit (4 alphabet bytes, and any skipped bytes before them) and keeps the 2 extra decoded
bytes for the next call, so after any call `in` has been read exactly to the end of a unit, or to the
padding, never beyond. (Added after review; the oracle does the same.)
Pinned by: Base64Streams ("dec stream", "eof", "fuzz dec stream", "dec source reads").

**B18.** Decoding stream errors are `IOException`s:
- a byte that is neither an alphabet byte nor `=`, for the basic and URL decoders:
  `Illegal base64 character -1`, always `-1`, whatever the byte;
- `=` with p = 0 or 1, or with p = 2 when the next byte is not `=` (or there is none):
  `Illegal base64 ending sequence:` + `(18 - 6 * p)` (no space: `:18`, `:12`, `:6`);
- end of `in` with p = 1: `Base64 stream has one un-decoded dangling byte.`

A call that throws returns no count, but the whole units it decoded before the error are already
in the caller's array, and bytes returned by earlier calls stand. The error does not lose the unit
being built: the byte that caused it is dropped (and, for `=` at p = 2, the byte read after it to
look for the second `=`), the characters of the unit begun before it are kept, and the next read
goes on from there. So `QQ=xQUJD`, read with `read()`, gives the `:6` error and then 65, 4, 20, 36
and the end (`QQ` + `QU`, then `JD`); `QU!DQUJD` gives the illegal-character error and then 65, 64,
208, 80, 144 (`QU` + `DQ`, then `UJD`); `Q===QUJD` gives `:12` three times, then 65, 5, 9 and the
dangling-byte error. MIME behaves the same for `=` (it has no illegal bytes). (Corrected after
review: the first version said the decoded bytes were lost and left reading on unspecified.)
Pinned by: Base64Streams ("dec stream", "after error", "read on after error").

**B19.** The decoding stream's `available()` returns `in.available()` while open. `markSupported()`
is false and `skip` reads and drops bytes. Bad bounds on `read(b, off, len)` throw
`IndexOutOfBoundsException` with no message; a `null` array throws `NullPointerException`; `read(b,
off, 0)` returns 0. All three hold only until the stream has *finished*: the data has ended (padding
read, or the end of `in` seen) and every decoded byte has been returned. From then on `read(b, off,
len)` returns -1 whatever its arguments, `len` 0, bad bounds and a `null` array included. So for
`QQ==`, `read()` gives 65 and then `read(b, 0, 0)` gives -1, while for `QUJD` after `read(b, 0, 3)`
(the end of `in` not yet seen) it gives 0. A B18 error finishes the stream only when it comes from
the end of `in` (the dangling byte); after an ending-sequence or illegal-character error, `read(b,
0, 0)` still gives 0 and bad bounds still throw (`QUJD====`: 3 bytes, the error, then 0). (Corrected
after review; the first version said 0 always.) `close()` closes `in`; after it, `read()`, `read(b,
off, len)` and `available()` throw `IOException: Stream is closed`, finished or not, and a second
`close()` does nothing. Pinned by: Base64Streams ("available", "read bounds", "read null",
"zero-length reads", "after error", "close closes underlying", "read after close", "read(b) after
close", "available after close", "close twice").

## 4. Base64 with java.nio buffers (waits for S5-06)

**B20.** `encode(ByteBuffer)` encodes the remaining bytes, moves the input's position to its limit and
returns a new heap buffer at position 0 whose limit and capacity are the output length. Direct
buffers work too. `decode(ByteBuffer)` on success moves the input to its limit and returns a new
buffer at position 0 holding the bytes; on an error it throws as for arrays and leaves the input's
position where it was. In the B11 message the index counts from the start of the backing array, not
from the buffer's position (input at position 3, `QQ==QQ==`: `at 7`; MIME `at 8`).
Pinned by: UtilBuffers ("encode buffer", "encode direct", "encode empty", "decode").

## 5. UUID

**U1.** `toString()` is 36 characters: 8-4-4-4-12 lower-case hex digits, zero padded. The first 16
digits are the high word (`getMostSignificantBits`), the last 16 the low word, both high nibble
first. `hashCode()` is `(int) (x >>> 32) ^ (int) x` with `x = msb ^ lsb`.
Pinned by: Uuids ("uuid", "fuzz rounds").

**U2.** `fromString(s)` splits `s` at `-` as `String.split("-")` does, so trailing empty fields vanish
(`1-1-1-1-1---` is accepted). Unless there are exactly 5 fields:
`IllegalArgumentException: Invalid UUID string: ` + `s` (this check comes first). Each field `f` is read
as a hex number the way `Long.decode("0x" + f)` reads it: any case, any char `Character.digit(c, 16)`
accepts (U+0661 and fullwidth digits included), no length limit while the value fits in a
non-negative `long`. A field starting with `+` throws `NumberFormatException: Sign character in wrong
position`; any other bad field (empty, non-hex, `0x1`, `#1`, spaces, ≥ 2^63) throws
`NumberFormatException: For input string: "` + `f` + `"`. `null` throws `NullPointerException`.
Pinned by: Uuids ("parse", "parse null", "fuzz short fields").

**U3.** The five values `f0`..`f4` are combined without masking: `msb = f0 << 32 | f1 << 16 | f2`,
`lsb = f3 << 48 | f4`. So an overlong field loses its top bits or spills into its neighbour
(`123456789-1-1-1-1` is `23456789-0001-…`, `1-12345-1-1-1` gives high word `0x123450001`).
Pinned by: Uuids ("parse").

**U4.** `version()` is `(int) ((msb >> 12) & 0xf)`. `variant()` reads the top bits of the low word: `0`
when the top bit is 0, `2` for `10`, `6` for `110`, `7` for `111`. Pinned by: Uuids ("variants").

**U5.** `timestamp()`, `clockSequence()` and `node()` work when `version()` is 1, whatever the variant,
and otherwise throw `UnsupportedOperationException: Not a time-based UUID`. `timestamp()` is
`(msb & 0xfff) << 48 | ((msb >>> 16) & 0xffff) << 32 | msb >>> 32`; `clockSequence()` is bits 48–61
of the low word; `node()` is its low 48 bits. Pinned by: Uuids ("v1", "v4", "v0").

**U6.** `compareTo` compares the high words as signed `long`s, then the low words as signed `long`s,
and returns -1, 0 or 1. `compareTo(null)` throws `NullPointerException`. `equals` is true only for a
UUID with both words equal. Pinned by: Uuids ("compareTo", "compare high first").

**U7.** `nameUUIDFromBytes(b)` takes the 16-byte MD5 digest of `b` (RFC 1321), sets byte 6 to
`(d[6] & 0x0f) | 0x30` and byte 8 to `(d[8] & 0x3f) | 0x80`, and reads bytes 0–7 and 8–15 as the
big-endian high and low words (version 3, variant 2). `null` throws `NullPointerException`. The test
covers lengths on both sides of the 56- and 64-byte MD5 padding boundaries.
Pinned by: Uuids ("md5", "fuzz rounds").

**U8.** `randomUUID()` has version 4 and variant 2 (122 random bits), and successive calls differ;
values also differ between runs of the program. Pinned by: Uuids ("random").

## 6. BitSet

**BS1.** A BitSet stores whole 64-bit words; `size()` is 64 × the number of words in its array.
`new BitSet()` has 1 word; `new BitSet(n)` has `(n + 63) / 64` words (0 for 0) and is *sized*
(BS4). A negative `n` throws `NegativeArraySizeException: nbits < 0: ` + `n`.
Pinned by: BitSets ("ctor sizes", "ctor -1").

**BS2.** When an operation must store a 1 in word `w` and the array has `k ≤ w` words, the array grows
to `max(2 * k, w + 1)` words and the set stops being sized. Operations that can grow: `set(i)`,
`set(i, true)`, `flip(i)`, the non-empty ranges of `set(from, to)`, `set(from, to, true)` and
`flip(from, to)` (they need the word of bit `to - 1`), and `or(s)` and `xor(s)`, which need as many
words as `s` has *in use* (words up to its highest set bit), not `s.size()`. Nothing else grows the
array: `clear` in all forms, `set(i, false)`, ranges set to false, empty ranges, `get`, `and`,
`andNot`. Only `clone()` shrinks it: cloning a set that is not sized (BS4) first trims the
receiver's own array to its words in use (0 words when empty), so its `size()` drops and later growth
starts from the trimmed size; a sized set keeps its array. Nothing else shrinks it, `clear()`
included. (Corrected after review: the first version said `clone()` left the receiver alone.)
Pinned by: BitSets ("growth default", "growth 64", "growth 0", "no growth", "flip grows",
"range grows", "set false grows?", "or", "clear()", "clone trims receiver", "fuzz sum").

**BS3.** `length()` is the highest set bit + 1 (0 when empty); `cardinality()` counts set bits;
`isEmpty()` ignores the size. Pinned by: BitSets (every `len`/`card` field, "intersects").

**BS4.** `clone()` of a sized set has the same number of words and is itself sized. A clone of any
other set has exactly the source's words in use (0 words, so size 0, when it is empty). A set is
sized when it was made by `new BitSet(int)` or returned by `get(from, to)` (BS6), or is a clone of a
sized set, and has not grown since. `valueOf` results and `new BitSet()` are not sized.
Pinned by: BitSets ("clone …" lines, "fuzz sum").

**BS5.** `toString()` lists the set bits in increasing order as `{1, 3, 64}`; the empty set is `{}`.
`valueOf(long[])` and `valueOf(byte[])` read bit `k` of element `j` as bit `64 * j + k` (resp.
`8 * j + k`), copy the input, and keep only the words up to the last non-zero one. `toLongArray()`
and `toByteArray()` return the same little-endian layout up to the last non-zero word or byte (an
empty array for the empty set). Pinned by: BitSets ("toString", "valueOf long", "valueOf byte",
"valueOf copies", "toLongArray").

**BS6.** `get(from, to)` (checked as BS8) returns an empty set with 0 words when `from == to` or
`length() <= from`. Otherwise, with `t = min(to, length())`, it returns a sized set as
`new BitSet(t - from)` holding bits `from`..`t - 1` moved down by `from`.
Pinned by: BitSets ("get range", "get result").

**BS7.** `nextSetBit(i)` returns the first set bit ≥ `i`, or -1. `nextClearBit(i)` returns the first
clear bit ≥ `i`, which is `i` itself at or past `length()`. `previousSetBit(i)` and
`previousClearBit(i)` look at bits ≤ `i`; past `length()`, `previousClearBit(i)` is `i`; both return
-1 for `i` = -1 or when nothing qualifies. Pinned by: BitSets ("searches", "full nextClear").

**BS8.** Index errors are `IndexOutOfBoundsException` with these messages, checked in this order:
- one index (`set`, `set(i, b)`, `get`, `clear`, `flip`) below 0: `bitIndex < 0: ` + `i`;
- ranges (`set`, `clear`, `flip`, `get`): `fromIndex < 0: ` + `from`, then `toIndex < 0: ` + `to`,
  then `fromIndex: ` + `from` + ` > toIndex: ` + `to`;
- `nextSetBit`, `nextClearBit` below 0: `fromIndex < 0: ` + `i`; `previousSetBit`,
  `previousClearBit` below -1: `fromIndex < -1: ` + `i`.

A `null` BitSet or array argument throws `NullPointerException`.
Pinned by: BitSets ("err set", "err ranges", "err searches", "err null").

**BS9.** `equals` is true for any BitSet with the same set bits, whatever the sizes. `hashCode()`
follows the formula in its Javadoc over the words in use (1234 for the empty set).
`intersects(s)` is true when some bit is set in both. Pinned by: BitSets ("equals", "intersects").

**BS10.** (Waits for S5-06.) `valueOf(ByteBuffer)` and `valueOf(LongBuffer)` read the remaining elements
from position to limit with the layout of BS5, ignore the buffer's byte order, leave the buffer
unchanged, and keep only the words in use. Pinned by: UtilBuffers ("valueOf bytes", "valueOf longs").

## 7. Not observable or not pinned: the implementation may choose

- The `toString()` of Base64 encoders, decoders and their streams (identity hash codes); whether
  `withoutPadding()` returns a new object each time; how many `write` calls the encoding stream makes
  on `out` for the same bytes; whether its `flush()` flushes `out`.
- Nothing about the decoding stream after an `IOException` is left free: B18 and B19 fix it. A
  differential fuzz of 100,000 random inputs over the three decoders, reading on past every error
  one byte, 3 bytes or 64 bytes at a time with empty reads mixed in, matched the oracle in every
  count, every exception, the caller's array after each throw and the source position. The only
  divergence is the defect below, which the fuzz avoids by not mixing read sizes.
- A Java 8 defect: a decoding stream read with `read(b, off, len)` can return zeros or never end when
  the data ends in a 2-byte unit and the requested sizes split it badly (for 5 decoded bytes, reads of
  2 or 4 at a time; for 8, reads of 7). LazyVM must return the right bytes in every case. Tests read
  1 byte at a time, 3 at a time, or with room for everything, where Java 8 is correct.
- A second Java 8 defect: its MIME decoder on arrays throws `ArrayIndexOutOfBoundsException` (with
  the signed byte, or no message) for a byte ≥ 0x80 after the padding. LazyVM follows B11 and skips
  it; no test reaches the case. (A scratch model of B8–B13 matched the oracle on 900,000 random
  decodes apart from this case.)
- How `randomUUID()` gets its bits (Java 8 uses a strong generator), provided calls and runs differ.
- BitSets near `Integer.MAX_VALUE` bits, where Java 8's `length()`, `size()` and `nextClearBit`
  overflow to negative values: not pinned, since a test would need a 256 MB array.
- `BitSet.stream()` comes with streams (S11) and is not pinned here.

## 8. Dependencies and API to provide

- **MD5.** lazyrt has no MD5 and no `java.security`. `nameUUIDFromBytes` needs one: write it from
  RFC 1321 as a package-private or `lazy.internal` helper (adding `java.security.MessageDigest` is a
  separate decision). Uuids pins 21 lengths plus 2000 fuzz inputs.
- **java.nio.** lazyrt on main has no `java.nio`. The `ByteBuffer` and `LongBuffer` overloads (B20,
  BS10) and the UtilBuffers test wait for S5-06 (branch `s5-io`); the other tests use only arrays and
  `java.io` streams, which exist.
- **Random bits** for `randomUUID` (U8).
- UUID parsing relies on `String.split` with a literal and on `Long.decode`'s errors (U2), both in
  lazyrt already.

Members are those each class declares; parameter types are erased; take return types and exceptions
from the Javadoc. `new(…)` is a constructor.

| Class | Kind | Members |
|---|---|---|
| `Base64` | class | static getDecoder(); static getEncoder(); static getMimeDecoder(); static getMimeEncoder(); static getMimeEncoder(int, byte[]); static getUrlDecoder(); static getUrlEncoder() |
| `Base64.Encoder` | static class | encode(byte[]); encode(byte[], byte[]); encode(ByteBuffer) (S5-06); encodeToString(byte[]); withoutPadding(); wrap(OutputStream) |
| `Base64.Decoder` | static class | decode(byte[]); decode(byte[], byte[]); decode(ByteBuffer) (S5-06); decode(String); wrap(InputStream) |
| `UUID` | final class implements Serializable, Comparable | new(long, long); clockSequence(); compareTo(UUID); equals(Object); static fromString(String); getLeastSignificantBits(); getMostSignificantBits(); hashCode(); static nameUUIDFromBytes(byte[]); node(); static randomUUID(); timestamp(); toString(); variant(); version() |
| `BitSet` | class implements Cloneable, Serializable | new(); new(int); and(BitSet); andNot(BitSet); cardinality(); clear(); clear(int); clear(int, int); clone(); equals(Object); flip(int); flip(int, int); get(int); get(int, int); hashCode(); intersects(BitSet); isEmpty(); length(); nextClearBit(int); nextSetBit(int); or(BitSet); previousClearBit(int); previousSetBit(int); set(int); set(int, boolean); set(int, int); set(int, int, boolean); size(); stream() (S11); toByteArray(); toLongArray(); toString(); static valueOf(byte[]); static valueOf(long[]); static valueOf(ByteBuffer) (S5-06); static valueOf(LongBuffer) (S5-06); xor(BitSet) |
