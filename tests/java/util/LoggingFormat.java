// VMARGS: -Duser.timezone=UTC
package util;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/** SimpleFormatter's default format and Formatter.formatMessage (docs/design/logging.md, sections 6 and 7). */
public class LoggingFormat {
    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    static LogRecord rec(Level level, String msg, Object... params) {
        LogRecord r = new LogRecord(level, msg);
        r.setMillis(1700000000123L); // 2023-11-14 22:13:20.123 UTC
        if (params.length > 0) {
            r.setParameters(params);
        }
        return r;
    }

    static String fmt(Formatter f, LogRecord r) {
        return f.format(r).replace("\r\n", "\n");
    }

    static void line(String label, LogRecord r) {
        System.out.print(label + ":\n" + fmt(new SimpleFormatter(), r) + "--\n");
    }

    static class Plain extends Formatter {
        public String format(LogRecord r) {
            return "<" + formatMessage(r) + ">";
        }
    }

    static class Heads extends Formatter {
        public String format(LogRecord r) {
            return "x";
        }

        public String getHead(Handler h) {
            return "head";
        }
    }

    public static void main(String[] args) {
        defaultFormat();
        sources();
        times();
        thrown();
        messages();
        numbers();
        quotes();
        broken();
        base();
    }

    static void defaultFormat() {
        LogRecord r = rec(Level.INFO, "hello");
        r.setLoggerName("my.log");
        r.setSourceClassName("a.B");
        r.setSourceMethodName("go");
        String s = new SimpleFormatter().format(r);
        p("raw ends with line separator", s.endsWith(System.lineSeparator()));
        p("raw line count", s.split("\r?\n").length);
        line("full", r);
        line("severe", rec(Level.SEVERE, "bad"));
        line("custom level", rec(new Level("Mixed Case", 1234) {}, "custom"));
        line("OFF", rec(Level.OFF, "off"));
        line("FINEST", rec(Level.FINEST, "finest"));
        line("multi-line message", rec(Level.INFO, "one\ntwo"));
        line("percent in message", rec(Level.INFO, "100% sure %s %d"));
    }

    static void sources() {
        LogRecord r = rec(Level.INFO, "m");
        line("no names", r);
        r.setLoggerName("my.log");
        line("logger name only", r);
        r.setSourceClassName("a.B");
        line("class, no method", r);
        r.setSourceMethodName("go");
        line("class and method", r);
        r.setSourceClassName(null);
        line("method, no class", r);
        r.setSourceMethodName(null);
        r.setLoggerName("");
        line("empty logger name", r);
        r.setSourceClassName("");
        line("empty class", r);
        r.setSourceMethodName("");
        line("empty class and method", r);
    }

    static void times() {
        LogRecord r = rec(Level.WARNING, "t");
        r.setLoggerName("t");
        long[] ms = {0L, -1L, 43200000L, 43200000L - 1000, 43199999L, 86399999L, 1700000000123L - 11L * 3600 * 1000,
            1700000000123L - 17L * 3600 * 1000, 1700000000123L - 9L * 3600 * 1000, 1700000000123L - 21L * 3600 * 1000,
            951782400000L, 1709208000000L, 253402300799999L, -62135769600000L};
        for (long m : ms) {
            r.setMillis(m);
            System.out.print(m + " -> " + fmt(new SimpleFormatter(), r).split("\n")[0] + "\n");
        }
    }

    static Exception chain() {
        Exception e = new Exception("top", new RuntimeException("cause"));
        e.setStackTrace(new StackTraceElement[] {new StackTraceElement("A", "m", "A.java", 3),
            new StackTraceElement("B", "n", null, -1), new StackTraceElement("C", "native", "C.java", -2)});
        e.getCause().setStackTrace(new StackTraceElement[] {new StackTraceElement("D", "o", "D.java", 9)});
        e.addSuppressed(new Error("sup"));
        e.getSuppressed()[0].setStackTrace(new StackTraceElement[0]);
        return e;
    }

