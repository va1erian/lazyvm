package util;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * The format property is read once, when SimpleFormatter is first used, so setting it from the
 * program works only before that (docs/design/logging.md, section 6). Nothing here may touch a
 * logger or a SimpleFormatter before the first setProperty.
 */
public class LoggingFormatLate {
    static final String KEY = "java.util.logging.SimpleFormatter.format";

    static LogRecord rec(String msg) {
        LogRecord r = new LogRecord(Level.INFO, msg);
        r.setMillis(1700000000123L);
        r.setLoggerName("ln");
        r.setSourceClassName("Cls");
        r.setSourceMethodName("meth");
        return r;
    }

    static String fmt(SimpleFormatter f, LogRecord r) {
        return f.format(r).replace("\r\n", "\n");
    }

    public static void main(String[] args) {
        System.out.println("property before: " + System.getProperty(KEY));
        System.setProperty(KEY, "FIRST %4$s/%3$s/%2$s/%5$s%n");
        SimpleFormatter early = new SimpleFormatter();
        System.out.print("early: " + fmt(early, rec("one")));
        System.setProperty(KEY, "SECOND %5$s%n");
        SimpleFormatter late = new SimpleFormatter();
        System.out.print("early after change: " + fmt(early, rec("two")));
        System.out.print("late after change: " + fmt(late, rec("three")));
        System.setProperty(KEY, "%5$s");
        System.out.print("newest: " + fmt(new SimpleFormatter(), rec("four")) + "\n");
        System.out.println("property after: " + System.getProperty(KEY));
        // The default handler of the root logger formats with the first value.
        Logger.getLogger("late.root").info("goes to stderr");
        Logger.getLogger("late.root").warning("also to stderr");
    }
}
