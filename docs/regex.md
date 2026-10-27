# java.util.regex in LazyVM

`java/lazyrt/src/java/util/regex` is a clean-room backtracking engine written
from the Java SE 8 `Pattern` and `Matcher` Javadoc, with every behaviour the
Javadoc leaves open chosen by running the same program on the Temurin 8 oracle
(`tests/java/util/Regex*`, plus randomized differential runs that are not
checked in). The API is exactly Java 8's; `splitAsStream` is missing until
streams exist (S11).

## Layout

| File | Role |
|---|---|
| `Pattern`, `Matcher`, `MatchResult`, `PatternSyntaxException` | the public API |
| `PatternReader`, `ClassReader`, `GroupReader`, `Parser` | one inheritance chain: cursor and escapes, character classes, groups, the rest of the grammar |
| `Node`, `Atoms`, `Anchor`, `GroupNodes`, `Repeat` | the node tree the parser builds and `link` wires into a chain |
| `CharClass`, `Props`, `Category`, `Blocks`, `UnicodeData` | sets of code points and the `\p{..}` names |
| `Lengths` | the length analysis whose results are observable (see below) |
| `Replacement` | `$n`, `${name}` and `\` expansion for `appendReplacement` |

A match is a walk down `next` pointers: a node consumes input and calls
`next.match`; false means backtrack. Nodes undo their own state when they
return false. Captures made inside a lookaround body or an atomic group are not
undone when the match later backs out: the oracle leaves them set (`RegexGroups`,
last block).

## Syntax

Everything in the Java 8 syntax table is supported: literals and all escapes
(`\t \n \r \f \a \e \0n \xhh \x{h..} \uhhhh \cX \Q..\E`), character classes with
union, `&&` intersection and negation, `. \d \D \s \S \w \W \h \H \v \V \R`,
POSIX and `java*` classes, categories, `\p{IsAlphabetic}`-style binary
properties, blocks and scripts (see gaps), boundaries (`^ $ \b \B \A \G \Z \z`),
greedy, reluctant and possessive quantifiers, capturing, named, non-capturing,
atomic and lookaround groups, back references (`\n`, `\k<name>`), inline flags
(`(?idmsuxU-idmsuxU)` and `(?flags:x)`), and all flags except as noted.

Quirks reproduced on purpose, each seen on the oracle and covered by a test
(`RegexClasses`, `RegexQuantifiers`, `RegexErrors`, `RegexGroups`): a leading `^` in a class negates only the items
written directly in it (`[^a-c&&b-d]` is "not a-c, and b-d"); an empty side of
`&&` is ignored; `{` where an atom is expected starts a repeat of nothing (`{1}`
compiles); lookbehind bodies of unbounded length are accepted unless adding the
loop to the length before it overflows 32 bits (`(?<=a*)` yes, `(?<=ab*)` no);
`\R` is atomic for CRLF; `(X)*` with a fixed zero-width `X` never sets the
group; a zero-width iteration ends a loop even below its minimum.

## Errors

`PatternSyntaxException` messages and indexes are compared against the oracle:
the description, `near index N`, the pattern and the caret line, with the line
separator from `System.lineSeparator()`. The parser keeps a cursor that can run
one past the end and reports `cursor - 1`; that rule reproduces the oracle's
indexes.
`RegexErrors` and the randomized runs cover the common and many odd cases;
errors in patterns that mix `\Q..\E` with errors after them report indexes in
the quote-expanded pattern, which is what the oracle's indexes show.

## Known gaps

- **Unicode data.** General categories use `Character.getType`, so they are as
  exact as lazyrt's `Character` (S5-08). lazyrt has no `UnicodeBlock` or
  `UnicodeScript`, so `UnicodeData` holds the code point ranges of every block
  and script, dumped from those classes' behaviour on the oracle. Block names
  match by constant name, display name and display name without spaces; the
  hyphen rule is derived from the constant name and can differ for a few
  names such as the "Extension A" blocks.
- **CANON_EQ** is accepted and ignored.
- Search does not look for a literal prefix; every start position is tried.

## Cost and limits

- Loops over one character class (`.*`, `\w+`, `[^,]*?`) are iterative and use
  no stack per character.
- Loops over a group, or over anything with alternation, recurse: about four
  Java frames per iteration, so with the default 4096-frame limit a pattern
  like `(ab|c)*` handles roughly a thousand iterations before
  `StackOverflowError` (the oracle managed about three thousand in a probe). A group of single
  characters, `(a|b)*`, is folded into a class and is not affected.
- Backtracking is exponential on patterns such as `(a+)+b` against `aaaa..c`,
  and on `(a|aa)+$`. Possessive quantifiers and atomic groups avoid it; nothing
  is memoized. The oracle shows the same blow-up.
- `hitEnd` and `requireEnd` were fitted to the oracle (`RegexRegions` and
  randomized runs), including a minimum-length rule that decides where a
  failed search stops (`Lengths`). A few exotic nestings of quantified groups differ.
