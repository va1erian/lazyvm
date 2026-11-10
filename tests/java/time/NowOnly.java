// VMARGS: -Duser.timezone=UTC
package time;

import java.time.*;

/**
 * LocalDateTime.now() runs; the real clock decides nothing that is printed (docs/design/time.md, rule N1).
 */
public class NowOnly {
    public static void main(String[] args) {
        LocalDateTime a = LocalDateTime.now();
        LocalDateTime b = LocalDateTime.now();
        System.out.println("class: " + a.getClass().getSimpleName());
        System.out.println("non-null: " + (a != null) + " " + (b != null) + " " + (a.getMonth() != null));
        System.out.println("month in range: " + (a.getMonthValue() >= 1 && a.getMonthValue() <= 12) + " " + (a.getMonth().getValue() == a.getMonthValue()));
        System.out.println("fields in range: " + (a.getDayOfMonth() >= 1 && a.getDayOfMonth() <= 31) + " " + (a.getHour() >= 0 && a.getHour() < 24)
            + " " + (a.getMinute() >= 0 && a.getMinute() < 60) + " " + (a.getSecond() >= 0 && a.getSecond() < 60) + " " + (a.getNano() >= 0 && a.getNano() < 1000000000));
        System.out.println("round trip: " + LocalDateTime.parse(a.toString()).equals(a));
        System.out.println("round trip 2: " + LocalDateTime.parse(b.toString()).equals(b));
    }
}
