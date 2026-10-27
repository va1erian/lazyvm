# Scanner, Properties and StringTokenizer: behaviour beyond the Javadoc

Part of S5-04. This is the behavioural specification for LazyVM's `java.util.Scanner`,
`java.util.Properties` and `java.util.StringTokenizer`, in the style of
[`collections.md`](collections.md): implement the Java SE 8 Javadoc, then these rules. Each rule
reads *input → observable result* and ends with the test (in `tests/java/util`) and line label that
pin it. All goldens were blessed on the Temurin 1.8.0_504 oracle. How the classes are built is the
implementer's choice; only what a program can observe is fixed here.

**Dependencies.** `Scanner` waits for `java.util.regex` (branch `s5-regex`, not merged; its
supported syntax is `docs/regex.md` on that branch) and for `java.lang.Readable`,
`java.nio.CharBuffer` and the charset decoders of `java.io`/`java.nio` (branch `s5-io`, not merged;
`docs/design/io.md` there). `Properties` waits for `s5-io` too: `BufferedWriter` (rule P12),
`BufferedReader`/`InputStreamReader` if the implementer wants them, and the ISO 8859-1 byte
handling. Main's lazyrt already has `Reader`, `Writer`, `StringReader`, `StringWriter`,
`InputStream`, `OutputStream`, `ByteArrayInputStream`, `ByteArrayOutputStream`, `PrintStream`,
`PrintWriter` and `Hashtable`. Also missing on every branch: `java.util.InputMismatchException`
(Scanner), `java.util.Date` (the date line of `Properties.store`, P11) and `java.math` (so
`Scanner`'s `BigInteger`/`BigDecimal` methods are left out until `java.math` exists; they are
listed in section 5 as deferred). Until those branches merge, the tests below do not compile
against lazyrt; their goldens were blessed through a JDK-compiled wrapper and are identical to
what `tools/golden.py` writes.

**Regex features Scanner needs.** Its default delimiter is `\p{javaWhitespace}+`; its number,
boolean, line and NaN/infinity recognisers need character classes with `&&` intersection (for
"digit but not 0"), `\p{javaDigit}`, `\Q…\E` quoting, alternation, greedy and possessive or
atomic repeats, capturing groups, `$` and the `hitEnd`/`requireEnd` and region and
`useTransparentBounds`/`useAnchoringBounds` parts of `Matcher` (a scanner over a stream must know
when more input could change a match). `docs/regex.md` on `s5-regex` lists all of these as
supported. Whether Scanner uses regexes internally for numbers is the implementer's choice; only
the accepted language (SC10–SC17) is fixed.

Notation: `<e9>` in a test line is the char U+00E9 (tests print every char outside 0x20–0x7E
that way). "ROOT" is `Locale.ROOT`.

## 1. Scanner

Every scanner in the tests calls `useLocale(Locale.ROOT)` (or `Locale.US`), because the oracle's
default locale is a property of the machine: on the blessing machine its format locale is
`fr_FR`, whose decimal separator is `,` and group separator U+00A0. LazyVM's default locale is
its own (`Locale.getDefault()`); no test depends on it.

### Tokens and delimiters

**SC1.** The default delimiter is the pattern `\p{javaWhitespace}+`, that is, runs of chars for
which `Character.isWhitespace` holds: tab, newline, U+000B, U+2003, U+001C and U+2028 separate
tokens, U+00A0 does not. Pinned by: Scanners ("ws").

**SC2.** Finding a token: at the current position, one match of the delimiter pattern is skipped
if it starts right there (for `,` that is one comma; for `,+` all of them). If the input then
ends, there is no token. Otherwise the token runs up to the start of the next delimiter match or
the end of input. So `,a,,b,` with `,` gives `a`, ``, `b` (no token for the leading or the
trailing comma), `,,,` gives two empty tokens, and `,,,x` three tokens. An empty delimiter
pattern makes each char a token; a zero-width lookahead splits before each match. With no token,
`next()` and every `nextX()` throw `NoSuchElementException` with no message, and `hasNext()` is
false. Pinned by: Scanners ("comma", "empty delimiter", "no token").

