package java.time;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.Objects;
import lazy.internal.Calendars;
import lazy.internal.TimeChecks;

/**
 * A date and time without a zone, kept as plain fields. Only what the games and tools that use
 * java.time need exists (docs/design/time.md); there is no arithmetic.
 */
public final class LocalDateTime implements TemporalAccessor, java.io.Serializable {
    private static final long MILLIS_PER_DAY = 86400000L;

    private final int year;
    private final int month;
    private final int day;
    private final int hour;
    private final int minute;
    private final int second;
    private final int nano;

    private LocalDateTime(int year, int month, int day, int hour, int minute, int second, int nano) {
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.nano = nano;
    }

    /** The current UTC date and time, to the millisecond. */
    public static LocalDateTime now() {
        long millis = System.currentTimeMillis();
        long[] ymd = Calendars.gregorian(Math.floorDiv(millis, MILLIS_PER_DAY));
        int ms = (int) Math.floorMod(millis, MILLIS_PER_DAY);
        return new LocalDateTime((int) ymd[0], (int) ymd[1], (int) ymd[2], ms / 3600000, ms / 60000 % 60,
                ms / 1000 % 60, ms % 1000 * 1000000);
    }

    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute) {
        return of(year, month, dayOfMonth, hour, minute, 0, 0);
    }

    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute, int second) {
        return of(year, month, dayOfMonth, hour, minute, second, 0);
    }

    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute, int second,
            int nanoOfSecond) {
        checkDate(year, month, dayOfMonth);
        TimeChecks.check("HourOfDay", 0, 23, hour);
        TimeChecks.check("MinuteOfHour", 0, 59, minute);
        TimeChecks.check("SecondOfMinute", 0, 59, second);
        TimeChecks.check("NanoOfSecond", 0, 999999999, nanoOfSecond);
        return new LocalDateTime(year, month, dayOfMonth, hour, minute, second, nanoOfSecond);
    }

    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute) {
        return of(year, month, dayOfMonth, hour, minute, 0, 0);
    }

    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute, int second) {
        return of(year, month, dayOfMonth, hour, minute, second, 0);
    }

    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute, int second,
            int nanoOfSecond) {
        Objects.requireNonNull(month, "month");
        return of(year, month.getValue(), dayOfMonth, hour, minute, second, nanoOfSecond);
    }

    public static LocalDateTime parse(CharSequence text) {
        Objects.requireNonNull(text, "text");
        return IsoParser.parse(text.toString());
    }

    /**
     * What the parser builds: the date is checked as in of(), then the minute, the hour and the
     * second, which is the order the reference behaviour reports in (docs/design/time.md, P3).
     */
    static LocalDateTime parsed(long year, int month, int day, int hour, int minute, int second, int nano) {
        TimeChecks.check("Year", -999999999, 999999999, year);
        checkDate((int) year, month, day);
        TimeChecks.check("MinuteOfHour", 0, 59, minute);
        TimeChecks.check("HourOfDay", 0, 23, hour);
        TimeChecks.check("SecondOfMinute", 0, 59, second);
        return new LocalDateTime((int) year, month, day, hour, minute, second, nano);
    }

    private static void checkDate(int year, int month, int day) {
        TimeChecks.check("Year", -999999999, 999999999, year);
        TimeChecks.check("MonthOfYear", 1, 12, month);
        if (day < 1 || day > 31) {
            throw TimeChecks.range("DayOfMonth", "1 - 28/31", day);
        }
        if (day > 28 && day > monthLength(year, month)) {
            if (day == 29) {
                throw new DateTimeException("Invalid date 'February 29' as '" + year + "' is not a leap year");
            }
            throw new DateTimeException("Invalid date '" + Month.of(month) + " " + day + "'");
        }
    }

    private static int monthLength(int year, int month) {
        if (month == 2) {
            return TimeChecks.isLeap(year) ? 29 : 28;
        }
        return month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
    }

    /** Days from 1970-01-01 in the proleptic Gregorian calendar, counting years from March. */
    private long epochDay() {
        long y = month <= 2 ? year - 1L : year;
        long monthFromMarch = (month + 9) % 12;
        long daysInYear = (153 * monthFromMarch + 2) / 5 + day - 1;
        return 365 * y + Math.floorDiv(y, 4L) - Math.floorDiv(y, 100L) + Math.floorDiv(y, 400L)
                + daysInYear - 719468;
    }

    private int dayOfYear() {
        return TimeChecks.daysBefore(month, TimeChecks.isLeap(year)) + day;
    }

    public int getYear() {
        return year;
    }

    public Month getMonth() {
        return Month.of(month);
    }

    public int getMonthValue() {
        return month;
    }

    public int getDayOfMonth() {
        return day;
    }

    public int getHour() {
        return hour;
    }

    public int getMinute() {
        return minute;
    }

    public int getSecond() {
        return second;
    }

    public int getNano() {
        return nano;
    }

    public String format(DateTimeFormatter formatter) {
        return Objects.requireNonNull(formatter, "formatter").format(this);
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField;
    }

    public long getLong(TemporalField field) {
        if (field == null) {
            throw new NullPointerException();
        }
        if (!(field instanceof ChronoField)) {
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        switch ((ChronoField) field) {
            case NANO_OF_SECOND: return nano;
            case MILLI_OF_SECOND: return nano / 1000000;
            case SECOND_OF_MINUTE: return second;
            case MINUTE_OF_HOUR: return minute;
            case HOUR_OF_DAY: return hour;
            case CLOCK_HOUR_OF_AMPM: return hour % 12 == 0 ? 12 : hour % 12;
            case AMPM_OF_DAY: return hour / 12;
            case DAY_OF_WEEK: return Math.floorMod(epochDay() + 3, 7L) + 1; // 1970-01-01 was a Thursday
            case DAY_OF_MONTH: return day;
            case DAY_OF_YEAR: return dayOfYear();
            case MONTH_OF_YEAR: return month;
            case YEAR_OF_ERA: return year >= 1 ? year : 1L - year;
            default: return year;
        }
    }

    public boolean equals(Object o) {
        if (!(o instanceof LocalDateTime)) {
            return false;
        }
        LocalDateTime t = (LocalDateTime) o;
        return year == t.year && month == t.month && day == t.day && hour == t.hour && minute == t.minute
                && second == t.second && nano == t.nano;
    }

    /** A polynomial hash over the fields, so equal values hash alike. */
    public int hashCode() {
        int h = year;
        h = 31 * h + month;
        h = 31 * h + day;
        h = 31 * h + hour;
        h = 31 * h + minute;
        h = 31 * h + second;
        return 31 * h + nano;
    }

    /** The ISO form; the seconds appear when they or the fraction are not zero. */
    public String toString() {
        StringBuilder b = new StringBuilder(32);
        if (year > 9999) {
            b.append('+');
        }
        TimeChecks.pad(b, year, 4).append('-');
        TimeChecks.pad(b, month, 2).append('-');
        TimeChecks.pad(b, day, 2).append('T');
        TimeChecks.pad(b, hour, 2).append(':');
        TimeChecks.pad(b, minute, 2);
        if (second > 0 || nano > 0) {
            TimeChecks.pad(b.append(':'), second, 2);
        }
        if (nano > 0) {
            appendFraction(b.append('.'));
        }
        return b.toString();
    }

    /** The fewest of 3, 6 or 9 digits that show the nano exactly. */
    private void appendFraction(StringBuilder b) {
        if (nano % 1000000 == 0) {
            TimeChecks.pad(b, nano / 1000000, 3);
        } else if (nano % 1000 == 0) {
            TimeChecks.pad(b, nano / 1000, 6);
        } else {
            TimeChecks.pad(b, nano, 9);
        }
    }
}
