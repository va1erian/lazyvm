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
| `CharClass`, `Props`, `Category`, `Blocks`, `UnicodeData` | sets of code points and the `\p{..}` names |
| `LookbehindReach` | how far back a lookbehind may look, and which bodies compile |
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
  (`[^a-c&&b-d]` is "not a-c, and b-d"); an empty side of `&&` is ignored
  (`RegexClasses`).
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
- After a failed attempt that matched some repeat iteration, `\G` and the next
  `find()` start at the end of that iteration (`RegexRegions`).
- Only `^` or `\A` written directly at the start of a pattern lets a failed
  search skip the other positions; inside any group it does not (`RegexRegions`).

Oracle oddities not copied: `(?<=\x{1F600})A` failing on a text of the emoji
followed by `A`, and a lone `\uDE00` never matching inside a pair. Here a
lookbehind over a supplementary character and a literal half of a pair behave
as code point semantics suggest.

## Lookbehind reach

The oracle accepts some unbounded lookbehind bodies and rejects others, and an
accepted one can silently look back too little. `LookbehindReach` reproduces
that with the rules below, fitted to probes (`RegexLookbehind` lists about 150
bodies with the oracle's verdicts and match counts).

| Rule | Examples |
|---|---|
| a char counts 1, a class repeated n times n, an unbounded repeat 2147483647; counts add up in plain int arithmetic | `a+b` ok |
| `*`, `+`, `{n}`, `{n,m}`, `{n,}` are rejected when their count overflows the total so far | `a+b+`, `ab*`, `_a+` rejected; `a*a`, `a+aa`, `_*a` ok |
| a plain or lazy `?` never is | `a+_?` ok, `a+_{0,1}` rejected |
| an alternation contributes its longest branch, counted from -1 | `a+b\|c+d` ok, never matches |
| after an alternation, and after a group repeated by plain or lazy `?`, the total starts again from zero, up to the end of the enclosing branch or repeated group | `(?:a\|b)a+`, `(?:ab)?a+` ok; `(?:ab)?+a+`, `ab?a+` rejected |
| a repeat of a group is rejected when the group has alternatives or a variable part | `(?:a\|b)+`, `(?:a\|b){2}`, `(?:a?b)+` rejected; `(?:a\|b)?`, `(?>a\|b)+` ok |
| a back reference is rejected | `(a)\1` |
| the matcher looks back from the shortest body to the reach, in int arithmetic | a reach that wrapped to a small negative number finds nothing: `(?<!_*\p{InBasicLatin}.+)` is true everywhere |

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

## Cost and limits

The default frame limit is 4096 Java frames. Matching is a walk through the
node chain, so a pattern of more than about 4000 non-literal elements (not
iterations) overflows, and so does a loop that recurses:

- Loops over one character class (`.*`, `\w+`, `[^,]*?`), and the same inside
  one capturing group (`(\d)+`, `(a|b)*`; alternatives of single chars become one
  class), run in a counting loop with no stack per iteration.
- Loops over a *flat* body (literals, classes, repeats of a class, zero-width
  anchors, alternatives of such sequences, optionally in one capturing group:
  `(\w+\s?)*`, `(?:ab|c)*`, `(?:ab)*+`) run in `FlatLoop`, which keeps the
  backtracking state in an int array: no stack, about 40 bytes per iteration.
- Any other loop (a body with a nested group, lookaround or back reference, e.g.
  `(?:(a)b)*`) recurses, about four frames per iteration: roughly 1000 iterations.
- Group nesting is parsed with an explicit stack: 1300 levels of `(?:`, `(`,
  `(?=` or `(?>` compile (the oracle manages 800 to 1150). Nested character
  classes recurse, about 4000 levels.
- A class or alternation of ten thousand single characters is one sorted range
  table; ranges are merged, so lookups are a binary search.
- Backtracking is exponential on patterns such as `(a+)+b` against `aaaa..c`,
  and on `(a|aa)+$`. Possessive quantifiers and atomic groups avoid it; nothing
  is memoized. The oracle shows the same blow-up.