**SC3.** `useDelimiter(String)` compiles its argument with no flags; `useDelimiter(Pattern)` keeps
the given pattern, and `delimiter()` returns the pattern in use (its `toString` is the source). An
invalid pattern string throws the regex `PatternSyntaxException`. `useDelimiter((Pattern) null)`
is accepted (the scanner's `toString` then shows `delimiters=null`; what later calls do is free).
`useDelimiter((String) null)` throws `NullPointerException`. Pinned by: Scanners ("delimiter",
"bad delimiter", "nulls").

**SC4.** `hasNext(pattern)` and `next(pattern)` take the next token (SC2) and test the *whole*
token against the pattern. `next(pattern)` on a token that does not match throws
`InputMismatchException` with no message and leaves the token in place; with no token it throws
`NoSuchElementException`; `hasNext(pattern)` is then false. After a successful `next(pattern)`,
`match()` is that pattern's match over the token (its groups, `start`/`end` counted in the whole
input). Pinned by: Scanners ("pattern", "pattern groups", "pattern then token").

### Lines

**SC5.** A line ends at `\r\n`, `\n`, `\r`, U+2028, U+2029 or U+0085 (not U+000B or U+000C).
`nextLine()` returns the rest of the current line without its terminator and moves past the
terminator. A last line without a terminator is a line; input that is empty, or that ends right
after a terminator, has no further line. `\r\r\n` is two lines, `\n\r` two empty lines. With no
line, `nextLine()` throws `NoSuchElementException: No line found` and `hasNextLine()` is false.
`nextLine()` after a token returns what follows the token on its line (often the empty string).
After `nextLine()`, `match().group()` is the line *with* its terminator, `start()`/`end()` its
place in the input. Pinned by: Scanners ("lines", "nextLine none", "int then line", "int then
line2", "line match").

### Searching and skipping

**SC6.**
- `findInLine(p)` looks for `p` from the current position up to (not including) the next line
  terminator, ignoring delimiters. On success it returns the match and moves past it; otherwise
  it returns null and does not move. A match never includes the terminator: `findInLine("x\\s")`
  on `x\ny` is null, while an empty match (`\s*`) succeeds.
- `findWithinHorizon(p, h)` looks for `p` in the next `h` chars (0: the rest of the input),
  ignoring delimiters; success and failure move as for `findInLine`. A negative `h`
  throws `IllegalArgumentException: horizon < 0`.
- `skip(p)` matches `p` anchored at the current position, ignoring delimiters, and moves past the
  match (an empty match is allowed). If `p` does not match there it throws
  `NoSuchElementException` with no message and does not move.

Pinned by: Scanners ("findInLine", "findInLine none", "findInLine newline", "horizon", "horizon
errors", "skip", "skip keeps position", "horizon chunked").

**SC7.** `match()` returns the result of the last successful `next…`, `nextLine`, `findInLine`,
`findWithinHorizon` or `skip`. Before any, after `hasNext…` (successful or not), and after a failed
`find…`, it throws `IllegalStateException: No match result available`. For `nextInt()` and the
other numeric readers, `group()` is the whole token and `start()`/`end()` its place in the input
(`groupCount()` and the other groups are free, section 4). Closing does not clear the last match.
Pinned by: Scanners ("match int", "match none", "closed still fine").

### Sources

**SC8.** A scanner over a `Readable` or an `InputStream` gives exactly the results of a scanner
over the same text as a `String`, however the source splits its reads: one char per read, a
`\r\n` split across reads, a token longer than any buffer (5000 chars), `findWithinHorizon` across
a split. An `InputStream` source is decoded with the named charset; malformed and truncated
sequences become U+FFFD (one per bad sequence, as the io decoders do). A wrong charset name throws
`IllegalArgumentException` whose message is the `toString()` of the
`java.nio.charset.UnsupportedCharsetException` (`java.nio.charset.UnsupportedCharsetException:
bogus`); a null one `NullPointerException: charsetName`. Pinned by: Scanners ("utf8 bytewise",
"latin1", "malformed", "long token", "crlf split", "horizon chunked", "charset", "fuzz chunked
same 2500 of 2500"; the fuzz runs 12 random operations on random inputs over `a1 ,\r\n-.` and
U+2028 with three delimiters, and also pins the string-source results through its hash).

**SC9.** When the source's `read` throws an `IOException`, the scanner treats it as the end of
input and never reads again; `ioException()` returns that exception (null before). Tokens read
before the failure are still returned. Pinned by: Scanners ("io before", "io tokens", "io again").

### Numbers and booleans

Integer readers (`nextByte`, `nextShort`, `nextInt`, `nextLong` and their `hasNext` forms) and
the floating ones (`nextFloat`, `nextDouble`) apply to the next token (SC2), which must match as a
whole. A `hasNextX` never consumes input; a `nextX` that fails throws `InputMismatchException` and
leaves the token for the next call (pinned by every `left …` in ScannerNumbers, and "no
consume"). The rules below are for ROOT, whose group separator is `,`, decimal separator `.`,
negative prefix `-` and other prefixes and suffixes empty; `Locale.US` gives the same symbols
(Scanners "toString US", ScannerNumbers "US"). Other locales are out of scope until `java.text`.

**SC10.** An integer token is an optional `+` or `-` followed by digits (SC11, SC12); leading
zeros are fine (`-007` is -7, `-0` is 0). Nothing else is accepted: no second sign, no trailing
sign, no point, exponent, `0x`, `_` or parentheses. A failure because the token has the wrong
shape throws `InputMismatchException` with a null message. The token is examined again for each
call, so a delimiter change after `hasNextInt` takes effect (`1,2` with `[ ,]`). Pinned by:
ScannerNumbers ("int", "no consume", "delimiter change").

**SC11.** Digits may be grouped: a first group of one to three digits whose first digit is not
`0` and is a decimal digit (`1`–`9` or a non-ASCII digit, never a letter, whatever the radix),
then one or more groups of exactly three digits, each after a `,`. So `1,234`, `999,999`,
`-1,234,567`, `1,fff` (radix 16) and `9,abc` are accepted, and `1,23`, `1,2345`, `12,34,567`,
`1234,567`, `,123`, `123,`, `1,,234`, `0,123`, `01,234`, `a,bcd`, `f,123` are not. The separators
are removed before the value is computed. Pinned by: ScannerNumbers ("group", "group16", "r2").

**SC12.** A digit is an ASCII digit or letter valid in the radix (either case), or any BMP char
for which `Character.isDigit` holds (Arabic-Indic U+0661, Devanagari U+096B, fullwidth U+FF11);
such non-ASCII digits have their decimal value in any radix (`١f` in radix 16 is 31). Fullwidth
*letters* (U+FF26) are not digits. A supplementary digit (U+1D7CE, a surrogate pair) is part of
the token's shape but fails to parse: `hasNextInt` is false and `nextInt` throws
`InputMismatchException: For input string: "…"` with the token. Pinned by: ScannerNumbers
("digits").

**SC13.** Range. A well-shaped integer outside the type's range is not accepted (`hasNextX`
false); `nextX` throws `InputMismatchException` whose message is the `NumberFormatException`
message of parsing the token with its group separators removed (sign and non-ASCII digits kept):
for `int` and `long`, `For input string: "2147483648"`, `"+2147483648"`, `"1٢345678901"`; for
`byte` and `short`, `Value out of range. Value:"128" Radix:10` (radix 16: `Value:"ff" Radix:16`).
The limits are those of the type (`-128`, `-9,223,372,036,854,775,808` accepted). Pinned by:
ScannerNumbers ("range", "long", "byte", "byte16", "short", "hex", "r36", "r2").

**SC14.** Radix. The scanner's radix (default 10, `useRadix`, `radix()`) is used by the
no-argument integer readers; `hasNextInt(r)`/`nextInt(r)` use `r`. There is no `0x` prefix (in
radix 36 `0xff` is just digits, 43323). A `hasNextInt(16)` success says nothing for a following
`nextInt()` in radix 10, which examines the token afresh and can fail. `nextDouble` and
`nextFloat` ignore the radix: they always read decimal (`10` is 10.0 under radix 16; `ff` is not a
double). Pinned by: ScannerNumbers ("hex", "r36", "hasNextInt(16) then nextInt()",
"hasNextInt(10) then nextInt(16)", "useRadix(16)").

**SC15.** A decimal floating token: an optional sign, then a mantissa of digits (grouped by SC11 or
not) with an optional `.` and fraction digits (ungrouped), at least one digit in all (`5.`, `.5`,
`00.5` are fine; `.`, `.e5` are not), then an optional exponent `e` or `E`, optional sign, and one
or more ungrouped digits (`1e`, `1e+`, `1e1,000` are not accepted). No `d`/`f` suffix, no `_`, no
second point. The value is `Double.parseDouble` (or `Float.parseFloat`) of the token with group
separators removed and BMP non-ASCII digits replaced by their ASCII digits: so huge values give
±Infinity and tiny ones 0.0, `-0.0` stays negative zero. When that parse fails (a supplementary
digit is left as it is), `hasNextDouble` is false and `nextDouble` throws
`InputMismatchException: For input string: "…"` naming the converted text (`1.𝟎` for `١.𝟎`).
Pinned by: ScannerNumbers ("dbl", "dbl range", "dbl digits", "flt").

**SC16.** Non-numbers. `NaN` and `Infinity`, case-sensitive, each with an optional `+` or `-`,
are accepted (`-NaN` is NaN; `nan`, `INFINITY`, `Inf` are not). The locale's own NaN and infinity
symbols (U+FFFD and U+221E under ROOT) have the right shape but never parse: `hasNextDouble` is
false and `nextDouble` throws `InputMismatchException: For input string: "∞"` (`"-∞"` with its
sign). Pinned by: ScannerNumbers ("nonnum").

**SC17.** Hexadecimal floats: an optional sign, `0x` or `0X`, ASCII hex digits, a `.`, then at
least one ASCII hex digit, then optionally `p`/`P`, an optional sign and ASCII decimal digits. The
value is `Double.parseDouble` of the token, which needs the `p` part: `0x1.8p1` is 3.0, `0x.8p1`
1.0, while `0x1.8` has the shape but fails (`For input string: "0x1.8"`). Without a point, or
without a digit after it (`0x1p3`, `0x1.`, `0x.p1`), or with non-ASCII digits or a group
separator anywhere, the shape is wrong (null message). Pinned by: ScannerNumbers ("hexdbl",
"flt").

**SC18.** `nextBoolean` accepts `true` and `false` in any case, as the whole token, and nothing
else (`yes`, `1`, `t`, `true,`). Pinned by: ScannerNumbers ("bool").

**SC19.** Radix arguments. `useRadix(r)` outside 2–36 throws `IllegalArgumentException: radix:r`.
`hasNextInt(r)` and `hasNextLong(r)` with `r` 0 or 1 return false, and `nextInt(r)` throws
`InputMismatchException: radix 1 less than Character.MIN_RADIX` (`radix 0 …`); with `r` above 36
or negative, all three throw `StringIndexOutOfBoundsException: String index out of range: 37`
(`… -1`). Pinned by: ScannerNumbers ("radix 1" … "radix -1").

### Closing, arguments, reset, toString

**SC20.** `close()` closes the source if it is `Closeable`; a second `close()` does nothing. After
closing, `next`, `hasNext`, `nextLine`, `hasNextLine`, every `nextX`/`hasNextX`, `hasNext(pattern)`,
`findInLine`, `findWithinHorizon` and `skip` throw `IllegalStateException: Scanner closed`, while
`delimiter`, `locale`, `radix`, `useDelimiter`, `useRadix`, `reset`, `ioException` and `match`
still work. Pinned by: Scanners ("closed source", "closed", "closed still fine").

**SC21.** Arguments. `new Scanner((Readable) null)` throws `NullPointerException: source`; null
`String` or `InputStream` sources, null patterns, a null locale: `NullPointerException` with no
message. `remove()` throws `UnsupportedOperationException`; Scanner is an `Iterator<String>`.
Pinned by: Scanners ("nulls", "remove").

**SC22.** `reset()` restores the default delimiter, radix 10 and the default locale, and returns
the scanner. Pinned by: Scanners ("reset").

**SC23.** `toString()` of a scanner that has not read anything, under ROOT or US, is exactly
`java.util.Scanner[delimiters=\p{javaWhitespace}+][position=0][match valid=false][need input=false][source closed=false][skipped=false][group separator=\x{2c}][decimal separator=\x{2e}][positive prefix=][negative prefix=\Q-\E][positive suffix=][negative suffix=][NaN string=\Q`U+FFFD`\E][infinity string=\Q`U+221E`\E]`,
with the delimiter's pattern source in the first field and the radix not shown. Pinned by:
Scanners ("toString", "toString US", "toString radix/delim").

## 2. Properties

`Properties` extends `Hashtable<Object,Object>`; its table order, `toString`, `equals` and the
rest are Hashtable's (collections.md section 2), already on main.

### load

**P1.** Lines. `load` splits the input at `\n`, `\r` and `\r\n`. A line whose first char that is
not a space, `\t` or `\f` is `#` or `!` is a comment; a line of only those blanks is skipped. Other
line terminators (U+2028, U+000B) and U+0000 are ordinary chars. Pinned by: PropertiesIO ("load
[…]" lines with `#`, `!`, `<d>`, `<2028>`, `<b>`, `<0>`).

**P2.** Continuation. A line ending in an odd number of backslashes continues: the backslash and
the terminator are dropped and the next line's leading blanks are skipped before it is joined. A
continued line is never a comment, even if it starts with `#`; a comment line never continues. A
backslash at the very end of input is dropped. A continuation line that is itself only `\` keeps
continuing; one that is empty ends the logical line. Pinned by: PropertiesIO ("a=1\<a>   2",
"a=1\<a># not comment", "#a\<a>b=1", "a=1\", "a=1\<a>\<a> x", "a=b\<a><a> c=d", "\<a>").

**P3.** Key and value. Leading blanks are skipped. The key runs up to the first unescaped `=`,
`:`, space, `\t` or `\f`. After the key, blanks are skipped, then at most one `=` or `:`, then
blanks again; the rest of the logical line is the value, trailing blanks included. So `a :=1` has
value `=1`, `a=:1` value `:1`, `a` and `a=` an empty value, `=1` an empty key. Pinned by:
PropertiesIO (the first twenty "load" lines).

**P4.** Escapes, in keys and values alike: `\t` `\n` `\r` `\f` give those chars, `\uXXXX` (exactly
four hex digits, either case) gives that char (surrogates are not combined or checked), and a
backslash before any other char gives that char (`\ ` `\=` `\:` `\#` `\\` `\x`). An escaped
separator does not end the key; `=` gives a key char `=`, not a separator. Pinned by:
PropertiesIO ("a\ b=1" … "\\=1", "a b=c", "==1").

**P5.** A `\u` not followed by four hex digits (fewer chars left, or a non-hex char) throws
`IllegalArgumentException: Malformed \uxxxx encoding.` In a comment line it is ignored. Pinned
by: PropertiesIO ("a=\u004", "a=\u00G1", "a=\uzzzz", "a=\u", "#\u12").

**P6.** Each pair is stored with `put` (overridable, P17), so a later duplicate key wins and
existing entries stay. Pinned by: PropertiesIO ("load merges", "a=1<a>a=2", "spy load").

**P7.** `load(InputStream)` reads bytes as ISO 8859-1 (each byte one char), so UTF-8 bytes give
two chars; escapes then apply. Pinned by: PropertiesIO ("load stream").

**P8.** A reader handing out one char per `read` gives the same table as a `StringReader`.
Pinned by: PropertiesIO ("load fuzz hash … chunked same 3000", which runs 3000 random texts over
`ab=: \`, the line ends, `#!`, tab, `u0` and U+00E9, and pins the tables through the hash).

### store

**P9.** Key escaping: `\` becomes `\\`; tab, newline, CR and form feed become `\t` `\n` `\r` `\f`;
`=`, `:`, `#` and `!` are preceded by a backslash wherever they occur; every space is written
`\ `. Value escaping is the same except that only a space at the very start of the value is
escaped (`  two lead` is written `\  two lead`; inner and trailing spaces stay). An empty key and
value give the line `=`. Pinned by: PropertiesIO ("store writer", "store stream").

**P10.** Chars outside printable ASCII. Through `store(Writer, …)` they are written as they are
(U+00E9, U+20AC, U+0001, U+007F, U+0000, lone surrogates). Through `store(OutputStream, …)`
every char below U+0020 or above U+007E is written `\uXXXX` with upper-case hex digits (`é`,
`\u007F`, `😀`), and the output is ISO 8859-1 bytes. Pinned by: PropertiesIO ("store
writer", "store stream", "store fuzz", where 600 random tables over a set of special chars
survive store then load both ways).

**P11.** Layout. If the comments argument is not null they come first (P12); then one line `#`
followed by the current date in `Date.toString()` form (`Tue Oct 27 19:30:42 CET 2026`: day,
month, day of month, time, zone, year; the tests check that shape and mask the line); then one
`key=value` line per entry. Every line, the last included, ends with `System.lineSeparator()`
(`\r\n` on Windows), except through a `BufferedWriter` (P12). Pinned by: PropertiesIO ("#DATE" in every store line, "store order").

**P12.** Comments. The first comment line starts with `#` whatever the text starts with
(`#already` gives `##already`). A `\n`, `\r` or `\r\n` in the comment ends the line; the next line
gets a `#` unless its first char is `#` or `!`. A trailing break gives a last `#` line. Chars above
U+00FF are written `\uXXXX` (upper case) through both a Writer and a stream; other chars are
written as they are (U+00E9, U+0001; as one byte through a stream). When the target is a
`BufferedWriter`, each line ends with its `newLine()` (five
calls for two comment lines, the date and two entries). Pinned by: PropertiesIO ("comment
writer", "comment stream", "comment breaks", "comment empty", "store BufferedWriter newLine
calls").

**P13.** Order. `store` walks `keys()` of the table, one entry per key in that order, and fetches
each value with `get` (P17). So the order is Hashtable's, and a subclass overriding `keys()`
(the usual way to get sorted output) changes it. Defaults are not stored. Pinned by:
PropertiesIO ("store order", "store keys() override", "spy store").

**P14.** A non-String key or value in the table makes `store` throw `ClassCastException`
(`java.lang.Integer cannot be cast to java.lang.String`). Pinned by: PropertiesIO ("store
non-string").

### Defaults, names and list

**P15.** `getProperty(k)` looks in the table; if the value there is not a String (or absent), it
asks `defaults.getProperty(k)` (the overridable method of the defaults object) when there are
defaults, else returns null. `getProperty(k, d)` calls `getProperty(k)` once and returns `d` for
null. `setProperty` calls `put` and returns the previous value. `stringPropertyNames()` is the set
of keys, own and inherited, whose key and value are both Strings; a pair that is not is skipped
where it is found, so an own non-String value does not hide a String default (`o` stays). The
set is unmodifiable. `propertyNames()` lists every key whose key is a String, values of any type
included, and throws `ClassCastException` (as P14) if any key, own or inherited, is not a String.
Pinned by: PropertiesIO ("get", "names", "names non-string", "names default non-string", "spy
getProperty", "spy defaults chain", "non-string own value").

**P16.** `list(PrintStream)` and `list(PrintWriter)` print `-- listing properties --`, then one
`key=value` line per name (keys whole; values longer than 40 chars cut to their first 37 chars
plus `...`), each line ending as `println` does. Own values hide defaults. A non-String key or
value (own or inherited) throws `ClassCastException`. Pinned by: PropertiesIO ("list writer",
"list stream", "list non-string").

**P17.** Overridable calls. `setProperty` and `load` call `put` once per pair. `store`, `list`,
`stringPropertyNames` and `propertyNames` call `keys()` once on the object itself and then
`get(key)` once per own key, in that order; they call no `entrySet`, `keySet` or `elements`. The
defaults are walked first, deepest level first, through their own `keys()`/`get` in the same
way. Pinned by: PropertiesIO ("spy …" lines, "spy defaults list", "spy defaults names").

**P18.** Arguments. A null reader, stream, writer, print stream or print writer, or a null key or
value for `setProperty`, or a null key for `getProperty`, throws `NullPointerException` with no
message (`list(null)` even on an empty table); `new Properties(null)` is a table without
defaults. Pinned by: PropertiesIO ("nulls").

**P19.** Name order. `list`, `stringPropertyNames` and `propertyNames` collect the names into a
fresh `new Hashtable()` (11 buckets, load factor 0.75): the deepest defaults first, each level
added in its own `keys()` order with its `get` values, then this table's keys; a later level
overwrites an earlier value. The result iterates in that Hashtable's order
(collections.md section 2). Pinned by: PropertiesIO ("names", "list writer", "names fuzz …",
"names fuzz hash", which fuzzes 400 random chains of one to three levels).

## 3. StringTokenizer

**T1.** The default delimiters are exactly space, `\t`, `\n`, `\r` and `\f` (not U+000B, U+001C,
U+00A0, U+2003). Pinned by: Tokenizers ("default").

**T2.** Tokens are maximal runs of non-delimiter chars; empty tokens never occur. With
`returnDelims` each delimiter is also returned as a token of its own (one per delimiter, so `,,,`
gives three). An empty delimiter string makes the whole string one token. Pinned by: Tokenizers
("delims", "empty").

**T3.** `nextToken(delim)` sets the delimiters for this call and every later one, then reads from
the current position: after `a` in `a b,c d` the switch to `,` gives ` b` and then `c d`.
Switching to no delimiters makes the rest one token. Pinned by: Tokenizers ("switch", "switch
returned", "switch to none", "switch back").

**T4.** `countTokens()` is the number of `nextToken()` calls that would succeed with the current
delimiters, from the current position, and does not move. Pinned by: Tokenizers ("count", "count
returned").

**T5.** `hasMoreElements`/`nextElement` are `hasMoreTokens`/`nextToken`. Past the end,
`nextToken()`, `nextToken(delim)` and `nextElement()` throw `NoSuchElementException` with no
message. Pinned by: Tokenizers ("enumeration").

**T6.** A null string throws `NullPointerException` in the constructor. Null delimiters are
accepted by the constructor; then `hasMoreTokens`, `nextToken` and `countTokens` throw
`NullPointerException`, except `countTokens` on an empty string, which returns 0.
`nextToken(null)` throws `NullPointerException` after setting the delimiters, so the next
`nextToken()` throws too. Pinned by: Tokenizers ("null string", "null delims").

**T7.** Delimiters and text are compared by code point: a surrogate pair in the delimiter string
is one delimiter that matches only that pair, a lone surrogate delimiter matches only a lone
surrogate in the text, never half of a pair, and a returned pair delimiter is one two-char token.
Pinned by: Tokenizers ("pair", "halves", "switch to pair").

**T8.** All of T1–T3 and T7 together: 3000 random strings over `ab ,;`, an emoji and a lone high
surrogate, with six delimiter sets and both `returnDelims` values, agree with a plain reading of
those rules, and `countTokens` matches. Pinned by: Tokenizers ("fuzz agree 3000 of 3000").

## 4. Not observable, or left free

- Scanner's buffer size, how much it reads ahead, and how many `read` calls it makes (only that
  it makes none after an `IOException`, SC9).
- `groupCount()` and the groups other than 0 of `match()` after a numeric reader or `nextLine`
  (the oracle exposes the groups of its internal patterns, 35 for an integer).
- The state fields of `toString` (`position`, `match valid`, `need input`, `source closed`,
  `skipped`) once the scanner has read anything; only the fresh form is pinned (SC23).
- What a scanner does after `useDelimiter((Pattern) null)` when it next looks for a token.
- One oracle defect: over a `Readable` or `InputStream` source, when the input ends in two or more
  delimiters, `hasNext()` can be false for the last empty token that a `String` source reports
  (and `next()` then still returns it). LazyVM should give the `String` source's answer; the
  chunked fuzz avoids such inputs.
- The time zone and the exact date of the `store` date line (P11 fixes its shape only).
- Scanner and Properties under locales other than ROOT and US; the default locale.
- `Properties.storeToXML`, `loadFromXML` (they need an XML parser and writer): out of scope for
  S5-04. Provide them throwing `UnsupportedOperationException` or leave them out until an XML
  stage; no test uses them.

## 5. API to provide

Members each class declares itself, from the Java SE 8 API; parameter types erased; generics,
return types and exceptions from the Javadoc. `new(…)` is a constructor.

| Class | Kind | Members |
|---|---|---|
| `Scanner` | final class implements Iterator, Closeable | new(Readable); new(InputStream); new(InputStream, String); new(File); new(File, String); new(Path); new(Path, String); new(String); new(ReadableByteChannel); new(ReadableByteChannel, String); close(); delimiter(); findInLine(String); findInLine(Pattern); findWithinHorizon(String, int); findWithinHorizon(Pattern, int); hasNext(); hasNext(String); hasNext(Pattern); hasNextBoolean(); hasNextByte(); hasNextByte(int); hasNextDouble(); hasNextFloat(); hasNextInt(); hasNextInt(int); hasNextLine(); hasNextLong(); hasNextLong(int); hasNextShort(); hasNextShort(int); ioException(); locale(); match(); next(); next(String); next(Pattern); nextBoolean(); nextByte(); nextByte(int); nextDouble(); nextFloat(); nextInt(); nextInt(int); nextLine(); nextLong(); nextLong(int); nextShort(); nextShort(int); radix(); remove(); reset(); skip(String); skip(Pattern); toString(); useDelimiter(String); useDelimiter(Pattern); useLocale(Locale); useRadix(int). Deferred until `java.math`: hasNextBigDecimal(); hasNextBigInteger(); hasNextBigInteger(int); nextBigDecimal(); nextBigInteger(); nextBigInteger(int). The `File`, `Path` and channel constructors come with `java.io.File`, `java.nio.file` and `java.nio.channels` (S5-05/S5-06); no test here uses them. |
| `InputMismatchException` | class extends NoSuchElementException | new(); new(String) |
| `Properties` | class extends Hashtable | protected field defaults; new(); new(Properties); getProperty(String); getProperty(String, String); list(PrintStream); list(PrintWriter); load(Reader); load(InputStream); loadFromXML(InputStream); propertyNames(); save(OutputStream, String) (deprecated: store that swallows IOException); setProperty(String, String); store(Writer, String); store(OutputStream, String); storeToXML(OutputStream, String); storeToXML(OutputStream, String, String); stringPropertyNames() |
| `StringTokenizer` | class implements Enumeration | new(String, String, boolean); new(String, String); new(String); countTokens(); hasMoreElements(); hasMoreTokens(); nextElement(); nextToken(); nextToken(String) |
