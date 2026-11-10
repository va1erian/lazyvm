// VMARGS: -Duser.timezone=UTC -Djava.util.logging.SimpleFormatter.format=%1$tc|%1$tF|%1$tT.%1$tL|%1$tZ|%4$s|%2$s|%3$s|%5$s|%6$s%n
package util;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/** The six arguments of a custom format, in order (docs/design/logging.md, section 6). */
public class LoggingFormatDate {
    static void show(LogRecord r) {
        System.out.print(new SimpleFormatter().format(r).replace("\r\n", "\n") + "--\n");
    }

    public static void main(String[] args) {
        LogRecord r = new LogRecord(Level.CONFIG, "msg {0}");
        r.setMillis(1700000000123L);
        r.setParameters(new Object[] {"param"});
        show(r);
        r.setLoggerName("log.name");
        show(r);
        r.setSourceClassName("a.B");
        show(r);
        r.setSourceMethodName("go");
        show(r);
        r.setMillis(86399999L);
        r.setMessage(null);
        show(r);
        Exception e = new Exception("t");
        e.setStackTrace(new StackTraceElement[0]);
        r.setThrown(e);
        show(r);
    }
}
