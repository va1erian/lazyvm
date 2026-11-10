# Minimal java.time and the %t conversions of Formatter (S11-02): behaviour beyond the Javadoc

This is the specification for S11-02 (`docs/plan-2.md`, decisions.md D6). The item is kept small on
purpose: it covers what the Minicraft port calls, not the whole of `java.time`. Implement the Java SE 8
Javadoc for the members in section 2, then the rules below. A rule reads *input → observable result* and
ends with the tests in `tests/java/time` that pin it. Every golden is blessed on the oracle (Temurin
1.8.0_504) with `python tools/golden.py --bless tests/java/time`; after the implementation exists that
command must leave every golden unchanged. `Markers` could not be compiled against the library that
existed before this item, so its golden was produced with the oracle's own `javac`; re-blessing must
not change it either. Nothing here says how the classes are built; only what a program can observe is
fixed. The rules come from the Javadoc (signatures) and from experiments on the oracle.

**How the tests print.** A test line is `label: ` and the value or the exception's `toString()`, so a rule
about an exception fixes its class and its message. `NullPointerException: text` means the message is
the parameter name. Tests that touch the default zone carry `// VMARGS: -Duser.timezone=...`.

## 1. What the game needs, and what is left out

The game calls `LocalDateTime.now()`, `LocalDateTime.parse(CharSequence)`, `LocalDateTime.toString()`
(saved, then parsed back), `getMonth()`, `getDayOfMonth()`, `Month.DECEMBER` and its siblings, and
`DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").format(dateTime)`. Programs compiled against a JDK
that call anything else fail to link, exactly as for any class not yet in `lazyrt`.

| Left out | How a program sees it |
|---|---|
| `Instant`, `Duration`, `Period`, `LocalDate`, `LocalTime`, `DayOfWeek`, `Clock`, `ZoneId`, `ZoneOffset`, `ValueRange`, `TemporalAdjusters`, the `chrono` types, `ResolverStyle`, `TextStyle` | `NoClassDefFoundError` on first use |
| arithmetic, `until`, `compareTo`, `with`, `plus`, conversions to dates, times or instants on `LocalDateTime`; `Month` methods other than the constants, `of`, `getValue` | `NoSuchMethodError` |
| formatter zones, locales, resolver styles, `parse` with a pattern, `ISO_*` constants other than what `LocalDateTime.parse` uses, `formatTo` | `NoSuchMethodError` |
| pattern letters other than `y u M d H m s S a A` (`MMM`, `E`, `G`, `h`, `n`, `X`, `z` and the rest), `AA` and longer runs of `A`, `MMM` and longer runs of `M`, and the optional-section brackets `[` | `IllegalArgumentException` from `ofPattern`. **Known difference, unpinned**: the oracle accepts them (`[` is refused as `Pattern includes reserved character: '['`). `a` (AM/PM, one letter) and `A` (milli of day, one letter) are kept because the goldens `a''`, `''a` and `A'` use them |
| a `]` in a pattern | `IllegalArgumentException: Pattern invalid as it contains ] without previous [`, as on the oracle (pinned) |
| a default zone id that is not `UTC`, `GMT` or a `GMT` offset | **Known difference, unpinned**: the oracle falls back to the machine's zone; LazyVM uses `UTC`, or `GMT` for a `GMT…` id that names no valid offset |
| `Date.toInstant`, `Date.from`, `FileTime` bridges | not present |
| `java.io.Serializable` object streams | out (D6); the marker interface is present (M2) |
| the system time zone database | `now()` and the default zone of `%t` use UTC or the zones of Z1. **Known difference, unpinned**: the oracle uses the machine's zone |

## 2. The kept members

All signatures are Java SE 8's.

