// VMARGS: -Duser.timezone=UTC -Djava.util.logging.SimpleFormatter.format=%d
package util;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * A format property that cannot be applied to the record's arguments (here %d on a Date) is
 * ignored: the default format is used (docs/design/logging.md, section 6).
 */
public class LoggingFormatBad {
    public static void main(String[] args) {
        System.out.println("property: " + System.getProperty("java.util.logging.SimpleFormatter.format"));
        LogRecord r = new LogRecord(Level.INFO, "still the default format");
        r.setMillis(1700000000123L);
        r.setSourceClassName("Cls");
        r.setSourceMethodName("meth");
        System.out.print(new SimpleFormatter().format(r).replace("\r\n", "\n"));
    }
}
