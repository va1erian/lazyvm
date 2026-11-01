// VMARGS: -Duser.timezone=UTC
package time;

/** user.timezone=UTC names the zone %t uses for a long or a Date (docs/design/time.md, rule Z1). */
public class DefaultZoneUtc {
    public static void main(String[] args) {
        System.out.println("format: " + String.format("%tz %tH:%tM %tZ", 0L, 0L, 0L, 0L));
        System.out.println("date: " + String.format("%tc", new java.util.Date(86400000L * 200)));
        System.out.println("date string: " + new java.util.Date(0).toString().length());
    }
}