```java
package java.time;
public final class LocalDateTime implements TemporalAccessor, java.io.Serializable {
    public static LocalDateTime now();
    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute);
    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute, int second);
    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute, int second, int nanoOfSecond);
    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute);
    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute, int second);
    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute, int second, int nanoOfSecond);
    public static LocalDateTime parse(CharSequence text);
    public String format(DateTimeFormatter formatter);
    public int getYear();  public Month getMonth();  public int getMonthValue();  public int getDayOfMonth();
    public int getHour();  public int getMinute();  public int getSecond();  public int getNano();
    public boolean isSupported(TemporalField field);
    public long getLong(TemporalField field);
    public boolean equals(Object o);  public int hashCode();  public String toString();
}
public enum Month { JANUARY, FEBRUARY, MARCH, APRIL, MAY, JUNE, JULY, AUGUST, SEPTEMBER, OCTOBER, NOVEMBER, DECEMBER;
    public static Month of(int month);  public int getValue(); }
public class DateTimeException extends RuntimeException { (String), (String, Throwable) }

package java.time.format;
public final class DateTimeFormatter { public static DateTimeFormatter ofPattern(String pattern);
    public String format(TemporalAccessor temporal); public String toString(); }
public class DateTimeParseException extends DateTimeException {
    (String message, CharSequence parsedData, int errorIndex), (…, Throwable cause);
    public String getParsedString(); public int getErrorIndex(); }

package java.time.temporal;
public interface TemporalAccessor { boolean isSupported(TemporalField field); long getLong(TemporalField field); }
public interface TemporalField { }
public enum ChronoField implements TemporalField { NANO_OF_SECOND, MILLI_OF_SECOND, SECOND_OF_MINUTE, MINUTE_OF_HOUR,
    HOUR_OF_DAY, CLOCK_HOUR_OF_AMPM, AMPM_OF_DAY, DAY_OF_WEEK, DAY_OF_MONTH, DAY_OF_YEAR, MONTH_OF_YEAR, YEAR_OF_ERA, YEAR; }
public class UnsupportedTemporalTypeException extends DateTimeException { (String), (String, Throwable) }
```

`TemporalAccessor` here has only the two abstract methods; user classes implement exactly those two (the
tests do). `ChronoField.toString()` is the oracle's (`YearOfEra`, `HourOfDay`, …). `LocalDateTime` and
`Month` have no other public member and no other supertype; the interfaces and members of the oracle
that are not listed are the "left out" row above.

## 3. LocalDateTime

**L1.** `toString()` is `uuuu-MM-dd'T'HH:mm`, then `:ss` when the second or the nano is non-zero, then `.`
and 3, 6 or 9 fraction digits (the fewest that show the nano exactly) when the nano is non-zero (so
`…T03:04:00.000000007` keeps `:00`). Years 0 to 9999 have four digits; above 9999 a leading `+`;
negative years a `-` and at least four digits (`-0005`, `-10000`).
Pinned by: LocalDateTimes ("toString …").

**L2.** The factories check, in this order: year (`DateTimeException: Invalid value for Year (valid values
-999999999 - 999999999): 1000000000`), month 1..12 (`Invalid value for MonthOfYear (valid values 1 - 12): 13`),
day 1..31 (`Invalid value for DayOfMonth (valid values 1 - 28/31): 32`), then the calendar (day 29 of
February in a non-leap year: `Invalid date 'February 29' as '2021' is not a leap year`; day 30 or 31 that
the month lacks: `Invalid date 'FEBRUARY 30'`, `'APRIL 31'`, upper case), then hour 0..23
(`HourOfDay (valid values 0 - 23)`), minute (`MinuteOfHour … 0 - 59`), second (`SecondOfMinute … 0 - 59`) and nano
(`NanoOfSecond … 0 - 999999999`). The `Month` forms do the same; a `null` month is
`NullPointerException: month`. Leap years are the Gregorian rule for every `int` year, `0` and negative
ones included (`-4` and `0` are leap, `-1` and `1900` are not, `2000` is).
Pinned by: LocalDateTimes ("of …").

