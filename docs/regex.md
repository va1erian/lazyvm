# java.util.regex in LazyVM

`java/lazyrt/src/java/util/regex` is a clean-room backtracking engine written
from the Java SE 8 `Pattern` and `Matcher` Javadoc, with every behaviour the
Javadoc leaves open chosen by running the same program on the Temurin 8 oracle.
Each such behaviour is pinned by a blessed test under `tests/java/util/Regex*`,
and the code comment next to it starts with "Observed on the oracle" and names
the test. The API is exactly Java 8's; `splitAsStream` is missing until streams
exist (S11).

## Layout

| File | Role |
|---|---|
| `Pattern`, `Matcher`, `MatchResult`, `PatternSyntaxException` | the public API |
| `PatternReader`, `ClassReader`, `GroupReader`, `Parser` | one inheritance chain: cursor and escapes, character classes, group headers, and the main loop with its explicit stack of open groups |
| `Node`, `Atoms`, `Anchor`, `GroupNodes`, `Repeat`, `FlatLoop` | the node tree the parser builds and `link` wires into a chain |
| `CharClass`, `CaseFold`, `Props`, `Category`, `Blocks`, `UnicodeData` | sets of code points, their case-insensitive forms, and the `\p{..}` names |
| `LookbehindReach` | how far back a lookbehind may look, and which bodies compile |
| `LoopMemo` | the failed loop states a long search remembers, and which loops may |
| `Replacement` | `$n`, `${name}` and `\` expansion for `appendReplacement` |

A match is a walk down `next` pointers: a node consumes input and calls
`next.match`; false means backtrack. Nodes undo their own state when they
return false. Captures made inside a lookaround body or an atomic group, and by
a possessive repeat of one char, are not undone when the match later backs out:
the oracle leaves them set (`RegexGroups`, last block).

`UnicodeData.java` is generated: `python tools/regex_data.py` dumps every block
and script from the oracle, and `--check` compares the committed file with a
fresh dump.

## Syntax

Everything in the Java 8 syntax table is supported: literals and all escapes
(`\t \n \r \f \a \e \0n \xhh \x{h..} \uhhhh \cX \Q..\E`), character classes with
union, `&&` intersection and negation, `. \d \D \s \S \w \W \h \H \v \V \R`,
POSIX and `java*` classes, categories, `\p{IsAlphabetic}`-style binary
properties, blocks and scripts, boundaries (`^ $ \b \B \A \G \Z \z`), greedy,
reluctant and possessive quantifiers, capturing, named, non-capturing, atomic
and lookaround groups, back references (`\n`, `\k<name>`), inline flags
(`(?idmsuxU-idmsuxU)` and `(?flags:x)`), and all flags except as noted.

Quirks reproduced on purpose, each seen on the oracle and pinned by a test:

- A leading `^` in a class negates only the items written directly in it
  (`[^a-c&&b-d]` is "not a-c, and b-d", `[^a[b]]` is "not a, or b").
- `&&` intersects everything gathered so far with its right side
  (`RegexClassAlgebra`, which also runs every class of up to four items from a
  small alphabet). Nested classes on the right are unioned, but a plain item or
  range there starts a fresh class that runs to the end of the side and
  replaces them: `[a-z&&[b]c]` is `[c]`, `[a-z&&c[b]]` is `[bc]`. An empty
  right side intersects with the last item written before it, where a run of
  plain characters below U+0100 counts as one item however it is interrupted:
  `[ab&&]` is `[ab]`, `[a[b]&&]` is `[b]`, `[a[bc]b&&]` is `[ab]`, and
  `[^a&&]` is empty (the item is the un-negated `a`). Negation carries on after
  `&&`: `[^a&&[b]&c]` is `[b]`. The same run explains `[a[b]&&&]` being
  `[ab&]` and `[^aa[^b]b]` excluding `a` again.
- Under `COMMENTS`, white space and `#` comments are skipped on both sides of
  the `-` of a class range: `[a- b]` is `a-b`, `[\r- ]` runs on to `]` and is
  unclosed (`RegexFlags`).
