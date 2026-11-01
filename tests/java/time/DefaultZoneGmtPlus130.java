// VMARGS: -Duser.timezone=GMT+130
package time;

/** user.timezone=GMT+130 is the offset +01:30: the digit form with a one-digit hour (docs/design/time.md, rule Z1). */
public class DefaultZoneGmtPlus130 {
    public static void main(String[] args) {
        System.out.println("format: " + String.format("%tz %tH:%tM %tZ", 0L, 0L, 0L, 0L));
        System.out.println("date: " + String.format("%tc", new java.util.Date(86400000L * 200)));
        System.out.println("date string: " + new java.util.Date(0));
    }
}
