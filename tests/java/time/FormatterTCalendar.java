// VMARGS: -Duser.timezone=UTC
package time;

import java.util.Date;

/**
 * %t of a long or a Date around the 1582 calendar change and in Julian leap years: the day of year,
 * the day of week and the date fields follow the Gregorian rules from 1582-10-15 and the Julian rules
 * before (docs/design/time.md, rule X8).
 */
public class FormatterTCalendar {
    /** Days since 1970-01-01 in the proleptic Gregorian calendar (month 1 to 12). */
    static long epochDay(long y, long m, long d) {
        long yy = m <= 2 ? y - 1 : y;
        long era = Math.floorDiv(yy, 400L);
        long yoe = yy - era * 400;
        long doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    static long ms(int y, int m, int d, int h, int min, int s, int milli) {
        return epochDay(y, m, d) * 86400000L + ((h * 60L + min) * 60 + s) * 1000 + milli;
    }

    static void line(String label, long t) {
        System.out.println(label + ": " + String.format("%tY-%<tm-%<td %<tj %<tA %<tH:%<tM:%<tS.%<tL", t));
    }

    public static void main(String[] args) {
        // the days are given in the proleptic Gregorian calendar and read back in the hybrid one
        int[][] days = {{1500, 1, 1}, {1500, 2, 28}, {1500, 3, 1}, {1500, 3, 10}, {1500, 12, 31}, {1582, 1, 1}, {1582, 10, 3}, {1582, 10, 13}, {1582, 10, 14},
            {1582, 10, 15}, {1582, 10, 16}, {1582, 12, 31}, {1583, 1, 1}, {1583, 12, 31}, {1600, 3, 1}, {1600, 12, 31}, {1700, 2, 28}, {1700, 3, 1},
            {1700, 12, 31}, {1900, 3, 1}, {1900, 12, 31}, {1000, 3, 1}, {1000, 12, 31}, {400, 3, 1}, {400, 12, 31}, {100, 3, 1}, {100, 12, 31}, {4, 3, 1},
            {4, 12, 31}, {1, 1, 1}, {0, 12, 31}, {0, 1, 1}, {-3, 12, 31}, {-4, 3, 1}, {-100, 12, 31}, {-400, 12, 31}, {2020, 12, 31}, {2021, 12, 31}};
        for (int[] d : days) {
            line("start " + d[0] + "-" + d[1] + "-" + d[2], ms(d[0], d[1], d[2], 0, 0, 0, 0));
            line("end   " + d[0] + "-" + d[1] + "-" + d[2], ms(d[0], d[1], d[2], 23, 59, 59, 999));
        }
        // Date.toString uses the same calendar, so it agrees with %t
        for (int[] d : new int[][] {{1582, 10, 13}, {1582, 10, 14}, {1582, 10, 15}, {1500, 3, 10}, {1500, 12, 31}}) {
            long t = ms(d[0], d[1], d[2], 12, 0, 0, 0);
            System.out.println("Date " + d[0] + "-" + d[1] + "-" + d[2] + ": " + new Date(t) + " | " + String.format("%tj %<tF", new Date(t)));
        }
        // the year numbering across the era boundary: year 0 is 1 BC
        for (int y : new int[] {0, -1, -4, -99, -100, -399, -400}) {
            long t = ms(y, 6, 15, 0, 0, 0, 0);
            System.out.println("year " + y + ": " + String.format("%tY %<ty %<tC %<tB %<td %<tj", t));
        }
    }
}