- A pattern with a surrogate char written raw in its text (a pair, or a lone
  half: `\x{D83D}` as an escape does not count), or with a
  negated category or block (`\P{Lu}`, `\P{InGreek}`; not `\P{all}`, scripts,
  binary properties or `[^\p{Lu}]`), skips the low half of a pair after a
  failed search attempt at the high half. An explicit start inside the pair
  (`find(1)`, a region) is not skipped. A literal `\uDC00` or `[\uDC00]` does
  match the low half of a pair, in both engines (`RegexSurrogates`).
- `\p{IsAlnum}`, `IsGraph`, `IsPrint`, `IsBlank` and `IsDigit` are the Unicode
  sets and the other `Is` + POSIX names (`IsLower`, `IsAlpha`, ...) stay ASCII,
  whatever `UNICODE_CHARACTER_CLASS` says; `\p{C}` leaves out unassigned code
  points; `LD`, `all` and `IsWord` exist; `Is` + `java*` names work.
  `gc=`, `general_category=` and `GC=` take POSIX (always ASCII), `java*` and
  category names, case-sensitively; under `UNICODE_CHARACTER_CLASS` a bare
  POSIX name is matched ignoring case (`\p{alpha}`, not `ascii`) (`RegexNames`).
- Case folding (`RegexFolding`, 13 pattern forms over a sample of 2268
  characters): with `CASE_INSENSITIVE | UNICODE_CASE` a character written alone
  (a literal, or `[c]`) matches the characters with the same
  `toLowerCase(toUpperCase(x))`, except that `ß` matches only itself (the
  capital `ẞ` matches `ß`, not the other way round) unless it is part of a
  literal string of two or more characters; a range, even `[ß-ß]`, holds x when
  x, `toUpperCase(x)` or `toLowerCase(toUpperCase(x))` is inside it, so `[ſ-ſ]`
  does not match `s`. In a class, `k`, `s`, `i`, their capitals, `µ`, `Å`, `å`
  and `ÿ` are not part of a "run of plain characters" because their case
  partners lie at or above U+0100.
- Under `COMMENTS`, a single `&` followed by white space or a comment is
  dropped, and `&` `&` with such text between them is `&&`; a `[` or `]` right
  after a dropped `&` is an ordinary character. The error index of a range
  whose plain end is smaller than its start counts the white space after it.
- `\v` straight before a `-` in a class is the one character U+000B
  (`[\v-z]` is a range, `[\v-]` holds U+000B and `-`).
- `{` where an atom is expected starts a repeat of nothing, so `{1}` compiles
  (`RegexErrors`).
- `\R` is atomic for CRLF (`RegexClasses`).
- A capturing group written directly under `*` or `{0,n}` whose body can only
  match the empty string is not recorded; `?`, a wrapped `(?:(\b))*`, and
  `+` do record it (`RegexQuantifiers`).
- A zero-width iteration ends a loop even below its minimum (`RegexQuantifiers`).
- `\b` treats a nonspacing mark as a word character only after a letter or
  digit (not after `_`); with `UNICODE_CHARACTER_CLASS` it is the Unicode `\w`
  (`RegexFlags`).
- In `COMMENTS` mode whitespace is skipped inside group headers (`( ?:a)`,
  `(?< n>a)`), but `(?  =a)` is an error (`RegexFlags`).
- A `PatternSyntaxException` index counts code points (`RegexErrors`).
- A region that ends between the halves of a surrogate pair still reads the
  whole pair (`RegexRegions`).
- After a failed attempt, `\G` and the next `find()` start where the last
  "event" of the attempt ended, in the order they happened and not moved by
  backing off (`RegexAfterFailure`, which also hashes a sweep over every pair
  of 21 loop shapes): the end of a run of a repeated class (`a*`, `\d+`, not a
  run of alternatives such as `(?:a|b)*`), the end of an iteration of a loop
  over a fixed-width body (`(?:ab)*`, not one written `?`, nor one over
  alternatives or a variable body, whose inner runs still count), and, for a
  written `(?>..)` whose body matched, the end of the body in place of what
  happened inside it. Possessive loops record every iteration.