    static void thrown() {
        LogRecord r = rec(Level.SEVERE, "oops");
        r.setLoggerName("x");
        Exception plain = new Exception("plain");
        plain.setStackTrace(new StackTraceElement[] {new StackTraceElement("p.Q", "r", "Q.java", 1)});
        r.setThrown(plain);
        line("thrown", r);
        r.setThrown(chain());
        line("thrown chain", r);
        Exception noMessage = new IllegalStateException();
        noMessage.setStackTrace(new StackTraceElement[0]);
        r.setThrown(noMessage);
        line("thrown no trace", r);
        r.setThrown(null);
        line("thrown cleared", r);
    }

    static void messages() {
        Formatter f = new Plain();
        p("null message", f.formatMessage(rec(Level.INFO, null)));
        p("null message stays null", f.formatMessage(rec(Level.INFO, null)) == null);
        p("null message with params stays null", f.formatMessage(rec(Level.INFO, null, "A")) == null);
        p("empty message", "[" + f.formatMessage(rec(Level.INFO, "")) + "]");
        p("no params", f.formatMessage(rec(Level.INFO, "a {0} b")));
        p("{0}", f.formatMessage(rec(Level.INFO, "a {0} b", "X")));
        p("{1} missing", f.formatMessage(rec(Level.INFO, "a {1} b", "X")));
        p("{1}", f.formatMessage(rec(Level.INFO, "a {1} b", "X", "Y")));
        p("order", f.formatMessage(rec(Level.INFO, "{1}{0}{1}", "X", "Y")));
        p("{2}", f.formatMessage(rec(Level.INFO, "{2}", "A", "B", "C")));
        p("{3}", f.formatMessage(rec(Level.INFO, "{3}", "A", "B", "C", "D", "E")));
        p("{4} alone is not substituted", f.formatMessage(rec(Level.INFO, "{4}", "A", "B", "C", "D", "E")));
        p("{4} with {0}", f.formatMessage(rec(Level.INFO, "{0} {4}", "A", "B", "C", "D", "E")));
        p("{5} alone", f.formatMessage(rec(Level.INFO, "{5}", "A", "B", "C", "D", "E", "F")));
        p("{10} with {1}", f.formatMessage(rec(Level.INFO, "{1} {10}", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")));
        p("space in braces", f.formatMessage(rec(Level.INFO, "{ 0}", "A")));
        p("null param", f.formatMessage(rec(Level.INFO, "v={0}", new Object[] {null})));
        p("empty params", f.formatMessage(rec(Level.INFO, "x {0}", new Object[0])));
        p("param no marker", f.formatMessage(rec(Level.INFO, "it's {x}", "A")));
        p("same twice", f.formatMessage(rec(Level.INFO, "{0}{0}{0}", "ab")));
        p("adjacent", f.formatMessage(rec(Level.INFO, "{0}{1}", "a", "b")));
        p("braces text", f.formatMessage(rec(Level.INFO, "} {0} } {", "A")));
        p("dollar and backslash", f.formatMessage(rec(Level.INFO, "${0} \\ {0}", "X")));
        p("object toString", f.formatMessage(rec(Level.INFO, "v={0}", new Object() {
            public String toString() {
                return "OBJ";
            }
        })));
        p("boolean char string", f.formatMessage(rec(Level.INFO, "{0} {1} {2}", true, 'c', "s")));
        p("string that looks numeric", f.formatMessage(rec(Level.INFO, "{0}", "1234567")));
        p("unicode", f.formatMessage(rec(Level.INFO, "é{0}€", "ü")));
        p("array length", f.formatMessage(rec(Level.INFO, "v={0}", new int[3].length)));
        p("format uses formatMessage", f.format(rec(Level.INFO, "m {0}", 1)));
    }

    static void numbers() {
        Formatter f = new Plain();
        p("int", f.formatMessage(rec(Level.INFO, "{0}|{1}|{2}|{3}", 0, 12, 1234, 1234567)));
        p("negative int", f.formatMessage(rec(Level.INFO, "{0}|{1}", -1, -1234567)));
        p("long", f.formatMessage(rec(Level.INFO, "{0}|{1}", -9876543210L, Long.MAX_VALUE)));
        p("int extremes", f.formatMessage(rec(Level.INFO, "{0}|{1}", Integer.MIN_VALUE, Integer.MAX_VALUE)));
        p("short byte", f.formatMessage(rec(Level.INFO, "{0} {1}", (short) 4000, (byte) -5)));
        p("double", f.formatMessage(rec(Level.INFO, "{0}|{1}|{2}|{3}", 1.5, 2.0, 0.1234567, 1234.56789)));
        p("double more", f.formatMessage(rec(Level.INFO, "{0}|{1}|{2}|{3}", -0.5, 1e10, 0.001, 100.25)));
        p("float", f.formatMessage(rec(Level.INFO, "{0}|{1}", 3.25f, 0.5f)));
        p("negative zero", f.formatMessage(rec(Level.INFO, "{0}", -0.0)));
        p("small rounds to zero", f.formatMessage(rec(Level.INFO, "{0}|{1}", 0.0001, 0.00049)));
        p("rounds up", f.formatMessage(rec(Level.INFO, "{0}|{1}|{2}", 0.9996, 999.9996, 2.0004)));
        p("whole double", f.formatMessage(rec(Level.INFO, "{0}|{1}", 1000000.0, 123456789.0)));
        p("exact ties round half even", f.formatMessage(rec(Level.INFO, "{0}|{1}|{2}|{3}|{4}", 0.0625, 0.1875, 0.5625, 0.4375, 1234.5625)));
        p("large", f.formatMessage(rec(Level.INFO, "{0}|{1}", 1e15, 123456789012.0)));
        p("char is not a number", f.formatMessage(rec(Level.INFO, "{0}", (char) 65)));
    }

    static void quotes() {
        Formatter f = new Plain();
        p("apostrophe starts quote", f.formatMessage(rec(Level.INFO, "it's {0}", "X")));
        p("no marker: no quote processing", f.formatMessage(rec(Level.INFO, "it's")));
        p("no marker with params", f.formatMessage(rec(Level.INFO, "it's no marker", "X")));
        p("doubled", f.formatMessage(rec(Level.INFO, "it''s {0}", "X")));
        p("quoted brace", f.formatMessage(rec(Level.INFO, "'{0}' {0}", "X")));
        p("quote closes", f.formatMessage(rec(Level.INFO, "a'b{0}c'd {1}", "X", "Y")));
        p("unterminated quote", f.formatMessage(rec(Level.INFO, "{0} 'rest {1}", "X", "Y")));
        p("quote only", f.formatMessage(rec(Level.INFO, "'{0}", "X")));
        p("two quotes", f.formatMessage(rec(Level.INFO, "{0} '' {1}", "X", "Y")));
        p("quoted quote", f.formatMessage(rec(Level.INFO, "'a''b' {0}", "X")));
    }

    static void broken() {
        Formatter f = new Plain();
        p("unclosed", f.formatMessage(rec(Level.INFO, "{0", "A")));
        p("unclosed later", f.formatMessage(rec(Level.INFO, "x {0} {", "A")));
        p("not a number", f.formatMessage(rec(Level.INFO, "{0} {abc}", "A")));
        p("negative index", f.formatMessage(rec(Level.INFO, "{-1} {0}", "A")));
        p("empty braces", f.formatMessage(rec(Level.INFO, "{} {0}", "A")));
        p("nested braces", f.formatMessage(rec(Level.INFO, "{{0}}", "A")));
    }

    static void base() {
        Formatter f = new Plain();
        p("head and tail default", "[" + f.getHead(null) + "][" + f.getTail(null) + "]");
        p("simple head and tail", "[" + new SimpleFormatter().getHead(null) + "][" + new SimpleFormatter().getTail(null) + "]");
        Formatter h = new Heads();
        p("head overridden", h.getHead(null) + " [" + h.getTail(null) + "]");
        p("format", h.format(rec(Level.INFO, "m")));
    }
}