**L3.** The getters return the parts. `isSupported(f)` and `getLong(f)` answer for every kept `ChronoField`: the time
fields of the clock, `CLOCK_HOUR_OF_AMPM` 1..12 (`12` at midnight and at noon), `AMPM_OF_DAY` 0 or 1, `DAY_OF_WEEK` 1 (Monday) to 7
(1970-01-01 was a Thursday), `DAY_OF_YEAR`, `YEAR_OF_ERA` (the year for years ≥ 1, `1 - year` otherwise), `YEAR`.
`getLong(null)` is a plain `NullPointerException`; `isSupported(null)` is false.
Pinned by: LocalDateTimes ("fields …", "getters …", "getLong null", "isSupported null").

**L4.** `equals` compares the fields (false for `null` and other classes). Equal values have equal hash codes; the value of the hash is not pinned.
Pinned by: LocalDateTimes ("hashCode equal values …", "equals").

**L5.** `Month`: `values()` in calendar order, `getValue()` 1..12, `of(n)` outside 1..12 is
`DateTimeException: Invalid value for MonthOfYear: 13` (no range in the message). The other enum members are the
language's.
Pinned by: LocalDateTimes ("Month …").

## 4. LocalDateTime.parse

`parse(text)` reads the same shapes `toString()` writes and a few more; any `CharSequence` is accepted and `null` is
`NullPointerException: text`.

**P1.** The accepted text: a year (exactly four digits; or `-` and four or more digits; or `+` and five or more digits,
`+02020` being 2020), `-`, two-digit month, `-`, two-digit day, `T` or `t`, two-digit hour, `:`, two-digit minute,
optionally `:` and a two-digit second, and after the second optionally `.` and 0 to 9 digits (`05.` is fine). ASCII digits only.
Everything `toString()` writes round-trips.
Pinned by: LocalDateTimeParse ("round trip …", the accepted list).

**P2.** Text that does not fit is `DateTimeParseException: Text '<text>' could not be parsed at index <n>`, `n` the first position that does not fit;
`getParsedString()` is the text, `getErrorIndex()` is `n`, `getCause()` is null. Text left over after a complete date-time
is `Text '<text>' could not be parsed, unparsed text found at index <n>` (an offset, `Z`, `[zone]`, a blank, a tenth fraction digit). The text
inside the quotes is cut to its first 64 characters followed by `...` when it is longer than 64 (exactly 64 is not cut);
`getParsedString()` is always the whole text.
`-0000` and `+0000` fail at index 0; `+123` and `-1` fail at the digit after the sign (index 1); `12345` and `+2020` fail at index 0.
Pinned by: LocalDateTimeParse ("syntax" and "unparsed" rows).

**P3.** Text that fits but names an impossible value is `Text '<text>' could not be parsed: <message>` with error index 0 and the
`DateTimeException` of L2 as cause (same messages as L2: month 13, day 32, `Invalid date 'FEBRUARY 30'`, `'February 29' as '2021' is
not a leap year`, hour 24, minute 60, second 60 or 61, a year beyond ±999999999). Hour `24:00` and second `60` are errors.
Several bad values: the date is checked first (year, month, day, calendar), then the minute, the hour and the second, so `T99:99` reports the minute and `T99:00:99` the hour. This differs from the order of L2 and is pinned by `2020-01-02T99:99`, `2020-13-99T99:99`, `2020-01-02T99:00:99` and `2020-01-02T00:99:99`.
Pinned by: LocalDateTimeParse ("resolution" rows).

## 5. DateTimeFormatter

