// VMARGS: -Duser.timezone=GMT+05:00
package time;

/**
 * The default zone is read once: changing the user.timezone property afterwards changes nothing
 * (docs/design/time.md, rule Z1).
 */
public class DefaultZoneCached {
    public static void main(String[] args) {
        System.out.println("first %tH: " + String.format("%tH", 0L));
        System.setProperty("user.timezone", "GMT+07:00");
        System.out.println("after setProperty %tH: " + String.format("%tH", 0L));
        System.out.println("after setProperty %tZ: " + String.format("%tZ", 0L));
        System.setProperty("user.timezone", "UTC");
    }
}
