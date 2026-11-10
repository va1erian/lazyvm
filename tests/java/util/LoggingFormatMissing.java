// VMARGS: -Duser.timezone=UTC -Djava.util.logging.SimpleFormatter.format=%7$s
package util;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * The formatter receives six values after the format (date, source, logger, level, message,
 * thrown); a property that names a seventh is unusable, so the default format applies
 * (docs/design/logging.md, section 6).
 */
public class LoggingFormatMissing {
    public static void main(String[] args) {
        LogRecord r = new LogRecord(Level.WARNING, "seventh argument does not exist");
        r.setMillis(1700000000123L);
        r.setLoggerName("some.logger");
        System.out.print(new SimpleFormatter().format(r).replace("\r\n", "\n"));
    }
}
