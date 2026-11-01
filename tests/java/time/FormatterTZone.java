// VMARGS: -Duser.timezone=GMT+01:00
package time;

import java.util.Date;

/**
 * %t of a long near its limits under a default zone that is not UTC: the zone offset is applied without
 * overflow (docs/design/time.md, rule X8).
 */
public class FormatterTZone {
    public static void main(String[] args) {
        for (long t : new long[] {Long.MAX_VALUE, Long.MAX_VALUE - 3600000L, Long.MAX_VALUE - 3599999L, Long.MIN_VALUE, Long.MIN_VALUE + 3600000L, Long.MIN_VALUE + 3599999L,
            0L, -1L, 3600000L}) {
            System.out.println(t + ": " + String.format("%tY %<tm %<td %<tH %<tM %<tS %<tL %<tz %<tZ %<tj %<tA", t));
            System.out.println(t + " Date: " + String.format("%tY %<tm %<td %<tH %<tM %<tS %<tL %<tz", new Date(t)));
            System.out.println(t + " seconds: " + String.format("%ts %<tQ", t));
        }
        System.out.println("epoch: " + String.format("%tc", 0L));
    }
}
