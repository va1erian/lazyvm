package java.util;

import java.time.temporal.TemporalAccessor;
import lazy.internal.Calendars;
import lazy.internal.TimeChecks;

/**
 * The text of the date/time conversions (`%t`, docs/design/time.md section 7) and of Date.toString.
 * A value is read through a Source: a millisecond instant (long, Date) in the default zone and the
 * calendar Date uses, or any TemporalAccessor.
 */
final class TimeFormat {
    /** What a conversion reads. The field numbers are the constants below. */
    interface Source {
        long get(int field, char conversion);

        /** The epoch milliseconds, for the conversions that need an instant. */
        long instant(char conversion);

        IllegalFormatConversionException fail(char conversion);
    }

    static final int MILLI = 0;
    static final int SECOND = 1;
    static final int MINUTE = 2;
    static final int HOUR = 3;
    static final int CLOCK_HOUR = 4;
    static final int AMPM = 5;
    /** Monday is 1. */
    static final int WEEKDAY = 6;
    static final int DAY = 7;
    static final int YEARDAY = 8;
    static final int MONTH = 9;
    /** The year of its era, so never below 1. */
    static final int YEAR = 10;

    private static final long DAY_MILLIS = 86400000L;
    /** The epoch day of 1582-10-15, the first Gregorian day. */
    private static final long GREGORIAN_START = -141427L;
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December"};
    private static final String[] WEEKDAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
        "Saturday", "Sunday"};
    /** The composite conversions and what they expand to; `P` is the upper-case `p`. */
    private static final String COMPOSITES = "cDFrRT";
    private static final String[] TEMPLATES = {"a b d T Z Y", "m/d/y", "Y-m-d", "I:M:S P", "H:M", "H:M:S"};

    private TimeFormat() {
    }

    /** The default zone, read from user.timezone the first time it is needed (docs/design/time.md Z1). */
    private static final class Zone {
        static final String NAME;
        static final int OFFSET_MILLIS;
        private static final int INVALID = Integer.MIN_VALUE;

        static {
            String id = System.getProperty("user.timezone");
            String name = "UTC";
            int minutes = 0;
            if (id != null && id.startsWith("GMT")) {
                // A GMT id that names no offset is plain GMT.
                name = "GMT";
                int m = id.length() == 3 ? INVALID : signedMinutes(id.substring(3));
                if (m != INVALID) {
                    minutes = m;
                    name = "GMT" + offsetText(m, ":");
                }
            }
            NAME = name;
            OFFSET_MILLIS = minutes * 60000;
        }

        /**
         * `+` or `-` and then h, hh, hmm, hhmm, h:mm or hh:mm; hours up to 23, minutes up to 59.
         * INVALID for anything else.
         */
        private static int signedMinutes(String t) {
            if (t.length() < 2 || (t.charAt(0) != '+' && t.charAt(0) != '-')) {
                return INVALID;
            }
            String r = t.substring(1);
            int colon = r.indexOf(':');
            int hours;
            int mins = 0;
            if (colon >= 0) {
                hours = digits(r.substring(0, colon), 1, 2);
                mins = digits(r.substring(colon + 1), 2, 2);
            } else if (r.length() >= 3) {
                hours = digits(r.substring(0, r.length() - 2), 1, 2);
                mins = digits(r.substring(r.length() - 2), 2, 2);
            } else {
                hours = digits(r, 1, 2);
            }
            if (hours < 0 || mins < 0 || hours > 23 || mins > 59) {
                return INVALID;
            }
            return (t.charAt(0) == '-' ? -1 : 1) * (hours * 60 + mins);
        }

        /** The value of 1 to 2 ASCII digits, or -1. */
        private static int digits(String s, int min, int max) {
            int v = 0;
            if (s.length() < min || s.length() > max) {
                return -1;
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') {
                    return -1;
                }
                v = v * 10 + c - '0';
            }
            return v;
        }
    }

    /** `+hhmm` style text of an offset; `sep` goes between the hours and the minutes. */
    private static String offsetText(int minutes, String sep) {
        StringBuilder b = new StringBuilder().append(minutes < 0 ? '-' : '+');
        TimeChecks.pad(b, Math.abs(minutes) / 60, 2).append(sep);
        return TimeChecks.pad(b, Math.abs(minutes) % 60, 2).toString();
    }

    /** Date.toString: the `%tc` text of an instant, but with the year not padded. */
    static String dateString(long millis) {
        Millis src = new Millis(millis);
        StringBuilder b = new StringBuilder(28);
        expand(b, TEMPLATES[0].substring(0, TEMPLATES[0].length() - 1), src);
        return b.append(src.get(YEAR, 'Y')).toString();
    }

    /** The text of conversion `c` for `arg`, or IllegalFormatConversionException if it cannot give it. */
    static String render(char c, Object arg) {
        Source src;
        if (arg instanceof Long) {
            src = new Millis(((Long) arg).longValue());
        } else if (arg instanceof Date) {
            src = new Millis(((Date) arg).getTime());
        } else if (arg instanceof TemporalAccessor) {
            src = new TimeAccessor((TemporalAccessor) arg);
        } else {
            throw new IllegalFormatConversionException(c, arg.getClass());
        }
        StringBuilder b = new StringBuilder();
        append(b, c, src);
        return b.toString();
    }

    private static void append(StringBuilder b, char c, Source src) {
        int k = COMPOSITES.indexOf(c);
        if (k < 0) {
            leaf(b, c, src);
            return;
        }
        expand(b, TEMPLATES[k], src);
    }

    /** Letters of the template are conversions, anything else is copied. */
    private static void expand(StringBuilder b, String template, Source src) {
        for (int i = 0; i < template.length(); i++) {
            char t = template.charAt(i);
            if (Character.isLetter(t)) {
                append(b, t, src);
            } else {
                b.append(t);
            }
        }
    }

    private static void leaf(StringBuilder b, char c, Source s) {
        switch (c) {
            case 'H': TimeChecks.pad(b, s.get(HOUR, c), 2); break;
            case 'k': b.append(s.get(HOUR, c)); break;
            case 'I': TimeChecks.pad(b, s.get(CLOCK_HOUR, c), 2); break;
            case 'l': b.append(s.get(CLOCK_HOUR, c)); break;
            case 'M': TimeChecks.pad(b, s.get(MINUTE, c), 2); break;
            case 'S': TimeChecks.pad(b, s.get(SECOND, c), 2); break;
            case 'L': TimeChecks.pad(b, s.get(MILLI, c), 3); break;
            case 'N': TimeChecks.pad(b, s.get(MILLI, c) * 1000000, 9); break;
            case 'p': case 'P': ampm(b, c, s); break;
            case 'B': b.append(MONTHS[(int) s.get(MONTH, c) - 1]); break;
            case 'b': case 'h': b.append(MONTHS[(int) s.get(MONTH, c) - 1], 0, 3); break;
            case 'A': b.append(WEEKDAYS[(int) s.get(WEEKDAY, c) - 1]); break;
            case 'a': b.append(WEEKDAYS[(int) s.get(WEEKDAY, c) - 1], 0, 3); break;
            case 'C': TimeChecks.pad(b, s.get(YEAR, c) / 100, 2); break;
            case 'Y': TimeChecks.pad(b, s.get(YEAR, c), 4); break;
            case 'y': TimeChecks.pad(b, s.get(YEAR, c) % 100, 2); break;
            case 'm': TimeChecks.pad(b, s.get(MONTH, c), 2); break;
            case 'd': TimeChecks.pad(b, s.get(DAY, c), 2); break;
            case 'e': b.append(s.get(DAY, c)); break;
            case 'j': TimeChecks.pad(b, s.get(YEARDAY, c), 3); break;
            case 'z': s.instant(c); b.append(offsetText(Zone.OFFSET_MILLIS / 60000, "")); break;
            case 'Z': s.instant(c); b.append(Zone.NAME); break;
            case 's': b.append(s.instant(c) / 1000); break;
            default: b.append(s.instant(c)); break; // 'Q', the only one left in checkDateTime's list
        }
    }

    /** The marker is `p` for a failure whichever case is printed, as the exception names it. */
    private static void ampm(StringBuilder b, char c, Source s) {
        String text = s.get(AMPM, 'p') == 0 ? "am" : "pm";
        b.append(c == 'P' ? text.toUpperCase() : text);
    }

    /** An instant read in the default zone, in the calendar Date.toString uses. */
    private static final class Millis implements Source {
        private final long millis;
        private final long[] fields = new long[YEAR + 1];

        Millis(long millis) {
            this.millis = millis;
            long days = Math.floorDiv(millis, DAY_MILLIS);
            // The zone offset is added to the time of day, so no instant overflows.
            long ms = Math.floorMod(millis, DAY_MILLIS) + Zone.OFFSET_MILLIS;
            days += Math.floorDiv(ms, DAY_MILLIS);
            ms = Math.floorMod(ms, DAY_MILLIS);
            fillDate(days);
            fields[MILLI] = ms % 1000;
            fields[SECOND] = ms / 1000 % 60;
            fields[MINUTE] = ms / 60000 % 60;
            fields[HOUR] = ms / 3600000;
            fields[CLOCK_HOUR] = fields[HOUR] % 12 == 0 ? 12 : fields[HOUR] % 12;
            fields[AMPM] = fields[HOUR] / 12;
        }

        /** Julian before 1582-10-15, Gregorian from then on. */
        private void fillDate(long days) {
            boolean gregorian = days >= GREGORIAN_START;
            long[] ymd = gregorian ? Calendars.gregorian(days) : Calendars.julian(days);
            long year = ymd[0];
            int month = (int) ymd[1];
            boolean leap = year % 4 == 0 && (!gregorian || year % 100 != 0 || year % 400 == 0);
            int yearDay = TimeChecks.daysBefore(month, leap) + (int) ymd[2];
            // The ten days the change dropped from 1582 are not counted.
            fields[YEARDAY] = gregorian && year == 1582 ? yearDay - 10 : yearDay;
            fields[WEEKDAY] = Math.floorMod(days + 3, 7L) + 1; // 1970-01-01 was a Thursday
            fields[DAY] = ymd[2];
            fields[MONTH] = month;
            fields[YEAR] = year > 0 ? year : 1 - year;
        }

        public long get(int field, char conversion) {
            return fields[field];
        }

        public long instant(char conversion) {
            return millis;
        }

        public IllegalFormatConversionException fail(char conversion) {
            return new IllegalFormatConversionException(conversion, Long.class);
        }
    }
}
