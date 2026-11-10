# java.util.logging (minimal): behaviour beyond the Javadoc

Specification for S11-03 (`docs/plan-2.md`; the scope is decisions.md D6: `Logger`, `Level`,
`ConsoleHandler`). It lists what the Java 8 oracle (Temurin 1.8.0_504) does where the Java SE 8
Javadoc is silent or vague and a program can see it. Implement the Javadoc, then these rules, and
keep to the closed subset of section 13. How you build the classes is your choice; only what a
program can observe is fixed here.

Each rule reads *input → observable result*. "Pinned by" names the test in `tests/java/util` and
the label its line starts with (the text before the first `:`; a few labels are the first words of
a data row). Every golden is blessed on the oracle. Section 12 lists what you may choose, and
section 13 the API, the dependencies and how to re-bless.

Terms. A logger's *effective level* is its own level if it is not null, else its nearest
ancestor's non-null level, else `INFO` (a logger without a parent and without a level behaves as
`INFO`). A *registered* logger is one `LogManager` knows by name: made by `Logger.getLogger` or
added by `addLogger`. All `NullPointerException`s below have a `null` message.

## 1. Loggers and the tree

**L1.** Before the program does anything, `Logger.getLogger("")` is the root logger: name `""`,
level `INFO`, no parent, no filter, `getUseParentHandlers()` true, and exactly one handler, a
`ConsoleHandler` with level `INFO` and a `SimpleFormatter`. `LogManager.getLogManager().getLogger("")`
is the same object. Pinned by: Logging ("root name" to "root handler formatter").

**L2.** `getLogger(name)` returns the same instance for the same name; names are case-sensitive.
`getLogger(null)` throws `NullPointerException`. `getGlobal()` (a `final` method), `Logger.global` and
`getLogger("global")` are one logger named `"global"` (`GLOBAL_LOGGER_NAME`), parent root, level
null, registered from the start (X1). `getLogger(null, bundle)` throws `NullPointerException` before
the bundle is looked at. Pinned by: Logging ("global", "global same", "global parent", "global level", "same instance",
"case sensitive", "getLogger null").

**L3.** A new named logger has level null, no handlers, no filter, `getUseParentHandlers()` true.
Its parent is the nearest registered logger whose name is a proper dotted prefix (cut at the last
`.` repeatedly), else the root. When a nearer ancestor is registered later, every registered
logger whose nearest registered ancestor it now is takes it as parent, even one given another parent by
`setParent` (an unrelated logger, the root, an anonymous one); a logger that has a nearer registered
ancestor still keeps that one. A logger registered by `addLogger` after a `setParent` gets its parent by
the same rule. `setParent` replaces the parent; a logger created afterwards under a
re-parented one gets that one as its parent. Pinned by: Logging ("plain logger", "t.a.b.c parent"
to "after t", "q reparent", "setParent", "new child of re-parented", "skip levels").
Names with an empty segment (`a..b`, `.a`, `a.`) are not pinned: the oracle skips levels there.