**F1.** `ofPattern(p)` accepts the letters `y u M d H m s S a A`, literals and quotes. `a` prints `AM` or `PM`; `A` prints the milli of day as a plain number. Each takes one letter: `aa` is `Too many pattern letters: a` (pinned, the oracle agrees) and `AA` is the same with `A` (the oracle accepts it; unpinned). `[` is refused (see section 1) and `]` is `Pattern invalid as it contains ] without previous [` (pinned). `'…'` is literal text, `''` a quote (alone or inside
quotes); a pattern that ends inside a quote is `IllegalArgumentException: Pattern ends with an incomplete string literal: <whole pattern>`.
Other non-letters are literal text (`-`, `.`, `:`, space, `/`). `{`, `}` and `#` are `Pattern includes reserved character: '{'`.
An unknown letter is `Unknown pattern letter: g`. Too many letters: `Too many pattern letters: d` for `d` ≥ 3, `H`, `m`, `s` ≥ 3;
`S` ≥ 10 is `Minimum width must be from 0 to 9 inclusive but was 10`; 20 or more `y` or `u` is
`The minimum width must be from 1 to 19 inclusive but was 20`. The empty pattern prints `""`. `null` is `NullPointerException: pattern`.
Errors come from `ofPattern`, not from `format`. 11 to 18 letters of `y` fail at format time on the oracle with an
`ArrayIndexOutOfBoundsException` (an oracle defect): unpinned, not used.
Pinned by: FormatPatterns ("pattern rows", "ofPattern …", "20 y", "26 y").

**F2.** What each letter prints for a `LocalDateTime` (the rows of FormatPatterns show four dates each):
- `y` year-of-era (never negative: year -5 is 6): 1 letter the number; 2 the last two digits; 3 at least three digits;
  4 at least four digits and a `+` when there are more than four (`+12345`); n ≥ 5 at least n digits, and a `+` when there are more than n
  (`yyyyy` of 123456 is `+123456`, of 12345 `12345`, of 99999 `99999`; `yyyyyy` of 123456 is `123456`).
- `u` year: as `y` but proleptic and signed, a `-` before negative years with every count (`-5`, `-005`, `-0005`); 2 letters the last two digits of the absolute value.
- `M`, `d`, `H`, `m`, `s`: 1 letter the number, 2 letters two digits (`dd` of 5 is `05`); a value wider than the width fails (F4).
- `S` n letters (1 to 9): the first n digits of the nano, truncated, never rounded.
- Adjacent numbers print with no separator (`yyyyMMdd`); `y y` prints the year twice; a digit in a pattern is a literal.
Pinned by: FormatPatterns (the pattern rows, "year … " rows).

**F3.** `format(null)` is `NullPointerException: temporal`. The formatter reads the fields it needs in pattern order from any
`TemporalAccessor` by calling `getLong` and does not consult `isSupported`. An exception thrown by `getLong`
(`DateTimeException: custom`, `UnsupportedTemporalTypeException: Unsupported field: YearOfEra`, naming the field the letter maps to:
`y` `YearOfEra`, `u` `Year`, `M` `MonthOfYear`, `d` `DayOfMonth`, `H` `HourOfDay`, `m` `MinuteOfHour`, `s` `SecondOfMinute`, `S` `NanoOfSecond`)
comes out unchanged.
Pinned by: FormatPatterns ("format cannot give …", "format getLong throws", "format isSupported is not consulted").

**F4.** A two-letter number wider than two digits fails with `DateTimeException: Field MonthOfYear cannot be printed as the value 123 exceeds the maximum print width of 2`;
the width is checked before the sign, so a negative value with more than two digits (`-123`, `-12345`) is the same width error; a negative value of one or two digits in an unsigned letter: `Field HourOfDay cannot be printed as the value -5 cannot be negative according to the SignStyle`; `S` of an
out-of-range nano: `Invalid value for NanoOfSecond (valid values 0 - 999999999): -1`.
Pinned by: FormatPatterns ("format wide …", "format negative …").

**F5.** `format` leaves no state behind: the same formatter formats any number of values. `toString()` of a formatter is not pinned (non-null).
`LocalDateTime.format(f)` is `f.format(this)`; `format(null)` is `NullPointerException: formatter` (pinned by "LocalDateTime format null").
Pinned by: FormatPatterns ("game", "format same twice").

## 6. now(), Serializable

