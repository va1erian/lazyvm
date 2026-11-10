package java.util.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.IllegalFormatException;

/** Formats a record as a short text, by default two lines. */
public class SimpleFormatter extends Formatter {
    private static final String KEY = "java.util.logging.SimpleFormatter.format";
    private static final String DEFAULT = "%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp %2$s%n%4$s: %5$s%6$s%n";
    // Read when the class is first used and never again, so every formatter agrees on one format.
    private static final String FORMAT = chooseFormat();

    public SimpleFormatter() {
    }

    // A property that cannot be applied to the six arguments would make every record fail; use the default.
    private static String chooseFormat() {
        String wanted = System.getProperty(KEY);
        if (wanted == null) {
            return DEFAULT;
        }
        try {
            String.format(wanted, new Date(0L), "s", "s", "s", "s", "s");
            return wanted;
        } catch (IllegalFormatException e) {
            return DEFAULT;
        }
    }

    // The level is shown by its own name; toString is final, so a Level subclass cannot change it.
    public synchronized String format(LogRecord record) {
        String source = record.getSourceClassName();
        if (source == null) {
            source = record.getLoggerName();
        } else if (record.getSourceMethodName() != null) {
            source += " " + record.getSourceMethodName();
        }
        return String.format(FORMAT, new Date(record.getMillis()), source, record.getLoggerName(),
                record.getLevel().toString(), formatMessage(record), thrownText(record.getThrown()));
    }

    private static String thrownText(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.println();
        t.printStackTrace(out);
        out.close();
        return text.toString();
    }
}
