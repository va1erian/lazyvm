// VMARGS: -Duser.timezone=GMT+23:59
package time;

/** user.timezone=GMT+23:59 is the largest offset; the date moves a day forward (docs/design/time.md, rule Z1). */
public class DefaultZoneGmtPlus2359 {
    public static void main(String[] args) {
        System.out.println("format: " + String.format("%tz %tH:%tM %tZ %<tF", 0L, 0L, 0L, 0L));
        System.out.println("date: " + String.format("%tc", new java.util.Date(86400000L * 200)));
        System.out.println("date string: " + new java.util.Date(0));
        System.out.println("extremes: " + String.format("%tF %<tT", Long.MAX_VALUE) + " | " + String.format("%tF %<tT", Long.MIN_VALUE));
    }
}
