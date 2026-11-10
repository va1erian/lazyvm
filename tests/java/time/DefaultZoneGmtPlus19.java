// VMARGS: -Duser.timezone=GMT+19
package time;

/** user.timezone=GMT+19: hours above 18 are fine up to 23 (docs/design/time.md, rule Z1). */
public class DefaultZoneGmtPlus19 {
    public static void main(String[] args) {
        System.out.println("format: " + String.format("%tz %tH:%tM %tZ", 0L, 0L, 0L, 0L));
        System.out.println("date string: " + new java.util.Date(0));
    }
}
