// VMARGS: -Duser.timezone=UTC
package time;

import java.io.Serializable;
import java.time.*;
import java.time.temporal.*;

/**
 * LocalDateTime carries the java.io.Serializable marker and is a TemporalAccessor; instanceof and generic bounds
 * see both, although no object stream reads or writes it (docs/design/time.md, rule M2).
 */
public class Markers {
    static <T extends Serializable> String through(T value) { return value.getClass().getSimpleName(); }

    static String accessor(TemporalAccessor t) { return t.getClass().getSimpleName(); }

    public static void main(String[] args) {
        Object ldt = LocalDateTime.of(2020, 1, 2, 3, 4);
        System.out.println("LocalDateTime Serializable: " + (ldt instanceof Serializable));
        System.out.println("LocalDateTime TemporalAccessor: " + (ldt instanceof TemporalAccessor));
        System.out.println("Month Serializable: " + ((Object) Month.MAY instanceof Serializable));
        System.out.println("Object: " + (new Object() instanceof Serializable));
        System.out.println("generic bound: " + through(LocalDateTime.of(2020, 1, 2, 3, 4)) + " " + accessor(LocalDateTime.of(2020, 1, 2, 3, 4)));
        Serializable[] all = {LocalDateTime.of(1, 1, 1, 0, 0)};
        System.out.println("array: " + all.length);
    }
}