**N1.** `LocalDateTime.now()` is the current UTC date-time, to at least the millisecond. The real clock cannot be golden; `NowOnly` prints only classes and
facts that hold at every instant (fields in range, `parse(toString())` returns an equal value).
Pinned by: NowOnly.

**M2.** `LocalDateTime` is a `TemporalAccessor` and implements the marker `java.io.Serializable`, which `instanceof` and generic bounds see;
`Month` is an enum and so also `Serializable`. No object stream reads or writes them.
Pinned by: Markers.

## 7. The %t and %T conversions of java.util.Formatter

`Formatter` (and `String.format`, `printf`, …) takes `%t` and `%T` followed by a suffix. `%T` is the `%t` result upper-cased. The set of
accepted arguments, the fields, and the errors below are the observable behaviour; no public member is added to `java.util`.

**X1.** Arguments: `long`/`Long`, `java.util.Date`, and any `TemporalAccessor`; `null` prints `null` (`NULL` for `%T`) honouring width and `-`.
Anything else (`String`, `Integer`, `Short`, `Boolean`, `Double`, `Character`, `AtomicLong`, `StringBuilder`, any object) is
`IllegalFormatConversionException: H != java.lang.String` (suffix, ` != `, class name). A `TemporalAccessor` that cannot give a field is the same exception
with that accessor's class and the first suffix that failed, which for a composite suffix is the part: `%tc` of a `LocalDateTime` is `Z != java.time.LocalDateTime`
(it has no zone), as are `%tz`, `%tZ`, `%ts`, `%tQ`. A `getLong` that throws `DateTimeException`, and an accessor whose `isSupported` is false, give the same exception, and so does a value outside the range of its field
(hour 0-23 for `H k`, 1-12 for `I l`, minute and second 0-59, milli 0-999 for `L N`, `p` 0-1, month 1-12, weekday 1-7, day 1-31, day of year 1-366, year-of-era 1-1000000000, pinned at the boundary by the `%tY`, `%tC` and `%ty` rows of 1000000000 and 1000000001);
a `%tr` over an out-of-range hour names `I`, over an out-of-range am/pm `p`.
Composite suffixes: `%tc` = `%ta %tb %td %tT %tZ %tY`; `%tD` = `%tm/%td/%ty`; `%tF` = `%tY-%tm-%td`; `%tr` = `%tI:%tM:%tS %Tp`; `%tR` = `%tH:%tM`; `%tT` = `%tH:%tM:%tS`.
Pinned by: FormatterT (the `t?` rows, the `Acc` rows, the `range t?` rows).