- Only `^` or `\A` written directly at the start of a pattern lets a failed
  search skip the other positions; inside any group it does not (`RegexRegions`).

Oracle oddity not copied: `(?<=\x{1F600})A` failing on a text of the emoji
followed by `A`. Here a lookbehind over a supplementary character behaves as
code point semantics suggest.

## Lookbehind reach

The oracle accepts some unbounded lookbehind bodies and rejects others, and an
accepted one can silently look back too little. `LookbehindReach` reproduces
that with the rules below, fitted to probes (`RegexLookbehind` lists about 150
bodies with the oracle's verdicts and match counts).

| Rule | Examples |
|---|---|
| a char counts 1, a class repeated n times n, an unbounded repeat 2147483647; counts add up in plain int arithmetic, so a sum can wrap | `a+b` ok |
| `*`, `+`, `{n}`, `{n,m}`, `{n,}` are rejected when their count overflows the total so far | `a+b+`, `ab*`, `_a+` rejected; `a*a`, `a+aa`, `_*a` ok |
| a plain or lazy `?` never is | `a+_?` ok, `a+_{0,1}` rejected |
| an alternation contributes its longest branch, counted from -1 | `a+b\|c+d` ok, never matches; `a*_?\|c*_?` ok, finds nothing |
| after an alternation, and after a group repeated by plain or lazy `?`, the total starts again from zero, up to the end of the enclosing branch or repeated group | `(?:a\|b)a+`, `(?:ab)?a+` ok; `(?:ab)?+a+`, `ab?a+` rejected |
| a repeat of a group is rejected when the group has alternatives or a variable part; a lookaround in the group takes no width and is not a variable part | `(?:a\|b)+`, `(?:a\|b){2}`, `(?:a?b)+` rejected; `(?:a\|b)?`, `(?>a\|b)+`, `(?:(?=b)b){2}` ok |
| a group of n chars repeated without an upper bound reaches n * 2147483647 in int arithmetic: an odd n gives a positive reach (accepted), an even n a negative one (rejected) | `(?:abc)+`, `(?:abcde)+` ok; `(?:ab)+`, `(?:abcd)+`, `(?:ab){2,}` rejected |
| a back reference is rejected | `(a)\1` |
| the matcher looks back from the shortest body to the reach, in int arithmetic | a reach that wrapped to a small negative number finds nothing: `(?<!_*\p{InBasicLatin}.+)` is true everywhere, `(?<=_*\p{InBasicLatin}.+)` never |

Known differences: a possessive `?+` over a group with an unbounded body, and a
few deeper nestings of these rules, can differ from the oracle.

## Errors

`PatternSyntaxException` messages and indexes are compared against the oracle:
the description, `near index N`, the pattern and the caret line, with the line
separator from `System.lineSeparator()`. The parser keeps a cursor that can run
one past the end and reports `cursor - 1`, in code points; that rule reproduces
the oracle's indexes. `RegexErrors` covers many cases; patterns that mix
`\Q..\E` with errors after them report indexes in the quote-expanded pattern,
which is what the oracle's indexes show (a leading digit in a quote becomes a
hex escape so it cannot extend an earlier escape).

## Known gaps

- **Unicode data.** General categories use `Character.getType`. lazyrt has no
  `UnicodeBlock` or `UnicodeScript`, so `UnicodeData` holds every block and
  script, with the spellings the oracle accepts for each. `\p{InSurrogatesArea}`
  is rejected by the oracle too; `InSurrogates_Area` is the accepted spelling.
