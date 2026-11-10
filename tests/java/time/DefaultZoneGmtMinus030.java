// VMARGS: -Duser.timezone=GMT-0:30
package time;

/** user.timezone=GMT-0:30 is minus half an hour: the sign applies to the minutes alone (docs/design/time.md, rule Z1). */
public class DefaultZoneGmtMinus030 {
    public static void main(String[] args) {
        System.out.println("format: " + String.format("%tz %tH:%tM %tZ", 0L, 0L, 0L, 0L));
        System.out.println("date string: " + new java.util.Date(0));
    }
}