**X2.** Fields of a `long`, `Long` or `Date`: the milliseconds since the epoch in the default zone (Z1), in the hybrid calendar `Date.toString()` uses:
Julian dates before 1582-10-15 (the day after 1582-10-04), Gregorian from then on, with the year-of-era, day of week and day of year of that calendar.
`-62167219200000` (0000-01-01 proleptic) prints `%tY` `0001`, `%tm` `01`, `%td` `03`, `%tA` `Saturday`, `%tj` `003`; `253402300800000` is year `10000`. Dates before the
change keep the Julian leap rule (`1500-02-29` exists, day of year 060 on the oracle's `1500-03-10` proleptic input). `Long.MIN_VALUE` and `Long.MAX_VALUE` work, also under a non-UTC default zone.
For a `LocalDateTime` the proleptic fields of the value are used.
Pinned by: FormatterT (long, Date rows, `Long.MIN_VALUE` rows), FormatterTCalendar, FormatterTZone.

**X3.** Suffixes (matrix rows are authoritative): `H` 00..23; `I` 01..12; `k` 0..23; `l` 1..12; `M`, `S` 00..59; `L` milliseconds 000..999; `N` nine digits,
always the milliseconds times 1 000 000; `p` `am`/`pm` (`%Tp` `AM`/`PM`); `z` the zone offset `+HHmm` and `Z` the zone's name, for `long` and `Date` only;
`s` seconds since the epoch (milliseconds divided by 1000 truncating toward zero) and `Q` milliseconds, for `long` and `Date` only; `B` month name, `b` and `h`
abbreviated, `A` weekday, `a` abbreviated (English); `C` year / 100; `Y` year, at least four digits; `y` last two digits; `m`, `d` two digits; `e` day without padding;
`j` 001..366; `R`, `T`, `r` (`03:04:05 AM`), `D`, `F`, `c` (`Thu Jan 02 03:04:05 UTC 2020`).
Unknown suffixes: `UnknownFormatConversionException: Conversion = 'tq'` (also `%Tq`); `%t` at the end of the format `Conversion = 't'`; `%t` followed by a non-letter
`Conversion = 't'`; `%t%` `'t%'`; `%tH%` `'%'`; `%T` alone `'T'`.
Pinned by: FormatterT.

**X4.** Flags, width, precision, index: width is honoured, right-justified or left with `-`, never truncating; `%T` upper-cases before justifying. `-` without width is
`MissingFormatWidthException: %-tH`. `0 # + space , (` are `FormatFlagsConversionMismatchException: Conversion = H, Flags = #`. A precision is `IllegalFormatPrecisionException: 2`.
Indexes work as for other conversions (`%1$tb`, `%<tH`, `%2$tH %1$tM`); a missing argument is `MissingFormatArgumentException: Format specifier '%tM'`.
Pinned by: FormatterT.

**X5.** `java.util.logging`'s default format `%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp` over `Date(0)` gives `Jan 01, 1970 12:00:00 AM`, over a `LocalDateTime` of
`2020-12-25T00:05:09` gives `Dec 25, 2020 12:05:09 AM` (`%tl` has no padding, `%td` has).
Pinned by: FormatterT ("%1$tb %1$td, …").

**X6.** Locales: names and markers are English for every `Locale`; the oracle agrees for `US`, `UK`, `ENGLISH`, `ROOT`. **Known difference, unpinned**: `Locale.FRANCE` prints French names on the oracle.
Pinned by: FormatterT ("Formatter(Locale.ROOT)", "Locale.UK").

## 8. The default zone for %t (Z1)

**Z1.** `user.timezone` is read once, the first time a `long` or `Date` is formatted with `%t`; changing the property afterwards changes nothing. Accepted values:
`UTC`, `GMT`, and `GMT` followed by `+` or `-` and an offset written `h`, `hh`, `hmm`, `hhmm`, `h:mm` or `hh:mm` (hours up to 23, minutes up to 59): the zone's name (`%tZ`) is `UTC`, `GMT`,
or `GMT±hh:mm` (`GMT-3` is `GMT-03:00`, `GMT+5:30` is `GMT+05:30`, `GMT+130` is `GMT+01:30`, `GMT+19` is `GMT+19:00`) and `%tz` is `±hhmm`. `Date.toString` reads the same zone
(`Thu Jan 01 01:30:00 GMT+01:30 1970`). Every other value (`Z`, `+01:00`, `UTC+01:00`, `UT`, region names such as
`Europe/Paris`, a `GMT` id with an offset beyond 23:59, garbage) is **not pinned**: the oracle uses the machine's zone or the region; LazyVM uses `UTC` (`GMT` for a `GMT…` id). Zero offsets spelled with digits (`GMT+0`) have
`%tZ` unpinned.
Pinned by: DefaultZoneUtc, DefaultZoneGmtPlus1, DefaultZoneGmtMinus3, DefaultZoneGmtPlus130, DefaultZoneGmtPlus19, DefaultZoneGmtPlus2359, DefaultZoneGmtMinus030, DefaultZoneCached.

## 9. Checks behind the expressions

The calendar of X2 was compared with the oracle on the test dates around 1582 and in Julian leap years, with no mismatch. Oracle behaviours left unpinned on purpose: letters dropped from the subset (section 1), widths 11 to 18 of `y`, the message
of several simultaneously bad parsed values, and zone-database dependent results.
