// VMARGS: -Duser.timezone=UTC
package time;

import java.time.*;
import java.time.temporal.*;

/** The kept part of LocalDateTime and Month: factories, getters, fields, equality, toString (docs/design/time.md, rules L1 to L5, M1). */
public class LocalDateTimes {
    interface Act { Object run() throws Throwable; }

    static void p(String l, Act a) {
        try {
            System.out.println(l + ": " + a.run());
        } catch (Throwable e) {
            System.out.println(l + ": " + e);
        }
    }

    static final ChronoField[] FIELDS = {ChronoField.NANO_OF_SECOND, ChronoField.MILLI_OF_SECOND, ChronoField.SECOND_OF_MINUTE, ChronoField.MINUTE_OF_HOUR,
        ChronoField.HOUR_OF_DAY, ChronoField.CLOCK_HOUR_OF_AMPM, ChronoField.AMPM_OF_DAY, ChronoField.DAY_OF_WEEK, ChronoField.DAY_OF_MONTH, ChronoField.DAY_OF_YEAR,
        ChronoField.MONTH_OF_YEAR, ChronoField.YEAR_OF_ERA, ChronoField.YEAR};

    static String fields(LocalDateTime t) {
        StringBuilder sb = new StringBuilder();
        for (ChronoField f : FIELDS) {
            sb.append(f).append('=').append(t.isSupported(f) ? String.valueOf(t.getLong(f)) : "-").append(' ');
        }
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        // L1: toString drops the seconds when they and the nanos are zero, and groups the fraction in 3, 6 or 9 digits
        p("toString minute", () -> LocalDateTime.of(2020, 1, 2, 3, 4));
        p("toString midnight", () -> LocalDateTime.of(2020, 1, 2, 0, 0));
        p("toString seconds", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 5));
        p("toString seconds zero nano", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 0, 0));
        p("toString nano 1", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 5, 1));
        p("toString nano 1000", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 5, 1000));
        p("toString nano 1000000", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 5, 1000000));
        p("toString nano 120000000", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 5, 120000000));
        p("toString nano 123456000", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123456000));
        p("toString nano 123456789", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123456789));
        p("toString nano 999999999", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 5, 999999999));
        p("toString nano no seconds", () -> LocalDateTime.of(2020, 1, 2, 3, 4, 0, 7));
        p("toString years", () -> LocalDateTime.of(5, 1, 2, 0, 0) + " " + LocalDateTime.of(0, 1, 2, 0, 0) + " " + LocalDateTime.of(-5, 1, 2, 0, 0) + " "
            + LocalDateTime.of(9999, 12, 31, 23, 59, 59) + " " + LocalDateTime.of(10000, 1, 1, 0, 0) + " " + LocalDateTime.of(-10000, 1, 1, 0, 0) + " "
            + LocalDateTime.of(999999999, 12, 31, 23, 59, 59, 999999999) + " " + LocalDateTime.of(-999999999, 1, 1, 0, 0));
        // L2: the factories check year, month, day, then hour, minute, second, nano
        p("of Month", () -> LocalDateTime.of(2020, Month.FEBRUARY, 29, 1, 2) + " " + LocalDateTime.of(2020, Month.FEBRUARY, 29, 1, 2, 3)
            + " " + LocalDateTime.of(2020, Month.FEBRUARY, 29, 1, 2, 3, 4));
        p("of int", () -> LocalDateTime.of(2020, 2, 29, 1, 2) + " " + LocalDateTime.of(2020, 2, 29, 1, 2, 3) + " " + LocalDateTime.of(2020, 2, 29, 1, 2, 3, 4));
        p("of Month null", () -> LocalDateTime.of(2020, null, 1, 0, 0));
        p("of year hi", () -> LocalDateTime.of(1000000000, 1, 1, 0, 0));
        p("of year lo", () -> LocalDateTime.of(-1000000000, 1, 1, 0, 0));
        p("of month 13", () -> LocalDateTime.of(2020, 13, 1, 0, 0));
        p("of month 0", () -> LocalDateTime.of(2020, 0, 1, 0, 0));
        p("of day 32", () -> LocalDateTime.of(2020, 1, 32, 0, 0));
        p("of day 0", () -> LocalDateTime.of(2020, 1, 0, 0, 0));
        p("of Feb 29 2021", () -> LocalDateTime.of(2021, 2, 29, 0, 0));
        p("of Feb 29 1900", () -> LocalDateTime.of(1900, 2, 29, 0, 0));
        p("of Feb 29 2000", () -> LocalDateTime.of(2000, 2, 29, 0, 0));
        p("of Feb 29 -4", () -> LocalDateTime.of(-4, 2, 29, 0, 0));
        p("of Feb 29 0", () -> LocalDateTime.of(0, 2, 29, 0, 0));
        p("of Feb 29 -1", () -> LocalDateTime.of(-1, 2, 29, 0, 0));
        p("of Feb 30", () -> LocalDateTime.of(2020, 2, 30, 0, 0));
        p("of Apr 31", () -> LocalDateTime.of(2020, 4, 31, 0, 0));
        p("of Sep 31", () -> LocalDateTime.of(2020, 9, 31, 0, 0));
        p("of Month Apr 31", () -> LocalDateTime.of(2020, Month.APRIL, 31, 0, 0));
        p("of Month Feb 30", () -> LocalDateTime.of(2021, Month.FEBRUARY, 30, 0, 0));
        p("of hour 24", () -> LocalDateTime.of(2020, 1, 1, 24, 0));
        p("of hour -1", () -> LocalDateTime.of(2020, 1, 1, -1, 0));
        p("of minute 60", () -> LocalDateTime.of(2020, 1, 1, 0, 60));
        p("of second 60", () -> LocalDateTime.of(2020, 1, 1, 0, 0, 60));
        p("of nano -1", () -> LocalDateTime.of(2020, 1, 1, 0, 0, 0, -1));
        p("of nano 1e9", () -> LocalDateTime.of(2020, 1, 1, 0, 0, 0, 1000000000));
        p("of order month first", () -> LocalDateTime.of(2020, 13, 99, 99, 99, 99, 99));
        p("of order year first", () -> LocalDateTime.of(1000000000, 13, 99, 99, 99, 99, 99));
        p("of order day before hour", () -> LocalDateTime.of(2020, 1, 99, 99, 99, 99, 99));
        p("of order hour before minute", () -> LocalDateTime.of(2020, 1, 1, 99, 99, 99, 99));
        p("of order minute before second", () -> LocalDateTime.of(2020, 1, 1, 1, 99, 99, 99));
        p("of order second before nano", () -> LocalDateTime.of(2020, 1, 1, 1, 1, 99, 99));
        p("of calendar before hour", () -> LocalDateTime.of(2021, 2, 29, 99, 0));
        // L3: getters and fields
        LocalDateTime t = LocalDateTime.of(2020, 12, 25, 15, 4, 5, 123456789);
        p("getters", () -> t.getYear() + " " + t.getMonth() + " " + t.getMonthValue() + " " + t.getDayOfMonth() + " " + t.getHour() + " " + t.getMinute() + " "
            + t.getSecond() + " " + t.getNano());
        p("getters negative year", () -> LocalDateTime.of(-5, 2, 1, 0, 0).getYear());
        p("fields afternoon", () -> fields(t));
        p("fields midnight", () -> fields(LocalDateTime.of(2021, 1, 1, 0, 0)));
        p("fields noon", () -> fields(LocalDateTime.of(2021, 7, 4, 12, 0)));
        p("fields leap day", () -> fields(LocalDateTime.of(2020, 2, 29, 23, 59, 59, 999999999)));
        p("fields year end", () -> fields(LocalDateTime.of(2021, 12, 31, 1, 0)));
        p("fields year 5", () -> fields(LocalDateTime.of(5, 3, 1, 0, 0)));
        p("fields year 0", () -> fields(LocalDateTime.of(0, 3, 1, 0, 0)));
        p("fields year -5", () -> fields(LocalDateTime.of(-5, 3, 1, 0, 0)));
        p("fields thursday", () -> fields(LocalDateTime.of(1970, 1, 1, 0, 0)));
        p("fields sunday", () -> fields(LocalDateTime.of(2020, 1, 5, 0, 0)));
        p("fields 1900 march", () -> fields(LocalDateTime.of(1900, 3, 1, 0, 0)));
        p("fields 2000 march", () -> fields(LocalDateTime.of(2000, 3, 1, 0, 0)));
        p("getLong null", () -> t.getLong(null));
        p("isSupported null", () -> t.isSupported((TemporalField) null));
        // L4: equality and hash
        p("equals", () -> t.equals(LocalDateTime.of(2020, 12, 25, 15, 4, 5, 123456789)) + " " + t.equals(LocalDateTime.of(2020, 12, 25, 15, 4, 5, 123456788))
            + " " + t.equals(null) + " " + t.equals("x") + " " + t.equals(t));
        p("hashCode equal values", () -> t.hashCode() == LocalDateTime.of(2020, 12, 25, 15, 4, 5, 123456789).hashCode() ? "same" : "different");
        p("hashCode equal values, negative year", () -> LocalDateTime.of(-5, 1, 1, 0, 0).hashCode() == LocalDateTime.of(-5, 1, 1, 0, 0).hashCode() ? "same" : "different");
        // Month (M1)
        p("Month values", () -> java.util.Arrays.toString(Month.values()));
        p("Month getValue", () -> Month.JANUARY.getValue() + " " + Month.MAY.getValue() + " " + Month.DECEMBER.getValue());
        p("Month of", () -> Month.of(1) + " " + Month.of(6) + " " + Month.of(12));
        p("Month of 0", () -> Month.of(0));
        p("Month of 13", () -> Month.of(13));
        p("Month of -1", () -> Month.of(-1));
        p("Month of max", () -> Month.of(Integer.MAX_VALUE));
        p("Month valueOf", () -> Month.valueOf("DECEMBER") + " " + Month.DECEMBER.ordinal() + " " + Month.DECEMBER.name() + " " + Month.DECEMBER.compareTo(Month.JANUARY));
        p("Month valueOf bad", () -> Month.valueOf("December"));
    }
}
