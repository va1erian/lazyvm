// VMARGS: -Duser.timezone=UTC
package util;

import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Corners of the "{n}" message language and of the level name SimpleFormatter prints
 * (docs/design/logging.md, sections 6 and 7).
 */
public class LoggingFormatEdge {
    static final Formatter F = new Formatter() {
        public String format(LogRecord r) {
            return formatMessage(r);
        }
    };

    static class Bad {
        public String toString() {
            throw new IllegalStateException("bad toString");
        }
    }

    static class Null {
        public String toString() {
            return null;
        }
    }

    static class Named extends Level {
        Named(String name, int value) {
            super(name, value);
        }

        public String getName() {
            return "getName";
        }

        public String getLocalizedName() {
            return "getLocalizedName";
        }
    }

    static String fm(String message, Object... params) {
        LogRecord r = new LogRecord(Level.INFO, message);
        r.setParameters(params);
        return F.formatMessage(r);
    }

    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    public static void main(String[] args) {
        String[] patterns = {"{0} {+1}", "{0} {١}", "{0} {0000000000001}", "{0} {00}", "{0} {-0}", "{0} { 1}",
            "{0} {1 }", "{0} {'1'}", "{0} {+}", "{0} {-1}", "{0} {1x}", "{0} {9999}", "{0} {10000}", "{0} {10001}",
            "{0} {99999999999}", "{0} {2147483647}", "{0} {2147483648}", "{0} {}", "{0} }", "} {0}", "{0}{",
            "{0}''", "''{0}''", "'''{0}'''"};
        for (String s : patterns) {
            p("pattern [" + s + "]", fm(s, "X", "Y"));
        }
        for (String s : new String[] {"{0} {+1}", "{0} {01}", "{0} {١٢}", "{0} {10}", "{0} {0009}"}) {
            p("one parameter [" + s + "]", fm(s, "X"));
        }
        p("big doubles", fm("{0} {1} {2} {3}", 9.0e18, 9.1e18, 9.2e18, 4.0e15));
        p("grouped longs", fm("{0} {1}", 1234567890123L, -1000L));
        p("toString throws", fm("a {0} b", new Bad()));
        p("toString throws, not reached", fm("a {0} b", "X", new Bad()));
        p("toString returns null", fm("a {0} b", new Null()));
        LogRecord r = new LogRecord(new Named("custom", 1234), "m");
        r.setMillis(1700000000123L);
        p("level shown by its own name", new SimpleFormatter().format(r).split("\r?\n")[1]);
        try {
            Logger.getLogger(null, "bundle");
        } catch (RuntimeException e) {
            p("getLogger(null, bundle)", e.getClass().getName() + " " + e.getMessage());
        }
    }
}