- **CANON_EQ** is accepted and ignored.
- Search does not look for a literal prefix; every start position is tried.
- `hitEnd` and `requireEnd` were fitted to the oracle (`RegexRegions` and
  randomized runs). A failed search stops where fewer than the pattern's true
  minimum length remains; the oracle's own bound differs for a few shapes (a
  `*`/`+` loop over a group with a variable body) and then reports `hitEnd` and
  `requireEnd` differently after a failed `find()`.
- Back references do not set `hitEnd` in `matches()` the way the oracle does.
- The shortest match that bounds a failed search counts nothing for a repeat of
  alternatives or of a variable body (`\b(?:B|C)+z` on `""` reports
  `requireEnd` on the oracle), and nothing after the first such repeat in the
  same sequence (`RegexEnds`). Other shapes of the oracle's bound can still
  differ after a failed `find()`: a pattern that starts with four or more literal
  characters is searched for its prefix by the oracle and so is also tried
  where fewer than the shortest match remains.
- A pattern with a raw surrogate char in its text compares a literal run by
  code points on the oracle, so escaped halves of a pair (`\uD83D` then a raw
  `\uDE00`) do not match a pair in the text there; here they do.
- A capturing group repeated by a greedy `+`, `*` or `{n,m}` inside an outer
  loop (`(?:(a)+b)*`) keeps, on the oracle, the capture of an earlier outer
  iteration in some cases: `(?:(a)+b)*` on `aabab` reports group 1 at index 1
  there and 3 here, and `(?:(a)*b)*` on `abab` reports 0 there and 2 here.
  One iteration, lazy and possessive repeats, and alternations of groups agree.
- A loop inside another loop, such as the inner one of `((a+)+)+b`, stays
  exponential (see Cost and limits).

## Cost and limits

The default frame limit is 4096 Java frames. Matching is a walk through the
node chain, so a pattern of more than about 4000 non-literal elements (not
iterations) overflows, and so does a loop that recurses:

- Loops over one character class (`.*`, `\w+`, `[^,]*?`), and the same inside
  one capturing group (`(\d)+`, `(a|b)*`; alternatives of single chars become one
  class), run in a counting loop with no stack per iteration.
- Loops over a *flat* body (literals, classes, repeats of a class, `\R`,
  zero-width anchors, alternatives of such sequences, optionally in one
  capturing group: `(\w+\s?)*`, `(?:ab|c)*`, `(?:ab)*+`, `\R+`) run in `FlatLoop`, which keeps the
  backtracking state in an int array: no stack, about 40 bytes per iteration.
- Any other loop (a body with a nested group, lookaround or back reference, e.g.
  `(?:(a)b)*`) recurses, about four frames per iteration: roughly 1000 iterations.
- Group nesting is parsed with an explicit stack: 1300 levels of `(?:`, `(`,
  `(?=` or `(?>` compile (the oracle manages 800 to 1150). Nested character
  classes recurse, about 4000 levels.
- A class or alternation of ten thousand single characters is one sorted range
  table; ranges are merged, so lookups are a binary search.
- Backtracking is exponential on patterns such as `(a+)+b` against `aaaa..c`.
  Possessive quantifiers and atomic groups avoid it, and so does `LoopMemo`:
  once a search has taken 20000 loop steps it remembers, per loop, the
  (iteration count, position) states that failed and does not enter them again.
  It applies to loops that sit inside no other loop and no lookaround, in
  patterns without back references anywhere (also after the loop) and without
  a capturing group inside a lookaround or atomic group or a possessive repeat
  of a group (those keep their capture when a path fails, so which paths were
  tried would show in the groups). The class comment of `LoopMemo` lists what
  the outcome of a state depends on, and `RegexMemo` pins each guard with
  searches long enough to switch the memo on (a run of 21000 failing starts
  first is the cheap way).
  Nested loops such as `((a+)+)+b` stay exponential in their inner loop.
  `(?:x+x+)+y` on 22 `x` takes 0.2 s and `(.*?,){11}P` on `1,` twenty times
  0.3 s here (45 s before); `(?:\w+\s?)*$` on 2000 words and a `!` finishes in
  0.5 s here, where the oracle throws `StackOverflowError`.
