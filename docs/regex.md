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
| `Node`, `Atoms`, `Anchor`, `GroupNodes`, `Repeat`, `LoopTail`, `FlatLoop` | the node tree the parser builds and `link` wires into a chain |
| `Backtrack` | the backtracking stack of a match, on the heap, and the loop that runs the nodes |
| `CharClass`, `CaseFold`, `Props`, `Category`, `Blocks`, `UnicodeData` | sets of code points, their case-insensitive forms, and the `\p{..}` names |
| `LookbehindReach` | how far back a lookbehind may look, and which bodies compile |
| `LoopMemo` | the failed loop states a long search remembers, and which loops may |
| `SearchHints`, `StartIndex` | shortcuts for the start-position loop of a search and for a lookbehind over a long way back |
| `Replacement` | `$n`, `${name}` and `\` expansion for `appendReplacement` |

A match is a walk down `next` pointers, run by `Backtrack` in one loop: a
node's `go` consumes input and names the node to run next, or fails. A node
that has another way to match, or state to undo, first pushes a frame (its
owner and up to seven ints) on a stack kept in arrays on the heap; a failure
pops frames and calls each owner's `back`, newest first, which is the order in
which a recursive matcher returns through the same nodes, so every flag,
capture and event changes in the same order. No Java frame is used per node or
per iteration. A lookaround or an atomic group opens a scope; when its body
matches, the frames above the scope are dropped without being undone, which is
why captures made inside a lookaround body or an atomic group, and by a
possessive repeat of one char, are not undone when the match later backs out:
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
  match the low half of a pair, in both engines (`RegexSurrogates`). The step
reads the pair even when the end of a region splits it, so a start at the
high half just before the end is followed by the end, not by the end minus one
(`RegexRegionPairs`).
- `\p{L1}` and `\p{IsL1}` are U+0000 to U+00FF, whatever
  `UNICODE_CHARACTER_CLASS` says, like `ASCII`.
- `\p{IsAlnum}`, `IsGraph`, `IsPrint`, `IsBlank` and `IsDigit` are the Unicode
  sets and the other `Is` + POSIX names (`IsLower`, `IsAlpha`, ...) stay ASCII,
  whatever `UNICODE_CHARACTER_CLASS` says; `\p{C}` leaves out unassigned code
  points; `LD`, `all` and `IsWord` exist; `Is` + `java*` names work.
  `gc=`, `general_category=` and `GC=` take POSIX (always ASCII), `java*` and
  category names, case-sensitively; under `UNICODE_CHARACTER_CLASS` a bare
  POSIX name is matched ignoring case (`\p{alpha}`, not `ascii`) (`RegexNames`).
- A region that ends between the halves of a surrogate pair has the pair read
  whole, and `$`, `\Z` and `(?m)$` (not `\z`) hold right after it when the bounds
  anchor (`RegexPairEnd`).
- A literal run (two or more literal chars, `ab\ud83d`) that ends with a high
  surrogate does not match when the text goes on with a low surrogate, even past
  the end of a region; a single `\ud83d`, a class and a run that goes on do
  (`RegexLookbehindWide`).
- With case folding, a literal that is part of a run of literals reads one
  char at a time, so at a region end that splits a pair it does not read the
  pair; a single folded literal, or one after a non-literal, reads whole pairs
  like a class (`RegexRegionPairs`).
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
  backing off (`RegexAfterFailure`, which with `RegexAfterFailureSweep` also
  hashes a sweep over every pair of 26 fragments): the end of a run of a repeated class (`a*`, `\d+`, not a
  run of alternatives such as `(?:a|b)*`), the end of an iteration of a loop
  over a fixed-width body (`(?:ab)*`, not one written `?`, nor one over
  alternatives or a variable body, whose inner runs still count), and, for a
  written `(?>..)` or a lookahead (positive or negative) whose body matched, the
  end of the body in place of what happened inside it. Possessive loops record
  every iteration.
- Only `^` or `\A` written directly at the start of a pattern lets a failed
  search skip the other positions; inside any group it does not (`RegexRegions`).

A lookbehind looks back in one of two ways, as the oracle does
(`RegexLookbehindPairs`, `RegexLookbehindWide`, `RegexLookbehindEdges`):

- When the pattern text has a surrogate char written raw (a pair, or a lone
  half) at or after the start of the lookbehind, the starts are found by
  stepping back whole code points over the text. The first start tried is the
  one `min` code points back (the start of the text, if fewer lie behind; a
  start before the start of an opaque region is not tried, so it sets neither a
  group nor `hitEnd`) and the others follow one code point at a time up to the
  reach. A start never falls between the halves
  of a pair, so `(?<=😀)A`, `(?<=\x{1F600}y)A`, `(?<=.)A` and `(?<=[^😀])A` all
  see an emoji as one step, and a lone low surrogate `\uDC00` is not found
  inside a pair. A raw surrogate written before the lookbehind does not count:
  `😀|(?<=\x{1F600})A` is the second kind. A reach that wrapped to a negative
  -k (see "Lookbehind reach") is not a number of steps but a bound in chars: a
  start may lie as many chars before the position as k code points take after
  it, the walk forward stopping at the end of the text (not the region). A pair
  after the position therefore lets the search go one char further back, and at
  the end of the text, or for `Integer.MIN_VALUE` (whose negation overflows),
  only the position itself is left: `(?<=c(y)?b*)x|😀` matches `cx` and not
  `cbx`, `(?<=a*b?😀)x` finds nothing in `ab😀x`, and `(?<=b*\S)x|😀` never
  matches (`RegexLookbehindEdges`, `wrappedFloor`).
- Otherwise the starts are found by stepping back chars, from `min` down to
  `i - reach` computed in `int` arithmetic, clamped to the region start. The reach
  counts a character above U+FFFF as one, so `(?<=\x{1F600})A` and
  `(?<=\x{1F600}y)A` fail on an emoji followed by `A` or `yA`.

The reach counts the same in both (a class or a character one, a literal run
its characters, a repeat that many times), and so does the overflow check:
`(?<=(?:😀a){715827883})A` is accepted. When the region starts inside a pair
the step still reads the pair and then has no start left.

The reviewer's fuzz (`RxFuzz`, 1500 cases for each of ten seeds, plain and
supplementary: 30000 cases) differs from the oracle as follows. Of the 15000
supplementary cases, 7 differ by `hitEnd`/`requireEnd` only and 1 more by the
capture quirk of a group inside a group repeated by `*` (Known gaps). Of the
15000 plain ones, 16 differ by `hitEnd`/`requireEnd` only and 17 more, every one
of which has a `\G` that reads the end of the previous match after a failed
attempt (Known gaps). The engine differed from the oracle in each of these cases
before the last round of lookbehind fixes as well.

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
| after an alternation, and after a group repeated by plain or lazy `?`, the total starts again from zero, up to the end of the enclosing branch or repeated group, or of an atomic group around it | `(?:a\|b)a+`, `(?:ab)?a+`, `(?>(?:b\|c)d+)b+` ok; `(?:ab)?+a+`, `ab?a+`, `(?:(?:b\|c)d+)(?>b+)?` rejected |
| an atomic group, and `?`, `??` or `?+` on an atom that is not a `(..)` or `(?:..)` group (an atomic group, `\R`), count as the atom written alone: the repeats inside are checked against the total before it | `(?>b+)?`, `x*\S(?>b+)?`, `a+(?>x\s*)?`, `a(?>b+\|c)?`, `a+\R?`, `(?>\s*x)?+` ok; `a(?>b+)?`, `a+(?>\s*x)?`, `(?>b+\|c)?(?>\s*x)?`, `a+(?>b{2147483640})?+` rejected |
| a repeat of a group is rejected when the group has alternatives or a variable part; a lookaround in the group takes no width and is not a variable part | `(?:a\|b)+`, `(?:a\|b){2}`, `(?:a?b)+` rejected; `(?:a\|b)?`, `(?>a\|b)+`, `(?:(?=b)b){2}` ok |
| a group of n chars repeated without an upper bound reaches n * 2147483647 in int arithmetic: an odd n gives a positive reach (accepted), an even n a negative one (rejected) | `(?:abc)+`, `(?:abcde)+` ok; `(?:ab)+`, `(?:abcd)+`, `(?:ab){2,}` rejected |
| a back reference is rejected | `(a)\1` |
| the matcher looks back from the shortest body to the reach, in int arithmetic | in char mode a reach that wrapped to a small negative number finds nothing (code-point mode walks its floor forward, above): `(?<!_*\p{InBasicLatin}.+)` is true everywhere, `(?<=_*\p{InBasicLatin}.+)` never |

The atomic and `?` rows were checked against about 90000 probes on the
oracle (prefixes whose total has already wrapped, alternation inside and around
the group, nested `(?>(?>..)?)?`, all three kinds of `?`): every verdict agrees.

Known differences: a `(..)` or `(?:..)` group (including `(?i:..)`) repeated by
`?` whose own reach wrapped negative adds that negative reach here, where the
oracle behaves as if it added nothing, so `(?<=a(?:b+c)?)y` finds `y` in `abcy`
here and not on the oracle, and `(?<=x*\S(?:b+c)?)y` finds it in `abcxy` only on
the oracle; the verdicts agree. A possessive `?+` over such a group with an
unbounded body, and a few deeper nestings of these rules, can also differ: for
example `(?<=x*(?:c|b+)?+)` and `(?<=a(?>(?:c|b+)?+)?)` are rejected here and
accepted by the oracle.

## Errors

`PatternSyntaxException` messages and indexes are compared against the oracle:
the description, `near index N`, the pattern and the caret line, with the line
separator from `System.lineSeparator()`. The parser keeps a cursor that can run
one past the end and reports `cursor - 1`, in code points; that rule reproduces
the oracle's indexes. `RegexErrors` covers many cases; patterns that mix
`\Q..\E` with errors after them report indexes in the quote-expanded pattern,
which is what the oracle's indexes show (a leading digit in a quote becomes a
hex escape so it cannot extend an earlier escape).

### Long inputs

Two shortcuts keep the start-position loop of `find()` from being quadratic
(`SearchHints`), neither visible in any result, flag or after-failure end:

- a pattern that must begin with one class or literal skips the starts that
  cannot begin it;
- a pattern that begins with an unbounded repeat of one class (no group) skips
  the rest of a run after a failed attempt: the attempt has already tried the
  rest of the pattern at every position of the run, and the same rest cannot
  succeed from a later start inside it. The rest must answer from the position
  alone, so no capture in the pattern may outlive a failed path, and unless it
  is only literals, classes, line breaks and anchors the repeat must be one
  whose runs count as events. The last start the search would try inside the
  run, and the end of the run, are still tried: they leave the same end of the
  previous match after a failure as all the skipped starts would
  (`RegexRuns`, which reads it with `\G`). A run holding a surrogate pair is
  not skipped. This keeps `\s*,\s*` linear over long runs of spaces.

A repeat of one class (`[ ]*`, `.{2,}`) does not hand on to the rest of the
pattern at a count after which the rest cannot begin (its first node is a class
or a literal, possibly inside groups): that try would fail at once and without
any effect, so backing off over a run costs one class test per char. A
possessive repeat that captures always tries, because its capture outlives the
failure.

A lookbehind over an unbounded body that must begin with one class only tries
the starts that can begin it, found by jumping through a bit set of the text
(`StartIndex`), built once the way back is longer than 2048. The index is per
matcher. `reset(input)` drops it, and so does `reset()` when the text is not a
`String` (a `StringBuilder` can change); over a `String`, `find(int)`, `region`
and `replaceAll` keep it, which `RegexLimitsSizes` times (`RegexIndexReset`,
`RegexIndexPossessive` and `RegexIndexStrings` cover the dropped case).

Measured with the oracle on the same machine (wall time including the start of
the VM; the oracle's run is the second figure):

| Input | before | after | oracle |
|---|---|---|---|
| `\s+$` on 20000 spaces and `x` | over 300 s | 0.16 s | 0.7 s |
| `.*foo` on 30000 `x` | over 300 s | 0.2 s | 1.3 s |
| `x*y` on 4000 `x` | 21.6 s | 0.08 s | 0.12 s |
| `(?:(?<=(a)+)a|aa)*b` after 21000 `z` (ten runs) | over 300 s | 20 s | 13.6 s |

Measured for S5-09 (the time of the search alone, in the same program; "before"
is the recursive engine):

| Input | before | after | oracle |
|---|---|---|---|
| `[ ]*,[ ]*` on `y` and 1000 spaces | 1.26 s | under 0.01 s | 3 ms |
| `[ ]*,[ ]*` on `y` and 5000 spaces | about 40 s | 0.02 s | 0.03 s |
| `\s*,\s*` on `y` and 100000 spaces | (quadratic) | 0.39 s | 5.2 s |
| `(?:a|a*)*b(?=c)` on 14 `a` | 11.7 s | 0.25 s | 0.06 s |
| `(?:a|a*)*b(?=c)` on 20 `a` | (about an hour) | 0.25 s | 7.5 s |
| `(?:a|a*)*b(?=c)` on 30 `a` | | 0.25 s | over 120 s |
| `(?:(a)b)*` on 100000 `ab` | `StackOverflowError` | 0.45 s | 2 ms |
| `(?:(a)b)*` on 340000 `ab` | `StackOverflowError` | 1.6 s | |
| `(?:(a)b)*` on 360000 `ab` (past the frame cap) | `StackOverflowError` | `StackOverflowError` after 1.6 s | |
| `(?:a\|aa){0,30}b` on 80000 `a` | 26 s | 31 s | |
| `(?:x\|xx)*y` on 2500000 `x` (`-Xmx1g`) | 33 s | 40 s | |
| `(?:(?:ab)+c)*` on 30000 `ababc` | `StackOverflowError` | 0.35 s | `StackOverflowError` at 4000 |
| `..{2,}ab` on 2000 `z` | 5.2 s | 4.1 s | 13 ms |

The oracle is exponential on `(?:a|a*)*b(?=c)` too (0.23 s on 16 `a`, 7.5 s on
20, 50 s on 22), so `RegexMemoAround` times 20 `a` rather than 30.

## Known gaps

- **Unicode data.** General categories use `Character.getType`. lazyrt has no
  `UnicodeBlock` or `UnicodeScript`, so `UnicodeData` holds every block and
  script, with the spellings the oracle accepts for each. `\p{InSurrogatesArea}`
  is rejected by the oracle too; `InSurrogates_Area` is the accepted spelling.
- **CANON_EQ** is accepted and ignored.
- Search does not look for a literal prefix; every start position is tried that
  the shortcuts above do not rule out.
- A lookbehind with a bounded window and a body that backs off (`x.{0,500}`)
  costs the length of the window for each start tried, in an interpreter: on
  20000 chars `(?<=x.{0,500})Z` is about 600 times slower than the oracle's
  compiled code. Only the starts that can begin the body are tried (through
  `StartIndex` for windows over 2048), but each of those is a full match of the
  body.
- `hitEnd` and `requireEnd` were fitted to the oracle (`RegexRegions` and
  randomized runs). A failed search stops where fewer than the pattern's true
  minimum length remains; the oracle's own bound differs for a few shapes (a
  `*`/`+` loop over a group with a variable body) and then reports `hitEnd` and
  `requireEnd` differently after a failed `find()`.
- Back references do not set `hitEnd` in `matches()` the way the oracle does.
- `hitEnd` on the empty input for `(?:(?<=\xe5|\x{10400}Y)(..|..)(..))*+`, a possessive
  loop whose body begins with a failing lookbehind: the oracle reports true, here
  false. A literal supplementary character at the end of a region that splits a
  pair is read whole by the oracle in more shapes than here (fuzz seed 1021).
- The end of the previous match after a failed search over a pattern with a
  supplementary literal (`a*\x{1F600}\x{1F600}`) differs on the oracle.
- The end of the previous match that `\G` reads after a failed attempt also
  differs for some patterns that nest possessive or lazy loops and lookarounds
  (17 of the 15000 plain fuzz cases, such as `\b(?>\p{javaLowerCase})\G` with
  `UNICODE_CASE` on `fooA`, which `lookingAt()` matches after a failed `matches()`
  on the oracle and not here).
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
- A capturing group inside a group repeated by `*` keeps, on the oracle, the
  capture of an iteration that was backed out: `((([^a]))*b)` on `b` reports
  group 3 at 0:1 there and unset here (the repeat of group 2 matches `b`, `b`
  then fails, and the iteration is given up). This is a different case from the
  outer-loop capture above.
- Under `COMMENTS`, the escapes that read the next char (`\c`, `\k<`, `\x{`,
  `\p{`) do not skip white space and comments the way the oracle does, so
  error positions and messages differ for patterns such as `\p{ L}`.
- `(?<😀a*` reports a different error first than the oracle (the group-name
  check comes before the end of the pattern is noticed here).
- **Depth.** Matching takes no Java frame per node or per iteration
  (`Backtrack`). The stack is capped at 1048576 frames (32 MB of ints), and a
  match that needs more throws `StackOverflowError`, as the oracle does when
  its own stack runs out, within seconds and well before the heap would run
  out (the alternative, filling the default heap, took about 9 s and left an
  `OutOfMemoryError`). That is about 349000 iterations of `(?:(a)b)*` (three
  frames: the loop's decision and the two halves of the group) and 1048000 of
  `(a)\1*` or `(?:(?=a)a)*`. A matcher holds no stack or memo between searches:
  both are made when a search starts and dropped when it ends, also when the
  match throws (`RegexMatcherMemory` runs 101000 live matchers and a matcher
  whose deep match threw in 48 MB).
  `RegexDepth` runs 100000 iterations of loops over a group, a back reference
  and a lookaround; the oracle at its default stack manages those shapes, but
  throws `StackOverflowError` on others that run here, measured in a fresh JVM
  each: `(?:(?:ab)+c)*` on 4000 repetitions, `(?:(a)|b)*` on 1400 `a`s and
  `(?:(?<=a)b|a)*` on 100000 `ab`s (the oracle's limit depends on what ran
  before in the same JVM). Compiling still recurses over the nesting of groups:
  a pattern nested too deeply to compile raises `PatternSyntaxException`
  ("Stack overflow during pattern compilation"), as on the oracle, with the
  index of the `(` of the deepest group (the oracle's index depends on its
  stack); the depth where that happens differs (see Cost and limits).
- **Exponential blow-up.** A back reference, `\G`, or a capture that outlives a
  failed path (a capturing group inside a lookaround or an atomic group, which
  includes a possessive repeat of a group, or a capturing possessive repeat of
  one char) turns `LoopMemo` off, and a pattern that backtracks exponentially
  then does so as on the oracle, only slower. Lookarounds, atomic groups and
  possessive repeats without such a capture no longer turn it off (S5-09):
  `(?:a|a*)*b(?=c)` on 14 `a` took 12 s and takes 0.25 s, the same on 30 `a`
  (the table under Long inputs).
- **Speed of the stack.** Running the nodes from a heap stack costs more
  bytecodes than the recursive walk did: about 1.7 to 1.9 times as many on
  patterns that backtrack a lot through groups, alternatives and lookarounds,
  which take 1.4 to 1.6 times as long (`RegexMemo`, and `RegexMemoLoops`, split
  into `RegexMemoStarts` to stay under the CI limit). Short matches pay about
  20% too: 200000 fresh `matches()` of `a?b?c` take 2.07 s against 1.73 s
  before, `split` and `replaceAll` over 500 KB of text 17.0 s against 14.4 s,
  and the long memo searches in the table above 18 to 20% more. Searches over
  long runs of a class run fewer than before, through the shortcuts above and
  the char fast paths of `Atoms.One` and `Repeat.widthAt`.
- **Names under another default locale.** Property, block and script names are
  folded through `Blocks.foldName` with `Locale.ROOT`, never the default
  locale, which matches the oracle under every locale: a dotless `ı`, a long
  `ſ` and the Kelvin sign still fold to ASCII letters (`RegexNamesLocale` runs
  under a Turkish default).

## Cost and limits

Matching uses no Java frames per node (`Backtrack`), so neither the length of
a pattern nor the number of iterations is bounded by the VM's frame limit
(`Options::max_frames`); the stack's own cap of 1048576 frames is:

- Loops over one character class (`.*`, `\w+`, `[^,]*?`), and the same inside
  one capturing group (`(\d)+`, `(a|b)*`; alternatives of single chars become one
  class), run in a counting loop with one frame for the whole run.
- Loops over a *flat* body (literals, classes, repeats of a class, `\R`,
  zero-width anchors, alternatives of such sequences, optionally in one
  capturing group: `(\w+\s?)*`, `(?:ab|c)*`, `(?:ab)*+`, `\R+`) run in `FlatLoop`, which keeps the
  backtracking state in an int array: about 40 bytes per iteration.
- Any other loop (a body with a nested group, lookaround or back reference, e.g.
  `(?:(a)b)*`) pushes a frame of 36 bytes for each decision and each node with
  state: about 120 bytes per iteration of `(?:(a)b)*`, up to the frame cap.
- Group nesting is parsed with an explicit stack (the reviewer's `Deep8` probe,
  at 300, 1000, 2000, 5000, 20000 and 200000 levels): here 5000 levels of any
  group kind compile, `(`, `(?=`, `(?!`, `(?<=` and `(?<!` fail at 20000, and
  `(?:`, `(?>`, `(?i:` and `(?:x|` compile at 200000. The oracle at its default
  stack size (no `-Xss`) fails somewhere between 1000 and 5000 levels for every
  group kind; the exact level depends on what already ran in the same JVM (separate
  runs disagreed for `(?:`, `(?<!`, `(?>`, `(?!` and `(?<=`), so no finer figure
  is given. Inside a lookbehind body, quantified nesting such as `(?:..)?` or
  `(?>..)?` fails earlier here than outside one (`(?>..)?` compiles at 2000 levels
  and fails at 5000); unquantified nesting does not. Nested character
  classes recurse: here and on the oracle, 5000 levels compile and 20000 fail.
- A class or alternation of ten thousand single characters is one sorted range
  table; ranges are merged, so lookups are a binary search.
- Backtracking is exponential on patterns such as `(a+)+b` against `aaaa..c`.
  Possessive quantifiers and atomic groups avoid it, and so does `LoopMemo`:
  once a search has taken 20000 loop steps it remembers, per loop, the
  (iteration count, position) states that failed and does not enter them again.
  The memo is a whitelist: it is on only for a pattern made entirely of
  literals, classes, line breaks, groups, alternation, loops, lookarounds,
  atomic groups, possessive repeats and anchors other than `\G`, with no
  capture that outlives a failed path; one back reference, `\G` or such a
  capture turns it off for the whole pattern. A failed state also remembers
  the last event its paths recorded, and skipping it records that event again,
  so even where a failed search leaves the end of the previous match (which the
  next `find()` and `\G` start from) is unchanged: with the memo switched on
  from the first step and with it never on, 27500 fuzz cases give the same
  output. Before S5-09 a skipped state could move that end, so a `find()` after
  a failed `matches()` could find what the oracle does not. Failures are bits,
  at most 67108864 states per loop (counts times positions; beyond that a loop
  remembers nothing), as before S5-09; the events sit beside them in a hash
  table of at most 524288 states, and a state that fails once the table is
  full is remembered without its event, so only then can a skip move that end
  as before.
  It applies to loops that sit inside no other loop, lookaround or atomic
  group, in patterns without back references anywhere (also after the loop)
  and without
  a capturing group inside a lookaround or atomic group or a possessive repeat
  of a group (those keep their capture when a path fails, so which paths were
  tried would show in the groups). The class comment of `LoopMemo` lists what
  the outcome of a state depends on, and `RegexMemo` pins each guard with
  searches long enough to switch the memo on (a run of 21000 failing starts
  first is the cheap way), and `RegexMemoAround` does the same for lookarounds,
  atomic groups and possessive repeats.
  Nested loops such as `((a+)+)+b` stay exponential in their inner loop.
  `(?:x+x+)+y` on 22 `x` takes 0.2 s and `(.*?,){11}P` on `1,` twenty times
  0.3 s here (45 s before); `(?:\w+\s?)*$` on 2000 words and a `!` finishes in
  0.5 s here, where the oracle throws `StackOverflowError`.