**L4.** `getAnonymousLogger()` returns a new logger on every call: name `null`, parent root, level
null, no handlers. It is not registered (`getLogger` never finds it, `getLoggerNames` never lists
it). `setParent` works on it. Pinned by: Logging ("anonymous", "anonymous distinct", "anonymous
setParent", "anonymous inherits root").

**L5.** Argument checks. `setParent(null)`, `addHandler(null)`, `log(null, …)`, `logp(null, …)`,
`log((LogRecord) null)`, `isLoggable(null)` and `LogManager.addLogger(null)` throw
`NullPointerException`. `setLevel(null)`, `setFilter(null)` and `removeHandler(null)` are accepted
(the last does nothing). Pinned by: Logging ("nulls" rows: "setParent null", "addHandler null",
"setLevel null", "log null level", "log null record", "logp null level", "addLogger null",
"isLoggable null").

**L6.** `getHandlers()` returns a new array, in the order the handlers were added. The same handler
may be added twice and then appears twice. `removeHandler(h)` removes the first occurrence only,
and does nothing for a handler that is not present. Pinned by: Logging ("order", "copy",
"remove one", "remove absent", "remove null", "empty").

**L7.** Resource bundles do not exist in LazyVM (section 13). `getResourceBundleName()` is `null`
for every logger. `getLogger(n, null)` works. A non-null bundle name given to `getLogger(n, b)`,
`getAnonymousLogger(b)` or the protected constructor throws `MissingResourceException` with message
`Can't find ` + name + ` bundle`, `getClassName()` = the name and `getKey()` = `""`; nothing is
registered. (The oracle finds real bundles; no test can use one, since a program cannot make one
without `ResourceBundle`.) Pinned by: Logging ("bundle name", "getLogger bundle null", "getLogger
bundle missing", "anonymous bundle missing", "ctor bundle missing", "missing class and key", "not
registered after failure", "same name later").

## 2. Levels

**V1.** `Level` has nine constants: `OFF` `Integer.MAX_VALUE`, `SEVERE` 1000, `WARNING` 900, `INFO`
800, `CONFIG` 700, `FINE` 500, `FINER` 400, `FINEST` 300, `ALL` `Integer.MIN_VALUE`. For them
`getName()`, `toString()` and `getLocalizedName()` are the constant's name (LazyVM has only the
English names), `hashCode()` is `intValue()`, and `getResourceBundleName()` is the string
`sun.util.logging.resources.logging` (just a string; no such bundle is loadable). `equals(o)` is
true when `o` is a `Level` with the same `intValue()`, whatever its name or class; `null` and
other types are false. Pinned by: LoggingLevels ("constant …", "distinct", "equals", "toString is
the name", "equal by value", "custom vs info", "not equal", "hash is value").

**V2.** `Level.parse(s)`: `null` throws `NullPointerException`. Otherwise `s` is looked up exactly
(case-sensitive, no trimming) among the names of all `Level` objects created so far, the nine
constants and custom levels, the first created winning. If there is none, `s` is read as
`Integer.parseInt` reads it (optional sign, leading zeros, int range) and the first created level
with that value is returned; if there is none, a new plain `Level` is created with the name `s`
exactly as written (`"+5"` stays `"+5"`), that value, and no bundle. It is remembered: parsing the
same string again, or the value in another spelling (`"5"` after `"+5"`), returns the same object.
Anything else throws `IllegalArgumentException` with message `Bad level "` + s + `"` (so `""`,
`"info"`, `" INFO"`, `"INFO "`, `"0x10"`, `"1.5"` and `"2147483648"` fail). Pinned by:
LoggingLevels ("by name" to "bundle of numeric").

**V3.** The protected constructors `Level(name, value)` and `Level(name, value, bundleName)` accept
any value, an empty name, and a bundle name that cannot be loaded; a null name throws
`NullPointerException`. The level is registered for `parse` (V2) in creation order: a second level
with an existing name or value never replaces the first for that key but is still found by its own
other key (`"ALPHA"` 850 then `"ALPHA"` 851: `parse("ALPHA")` gives the first, `parse("851")` the
second). `getResourceBundleName()` returns what was given (null by default) and
`getLocalizedName()` the name. Pinned by: LoggingLevels ("fields", "parse custom name" to "negative
value").

**V4.** `isLoggable(l)` on a logger is true when `l.intValue()` ≥ the effective level's value and
that effective level is not `OFF`. So with effective `OFF` nothing is loggable, `OFF` included;
with `ALL` everything is, `OFF` included; `OFF` as a message level is loggable at every other
effective level. The effective level is read at the call: changing an ancestor's level or calling
`setLevel(null)` takes effect at once. Pinned by: Logging ("inherit default" to "no parent, no
level", "OFF", "ALL", "CONFIG", "750", "getLevel"), LoggingRecords ("filtered").

## 3. Publication

**P1.** `log`/`logp`/`entering`/… first test `isLoggable(level)`. If false nothing else happens:
no record is created (its sequence number is not consumed, R1), a `Supplier` is not called.
Pinned by: LoggingRecords ("sequence steps", "not called when hidden", "trace hidden at FINE").

**P2.** For a loggable call the record is created (its logger name is the logger's name), then the
logger's own filter is asked; `false` ends the call (no handler of this logger or of any parent
sees it). The filter sees the record with the logger name set. Pinned by: LoggingFlow ("leaf filter
refuses: nothing", "filter then handler").

**P3.** Then the walk, reading the tree through the public `getParent()`, `getHandlers()` and
`getUseParentHandlers()` (a subclass can override them; the level and the filter come from the
logger's own fields, so an overridden `getLevel()` or `getFilter()` is not asked): for the logger,
then its parent, then the grandparent, … call `publish` on each of the logger's handlers in the order they were added, **without** checking the handler's
level or filter (the handler decides in `publish`); after a logger's handlers, stop if its
`getUseParentHandlers()` is false, else go to the parent. A logger's own handlers always run, even
when its `useParentHandlers` is false. The levels and filters of the parents are never consulted;
a parent's filter does not gate a child's record. The handler list of a logger is a snapshot taken
when the walk reaches it: a handler added during a `publish` is first called for the next record.
Pinned by: LoggingFlow (every row up to "mid filter gates its own", "the logger calls handlers
whatever their level", "handlers: child first, then parent", "handler levels", "add during publish:
this record", "add during publish: next record").

**P4.** A handler registered on two loggers on the walk is called once per logger. An exception
thrown by `publish` propagates to the caller of `log` and ends the walk: later handlers and parents
are skipped. Pinned by: LoggingFlow ("shared handler", "handler exception").

**P5.** `log(LogRecord r)`: `r` null → `NullPointerException`; otherwise the same steps with `r`
itself (the very object reaches the handlers): level test with `r.getLevel()` (V4), then the filter,
then the walk. The logger does not touch `r`'s logger name or any other field. Pinned by:
LoggingRecords ("direct same record", "direct", "direct pre", "direct levels").

## 4. LogRecord

**R1.** `new LogRecord(level, msg)`: `level == null` → `NullPointerException`; `msg` may be null.
Logger name, bundle name, thrown and parameters start null. The millis are
`System.currentTimeMillis()` at construction. The thread id is `(int)` of the creating thread's
`Thread.getId()` (the value for `main` is not pinned). The sequence number comes from a process-wide
counter that goes up by exactly 1 for each record constructed, by anyone, on any thread (the first
value and overflow are not pinned). Pinned by: LoggingRecords ("defaults", "millis is now", "thread
id", "other thread id", "null level", "null message", "sequence steps", "logged record number",
"millis order").

**R2.** Every setter stores its argument and the getter returns it (`getParameters` returns the same
array object). `setLevel(null)` throws `NullPointerException`; every other setter accepts null.
Pinned by: LoggingRecords ("setters", "setters null", "setLevel null", "negative millis",
"params array identity").

## 5. Logger methods and source inference

**M0.** Every logging method (`log`, `logp`, `entering`, the level shortcuts, the `Supplier` forms)
makes its record after the level test and hands it to the overridable `log(LogRecord)`; a subclass
overriding that method sees all of them. Pinned by: LoggingSubclass ("log(LogRecord) override saw",
"level test happens before log(LogRecord)").

**M1.** `severe`, `warning`, `info`, `config`, `fine`, `finer`, `finest` (String or `Supplier`)
call `log` with their level. `log(level, msg)` has no parameters; `log(level, msg, Object p)` has
parameters `{p}` (so `null` gives `[null]`); `log(level, msg, Object[] ps)` stores `ps` itself
(null stays null, empty stays empty); `log(level, msg, Throwable t)` sets thrown (null allowed) and
no parameters. A null message is stored as null. `Level.OFF` and levels made by `parse` are legal
message levels. Pinned by: LoggingRecords ("level", "log", "filtered", "params array identity").

**M2.** `Supplier` overloads call `get()` exactly once, only when the call is loggable (P1), and the
result is the message (null allowed). A null supplier throws `NullPointerException` when the call
is loggable (not pinned when it is not). `log(level, Throwable t, Supplier)` sets thrown to `t`.
Pinned by: LoggingRecords ("supplier", "calls", "not called when hidden", "null supplier").

**M3.** `logp(level, cls, method, …)` sets the record's source class and method to the arguments,
nulls included, and never infers (S2). The overloads mirror M1. Pinned by: LoggingRecords ("logp",
"infer explicit").

**M4.** `entering(c, m)`: level `FINER`, message `ENTRY`, no parameters. `entering(c, m, Object o)`:
message `ENTRY {0}`, parameters `{o}` (null gives `[null]`). `entering(c, m, Object[] a)`: message
`ENTRY` followed by ` {0}`, ` {1}`, … one per element, parameters `a` itself; null array → `ENTRY`
and null parameters; empty array → `ENTRY` and `[]`. `exiting(c, m)`: `RETURN`; `exiting(c, m, r)`:
`RETURN {0}` with `{r}` (null → `[null]`). `throwing(c, m, t)`: `THROW`, thrown `t` (null allowed),
no parameters. All are `FINER` and hidden unless the effective level is `FINER` or lower. Which
overloads of `logp` (the given class and method) a subclass sees: `entering(c, m)`,
`entering(c, m, o)`, `exiting(c, m)` and `exiting(c, m, r)` call `logp` with a `String` or an `Object`
parameter even when hidden; `entering(c, m, Object[])` with a non-null array calls the `Object[]`
overload and only when loggable, and with a null array calls the plain `String` one (message `ENTRY`);
`throwing` never calls `logp`, it makes the record itself and hands it to `log(LogRecord)`. Pinned by:
LoggingRecords ("trace", "trace hidden at FINE", "trace shown at FINER"), LoggingTrace.

**S1.** *Source inference.* A record whose source was never set carries "pending inference". The
first call of `getSourceClassName()` or `getSourceMethodName()` (either one infers both) looks at
the call stack **at that moment**: find the innermost frame of the class `java.util.logging.Logger`
(LazyVM's own class of that name; its internal helper classes must not show), skip the run of
consecutive `Logger` frames after it, and also the generated glue frames after that (the class behind a
lambda or method reference, `java.lang.reflect.*`, `sun.reflect.*`: the oracle does not show the first, LazyVM
names it `...-Lambda-n` and skips it), and the next frame is the caller: its declaring class's
binary name (`util.LoggingRecords$Wrap`) and method name (`<init>` and `<clinit>` included). If the
stack has no `Logger` frame, both stay null for good. The result is kept: a second handler, or a
later call, sees the same values. Consequences, all pinned: inside `Handler.publish`, a `Filter`
or a `Formatter` run by `log`, the getters give the code that called the logger; read after `log`
returned, or in a record passed to a handler by hand, they give `null`/`null`. Frames of a *user*
subclass of `Logger` are callers: a `MyLogger.info` override calling `super.info` is the source
(`MyLogger#info`). The thread is the one running `publish`. An implementation that infers eagerly
inside `log` and keeps the answer fails "late source": capture lazily, or drop the capture when
`log` returns. Pinned by: LoggingRecords ("infer", "infer subclass", "infer thread", "infer
explicit", "late source", "infer by hand", "method first", "infer twice", "no source before
publish", and the source column of "level", "log", "supplier"), LoggingRoot (the stderr lines).

**S2.** `setSourceClassName` and `setSourceMethodName` (even with null) end the pending inference;
the field not set stays null. Pinned by: LoggingRecords ("setters null", "direct pre": source class
`Pre`, method `null`).

**S3.** Not pinned: the source of a call on the *root logger itself* (`Logger.getLogger("").info`):
the oracle shows one of its own internal classes there. LazyVM reports the caller like for any
logger. Tests use `logp` on the root.

## 6. SimpleFormatter

**F1.** `format(r)` is `String.format(f, date, source, logger, level, message, thrown)` with `f`
from F4 and these six arguments: `date`, a `Date` of `r.getMillis()` in the default time zone;
`source`, which is `r.getSourceClassName()` when that is not null, followed by a space and
`r.getSourceMethodName()` when that is not null too, and otherwise `r.getLoggerName()` (null
prints `null`, `""` prints nothing; a method without a class is ignored); `logger`,
`r.getLoggerName()`; `level`, the level's own name (`toString()`; an overridden
`getName()` or `getLocalizedName()` is not asked); `message`, `formatMessage(r)`
(null prints `null`); `thrown`, the empty string without a throwable, else a line separator
followed by what `Throwable.printStackTrace(PrintWriter)` writes (so `Suppressed:` and
`Caused by:` blocks, `Unknown Source`, `Native Method`, and a final line separator). Pinned by:
LoggingFormat ("full" to "percent in message", "no names" to "empty class and method", "thrown"
to "thrown cleared"), LoggingFormatDate (all six rows).

**F2.** Default format: the line `Nov 14, 2023 10:13:20 PM a.B go`, then a line separator, then
`INFO: hello`, then the thrown text, then a line separator: abbreviated month name, two-digit day,
`, `, four-digit year, a space, 12-hour clock **without** a leading zero for the hour, minutes and
seconds in two digits, space, `AM` or `PM` in capitals, space, the source; next line level name,
`: `, message, thrown. (That is `%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp %2$s%n%4$s: %5$s%6$s%n`.)
Midnight is `12:00:00 AM`, noon `12:00:00 PM`; instants before 1970 and in year 9999 follow the
`%t` rules. `%n` is `System.lineSeparator()`. A custom `Level` prints its name as given. Pinned by:
LoggingFormat ("raw ends with line separator", "raw line count", "times" rows by their millis value
at the start of the line: `0 ->` … `253402300799999 ->`), LoggingStream ("SimpleFormatter has no
head or tail", "default format on the console handler").

**F3.** `getHead` and `getTail` return `""` for `SimpleFormatter` and for the `Formatter` base.
Pinned by: LoggingFormat ("head and tail default", "simple head and tail", "head overridden").

**F4.** The format string is the system property `java.util.logging.SimpleFormatter.format`
(`logging.properties` is not read). It is read **once**, when `SimpleFormatter` is first used; every
instance, the root logger's handler included, uses that value; later `System.setProperty` calls
change nothing. Setting it from the program works only before the first use. If the property is
absent, or applying it to a sample of the six arguments (a `Date` and five strings; `%d` on the
date, `%7$s`, an unknown conversion) throws an `IllegalFormatException`, the default format of F2
applies. The property's own text has no `%n` added. An empty value is not pinned. Pinned by:
LoggingFormatLate ("early", "early after change", "late after change", "newest", "property
after", the stderr lines), LoggingFormatBad, LoggingFormatMissing, LoggingRoot ("property").

## 7. formatMessage (what `{0}` does)

`java.text.MessageFormat` does not exist in lazyrt (no `java.text` at all). `formatMessage` needs a
small private formatter for the `{n}` subset; rules MF1 to MF5 are the whole contract.

**MF1.** `formatMessage(r)` returns `null` for a null message, `""` for an empty one. The message
is substituted only when `r.getParameters()` is non-null and non-empty **and** the message contains
`{0`, `{1`, `{2` or `{3` as a substring; otherwise it is returned unchanged, apostrophes included
(`it's` stays, `{4}` alone stays, `{5}` alone stays, `{ 0}` stays). Pinned by: LoggingFormat ("null
message", "null message stays null", "null message with params stays null", "empty message", "no
params", "{4} alone is not substituted", "{5} alone", "space in braces", "empty params", "param no
marker", "no marker: no quote processing", "no marker with params").

**MF2.** When substitution applies, the message is read as a pattern: text is literal, `{n}` is
replaced by argument `n`, where the text between the braces is read as `Integer.parseInt` reads it
(`{+1}`, `{00}`, `{0000000000001}`, `{-0}` and non-ASCII digits work; `{ 1}`, `{1 }`, `{+}`, `{1x}`,
`{}` do not). An `n` below 10000 and ≥ the number of parameters stays in the text, written as the number it names
(`a {1} b` with one parameter; `{+1}` and `{01}` give `{1}`, `{١٢}` gives `{12}`). Indexes above 9 work (`{10}`); once substituting, `{4}` is
replaced too (`{0} {4}`). The same argument may be used any number of times, in any order.
Pinned by: LoggingFormat ("{0}", "{1} missing", "{1}", "order", "{2}", "{3}", "{4} with {0}", "{10}
with {1}", "same twice", "adjacent", "braces text", "dollar and backslash", "unicode").

**MF3.** Apostrophes quote, as in `MessageFormat`: `'` starts a quoted section that ends at the
next `'` and is copied literally without the quote characters; `''` is one `'`, also inside a
quoted section; an unterminated quote runs to the end. So `it's {0}` gives `its {0}`, `'{0}' {0}`
gives `{0} X`, `a'b{0}c'd {1}` gives `ab{0}cd Y`. Pinned by: LoggingFormat ("apostrophe starts
quote", "doubled", "quoted brace", "quote closes", "unterminated quote", "quote only", "two quotes",
"quoted quote").

**MF4.** Argument text: `null` → `null`; `Integer`, `Long`, `Short`, `Byte` → decimal with `,` as
the grouping separator every three digits (`-1,234,567`); `Double` and `Float` → the same style
with at most three fraction digits, no trailing zeros (`2.0` gives `2`, `1.5` gives `1.5`), rounded
half-even on the exact binary value (`0.0625` gives `0.062`, `0.1875` gives `0.188`, `0.9996` gives
`1`, `999.9996` gives `1,000`), `-0.0` gives `-0`; a `Float` is widened to `double` first (only
exactly representable floats are pinned). Every other argument, `String`, `Character`, `Boolean`
and user objects, is `String.valueOf`. Not pinned: `NaN` and infinities, `Date`, any other `Number`,
non-ASCII default-locale symbols. **Known differences** (no `java.text` or `java.math` in lazyrt):
the oracle prints doubles of 2^63 and above with all their digits, where LazyVM prints
`String.valueOf`; and it rounds on the shortest decimal text of the double, so `0.0005` gives `0`
where LazyVM, rounding the exact binary value as above, gives `0.001`. Pinned by: LoggingFormat ("int" to "char is not a number", "null
param", "object toString", "boolean char string", "string that looks numeric").

**MF5.** If the pattern is malformed after the marker test of MF1 passes (unclosed `{`, an index
that is empty, negative, not a number or 10000 and above, `{{`, `{}`), or an argument's `toString()`
throws, the original message is returned unchanged;
nothing is thrown. A `{n,type,style}` element is outside the subset: the oracle formats it, LazyVM
may treat it as malformed (unchanged message). Pinned by: LoggingFormat ("unclosed", "unclosed
later", "not a number", "negative index", "empty braces", "nested braces").

## 8. Handler

**H1.** A direct subclass of `Handler` starts with level `ALL`, no formatter, no filter, no
encoding, and an `ErrorManager` that is never null (a plain `java.util.logging.ErrorManager`).
`StreamHandler` and `ConsoleHandler` start with level `INFO`, a `SimpleFormatter`, no filter, no
encoding and a plain `ErrorManager`; `StreamHandler(out, f)` also has level `INFO`, formatter `f`,
no filter, no encoding. Pinned by: LoggingStream ("defaults", "StreamHandler defaults",
"StreamHandler(out, f) defaults", "ConsoleHandler defaults", "ConsoleHandler is a StreamHandler").

**H2.** `setFormatter(null)`, `setLevel(null)`, `setErrorManager(null)` and `isLoggable(null)`
throw `NullPointerException`; `StreamHandler(null, f)` and `StreamHandler(out, null)` too.
`setFilter(null)` and `setEncoding(null)` are accepted. Pinned by: LoggingStream ("StreamHandler(out)
null out", "StreamHandler(out) null formatter", "setFormatter null", "setLevel null",
"setErrorManager null", "isLoggable null", "setEncoding null", "setters", "filter").

**H3.** `isLoggable(r)` is false when the filter exists and refuses `r`, and when
`r.getLevel().intValue()` is below the handler's level or the handler's level is `OFF` (then even
`OFF` records fail). Level `ALL` accepts everything, `OFF` records included. Pinned by:
LoggingStream ("isLoggable", "level OFF", "level ALL", "level 555").

**H4.** `setEncoding(name)` accepts any charset name or alias the runtime knows and stores the string
as written (`getEncoding()` gives back `ISO-8859-1`, not a canonical form); an unknown name throws
`UnsupportedEncodingException` whose message is the name, and the old encoding stays. Pinned by:
LoggingStream ("setEncoding", "setEncoding latin1", "setEncoding bogus", "encoding kept after
failure").

**H5.** `reportError(msg, ex, code)` calls `error(msg, ex, code)` on the error manager that was set
(an overridden `getErrorManager()` is not asked) with the same arguments. If that call throws, the
exception does not escape: the lines `Handler.reportError caught:` and the exception's stack trace go to
`System.err`. Pinned by: LoggingStream ("reportError rep re 2").

## 9. StreamHandler

**SH1.** `publish(r)` does nothing when `isLoggable(r)` is false (the handler is closed, has no
stream, the level or the filter refuses). Otherwise it formats `r`; before the **first** record
on the current stream it writes `formatter.getHead(this)`, then the formatted text; a head that
throws is not counted as written and is asked for again by the next record. The text goes through one
encoder per stream (a byte-order mark is written once, a surrogate pair may be split between records)
with a bounded buffer: small outputs reach the `OutputStream` only at `flush()`, `close()` or
`setOutputStream`, but the buffer cannot grow without bound, so large output is written through.
A write that fails drops the bytes it held; they are not retried, and bytes of a stream never reach
the next one. Pinned by: LoggingStream ("buffered after publish", "after
flush", "filter", "level ALL").

**SH2.** `flush()` pushes the buffered bytes into the stream and calls the stream's `flush()`; it
writes no head. `close()` writes the head if no record was written yet, then
`formatter.getTail(this)`, flushes the `OutputStream` and then closes it (if the flush fails, the failure is
reported with code 3 and the stream is not closed), and detaches the stream: after
that `isLoggable` is false, `publish`, `flush` and `close` do nothing (a second `close` does not
close the stream again). Pinned by: LoggingStream ("after close", "publish after close", "close
twice", "close without records", "flush without records").

**SH3.** A `StreamHandler()` with no stream: `isLoggable` false, `publish`, `flush`, `close` are
no-ops. `setOutputStream(out)` (protected): null → `NullPointerException`; with an attached stream
it first closes that one the way `close()` does (head if missing, tail, flush, close the stream),
then attaches `out` as a fresh stream whose first record writes the head again. Pinned by:
LoggingStream ("no stream", "no stream survives", "loggable with stream", "old stream finished and
closed", "new stream untouched", "new stream gets head again", "null stream", "closed").

**SH4.** Bytes: the handler's encoding, or the default charset when null; an unmappable character
becomes `?`. `setEncoding` after output has started flushes what is pending, switches, and writes no
second head. Pinned by: LoggingStream ("utf-8 bytes", "latin1 bytes (unmappable becomes ?)",
"encoding change keeps earlier bytes, no second head", "default encoding is the platform's").

**SH6.** `StreamHandler(out, f)` sets its state through the overridable `setEncoding(null)` and
`setOutputStream(out)`, in that order. Pinned by: LoggingEncoding ("the constructor goes through").

**SH5.** Failures, unchecked exceptions from the stream included, go to the error manager and never out
of `publish`/`flush`/`close`: a formatter
that throws gives `error(null, thatException, 5)` (`FORMAT_FAILURE`) and the record is dropped; an
`IOException` from the stream at `flush()` gives `error(null, e, 2)` and at `close()` gives
`error(null, e, 3)`. (A stream that fails while `publish` is still buffering reports at the next
`flush`/`close`; `WRITE_FAILURE` 1 arises when an implementation's buffer overflows during
`publish`, which the tests never reach.) Pinned by: LoggingStream ("reported …" lines, "publish
survives a failing formatter").

## 10. ConsoleHandler and ErrorManager

**C1.** `new ConsoleHandler()` writes to the `System.err` that exists **at construction**; a later
`System.setErr` does not move it. It publishes like a `StreamHandler` and then flushes, so each
record is visible at once. `close()` only flushes: no tail, `System.err` is not closed, and the
handler keeps working after it. Pinned by: LoggingStream ("writes at once to the System.err of its
creation", "level INFO", "close adds no tail, closes nothing", "publish after close", the stderr
lines).

**C2.** The root logger's `ConsoleHandler` is such a handler on the real `System.err`; records
that reach it print there (stdout and stderr are separate parts of the transcript). Its level
`INFO` hides `CONFIG` and below until `setLevel` is called on it; a logger at `ALL` does not
change that. Removing it from the root silences the root. When the handler is created relative to
a `System.setErr` is not pinned. Pinned by: LoggingRoot (stderr lines, "end").

**E1.** `ErrorManager` constants: `GENERIC_FAILURE` 0, `WRITE_FAILURE` 1, `FLUSH_FAILURE` 2,
`CLOSE_FAILURE` 3, `OPEN_FAILURE` 4, `FORMAT_FAILURE` 5. The plain `error(msg, ex, code)` prints
**only the first call of each instance**, to `System.err`: the line `java.util.logging.ErrorManager: `
+ code, followed by `: ` + msg when `msg` is not null, then a line separator, then
`ex.printStackTrace()` when `ex` is not null; later calls on that instance print nothing. Pinned
by: LoggingStream ("codes", and the stderr lines from `java.util.logging.ErrorManager: 3: m1`).

## 11. LogManager

**X1.** `getLogManager()` always returns the same object. `getLogger(name)` returns the registered
logger or null (null name → `NullPointerException`). `getLoggerNames()` lists the registered names:
`""` and `"global"` from the moment the manager exists (so `getLogger("global")` finds `Logger.global`
at once, and its handlers are closed by `reset()` and at exit), anonymous loggers never; the order is
not pinned. A `LogManager` made by a subclass is a separate, empty registry: it has no root, and a
logger added to it has no parent. Pinned by:
Logging ("same manager", "getLogger", "getLogger unknown", "getLogger null", "names has").

**X2.** `addLogger(l)` registers `l` under `l.getName()` and returns true if that name is free,
else returns false and changes nothing. Registered, `l` gets a parent by L3 (root for a name
without a registered prefix; before that its parent is null), is found by `getLogger` and by
`Logger.getLogger(name)`, and becomes the parent of later loggers below it. Pinned by: Logging
("custom before", "addLogger", "custom after", "getLogger finds custom", "addLogger twice",
"addLogger same name", "addLogger kept first", "child of custom").

**X3.** `reset()` takes a snapshot of the loggers and closes with no lock held, so a `close()` may
create loggers or wait for a thread that uses `Logger`, and one that throws does not stop the others.
It closes (calls `close()` on) every handler of the root and of each registered
logger, once per registration, and removes them; sets the root level to `INFO` and every other
registered logger's level to null. The loggers stay registered, and their `useParentHandlers`
setting is not touched. The root has no handler afterwards (not even the default one); a new one can
be added. Pinned by: Logging ("reset closes handlers", "reset root", "reset child", "reset keeps
registry", "after reset add").

**X4.** When the VM exits, by the end of `main` and its non-daemon threads or by `System.exit`,
the effect of `reset()` happens once: every handler of the root and the registered loggers gets
`close()` (no separate `flush()`), the handlers of anonymous loggers get nothing. The order among
loggers is not pinned (tests use one handler). Pinned by: LoggingExit, LoggingExitCode.

## 12. Not pinned: the implementation may choose

- Weak references: the oracle holds registered loggers weakly, so an unreferenced logger can vanish
  with its level and handlers; LazyVM may hold them strongly (tests keep references).
- When the root's default handler and the default config are built (first use of any logger is
  fine), and the `logging.properties` / `java.util.logging.config.*` machinery: not supported.
- Names with empty dotted segments (L3), the order of `getLoggerNames`, the order in which `reset`
  and the exit hook visit loggers, and the source of calls on the root logger (S3).
- `Level` and `LogRecord` serialization (`readResolve`, `serialVersionUID`): the classes are
  `Serializable` but never serialized.
- `{n,type,style}` elements, `NaN`, infinities, `Date` and other `Number` arguments (MF4, MF5);
  `Handler.isLoggable` order of the level and filter tests when both would fail; threading
  guarantees beyond "do not corrupt state".
- The first sequence number, the thread id of `main`, hash codes of loggers and handlers, and the
  `toString()` of anything but `Level`.
- An empty `java.util.logging.SimpleFormatter.format` value.

## 13. Subset, dependencies, size and how to re-bless

**What lazyrt lacks.** There is no `java.text` (`MessageFormat`, `NumberFormat`) and no
`ResourceBundle`; only `MissingResourceException` exists. So: `{n}` substitution is a private
helper (section 7; package-private in `java.util.logging`, or in `lazy.internal`); all
`ResourceBundle` members are left out of the API; bundle names are carried as strings and never
loaded (L7, V3).

**Closed subset.** Signatures are Java SE 8's; take `throws` clauses from the Javadoc. Nothing else
may be public or protected (AGENTS.md rule 2).

| Type | Members (everything the class declares in the Javadoc unless noted) |
|---|---|
| `Logger` | `protected Logger(String name, String resourceBundleName)`; `public static final String GLOBAL_LOGGER_NAME`; `@Deprecated public static final Logger global`; static `getGlobal()`, `getLogger(String)`, `getLogger(String, String)`, `getAnonymousLogger()`, `getAnonymousLogger(String)`; `getName()`, `getParent()`, `setParent(Logger)`, `getLevel()`, `setLevel(Level)`, `isLoggable(Level)`, `getFilter()`, `setFilter(Filter)`, `getHandlers()`, `addHandler(Handler)`, `removeHandler(Handler)`, `getUseParentHandlers()`, `setUseParentHandlers(boolean)`, `getResourceBundleName()`; `log(LogRecord)`, `log(Level, String)`, `log(Level, Supplier<String>)`, `log(Level, String, Object)`, `log(Level, String, Object[])`, `log(Level, String, Throwable)`, `log(Level, Throwable, Supplier<String>)`; the six `logp` overloads (same shapes after `String sourceClass, String sourceMethod`); `entering` (3), `exiting` (2), `throwing`; `severe`, `warning`, `info`, `config`, `fine`, `finer`, `finest`, each with `String` and `Supplier<String>`. **Left out:** `getResourceBundle`, `setResourceBundle`, all six `logrb`. |
| `Level` | `implements java.io.Serializable`; the nine `public static final Level` constants; `protected Level(String, int)`, `protected Level(String, int, String)`; `getResourceBundleName()`, `getName()`, `getLocalizedName()`, `final toString()`, `final intValue()`, `static parse(String)`, `equals(Object)`, `hashCode()`. |
| `LogRecord` | `implements java.io.Serializable`; `LogRecord(Level, String)`; getters and setters for loggerName, resourceBundleName, level, sequenceNumber, sourceClassName, sourceMethodName, message, parameters (`Object[]`), threadID (`int`), millis (`long`), thrown. **Left out:** `getResourceBundle`, `setResourceBundle`. |
| `Handler` | `public abstract`; `protected Handler()`; abstract `publish(LogRecord)`, `flush()`, `close()`; `setFormatter`, `getFormatter`, `setEncoding` (throws `UnsupportedEncodingException`), `getEncoding`, `setFilter`, `getFilter`, `setErrorManager`, `getErrorManager`, `protected reportError(String, Exception, int)`, `setLevel`, `getLevel`, `isLoggable(LogRecord)`. |
| `StreamHandler` | `public StreamHandler()`, `public StreamHandler(OutputStream, Formatter)`, `protected setOutputStream(OutputStream)`, `setEncoding(String)`, `publish`, `isLoggable`, `flush`, `close`. Kept because `ConsoleHandler` extends it and programs wrap streams with it. |
| `ConsoleHandler` | `public ConsoleHandler()`, `publish(LogRecord)`, `close()`. |
| `Formatter` | `public abstract`; `protected Formatter()`; abstract `format(LogRecord)`; `getHead(Handler)`, `getTail(Handler)`, `formatMessage(LogRecord)`. |
| `SimpleFormatter` | `public SimpleFormatter()`, `format(LogRecord)`. |
| `Filter` | `@FunctionalInterface public interface Filter { boolean isLoggable(LogRecord record); }`. |
| `ErrorManager` | the six `public static final int` constants (values in E1), `public ErrorManager()`, `public void error(String, Exception, int)`. Needed by `Handler`'s signatures. |
| `LogManager` | `protected LogManager()`; static `getLogManager()`; `addLogger(Logger)`, `getLogger(String)`, `getLoggerNames()` (`Enumeration<String>`), `reset()`. **Left out:** `readConfiguration` (both), `getProperty`, `checkAccess`, the property-change listener methods (`java.beans` is absent), `getLoggingMXBean`, `LOGGING_MXBEAN_NAME`. |

**Left out as types:** `FileHandler`, `SocketHandler`, `MemoryHandler`, `XMLFormatter`,
`LoggingPermission`, `LoggingMXBean`, `LogManager` configuration files. If a real program needs
`readConfiguration(InputStream)` later, it is a small addition on `Properties`; no program in the
tests needs it. Every type in a kept signature exists: `Supplier`, `Enumeration`, `OutputStream`,
`UnsupportedEncodingException`, `MissingResourceException`, `SecurityException`, `Serializable`.

**Needs from the rest of lazyrt** (all present on this branch): `java.util.Date` and the `%t`
conversions of `Formatter` (`%1$tb %1$td %1$tY %1$tl %1$tM %1$tS %1$Tp`, and for the tests `%tc`,
`%tF`, `%tT`, `%tL`, `%tZ`), which is why this work comes after the `java.time` work (S11-02);
`Throwable.getStackTrace()` with class names, method names including `<init>`/`<clinit>`, and
`printStackTrace(PrintWriter)` with `Suppressed:`/`Caused by:`; `Thread.getId()`;
`Runtime.addShutdownHook` or an equivalent exit hook (X4; `System.exit` and the end of `main` must
both run it); `OutputStreamWriter(OutputStream, String)` with a buffering encoder and `?` for
unmappable characters; `Charset.isSupported` for H4; `System.setErr`.

**Size.** Eleven public classes plus the number and quote helper of section 7 should compress to
well under the 40 KB budget; keep source-class inference to one private method that walks
`getStackTrace()`, and keep `LogRecord`/`Level` plain.

**Tests and re-blessing.** Twenty-one tests, all `package util`, none over 400 lines: `Logging`,
`LoggingLevels`, `LoggingRecords`, `LoggingFlow`, `LoggingFormat`, `LoggingFormatLate`,
`LoggingFormatBad`, `LoggingFormatMissing`, `LoggingFormatDate`, `LoggingStream`, `LoggingRoot`,
`LoggingExit`, `LoggingExitCode`, `LoggingExitThread`, `LoggingSubclass`, `LoggingTree`, `LoggingReset`,
`LoggingEncoding`, `LoggingFormatEdge`, `LoggingSource`, `LoggingTrace`. Each owns its loggers; the ones that print dates carry
`// VMARGS: -Duser.timezone=UTC` and fix the millis; `LoggingRoot` and `LoggingFormatDate` set the
format property with `// VMARGS:` so no date is printed. Importing both `java.util.*` and
`java.util.logging.*` makes `Formatter` ambiguous, so the tests import by name.

The goldens were written before lazyrt had `java.util.logging`, by a scratch script that compiled
the tests with the oracle's own `rt.jar` as bootclasspath and ran them with exactly
`golden.ORACLE_FLAGS` in the transcript format of `tools/testlib.py`. Once the classes exist, run
`python tools/golden.py --bless tests/java/util/Logging*.java` on the oracle: **the diff of the
`.out` files must be empty.** A non-empty diff is a bug in the test or in this document, not a
reason to edit a golden by hand; report it.

Two things the goldens contain that look odd and are on purpose: stderr appears after a
`--- stderr ---` line (`LoggingFormatLate`, `LoggingRoot`, `LoggingStream`), and `LoggingExitCode`
ends with `--- exit 2 ---`.
