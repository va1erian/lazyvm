// VMARGS: -Djava.util.logging.SimpleFormatter.format=[%4$s]-%3$s-%2$s-%5$s%6$s%n
package util;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The root logger's default ConsoleHandler writes to stderr, and the format property chooses the
 * line (docs/design/logging.md, sections 3, 9 and 10). Stdout and stderr are compared separately.
 */
public class LoggingRoot {
    static class Quiet extends Exception {
        Quiet(String m) {
            super(m);
            setStackTrace(new StackTraceElement[] {new StackTraceElement("q.Z", "z", "Z.java", 5)});
        }
    }

    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    void instance(Logger l) {
        l.info("from an instance method");
    }

    public static void main(String[] args) {
        p("property", System.getProperty("java.util.logging.SimpleFormatter.format"));
        Logger l = Logger.getLogger("root.test");
        l.info("plain");
        l.warning("warn");
        l.severe("severe");
        l.config("config is below the handler's INFO");
        l.fine("fine is below the logger's inherited INFO");
        l.log(Level.INFO, "params {0} and {1}", new Object[] {"a", 2});
        l.log(Level.INFO, "one param {0}", 1234567);
        l.log(Level.INFO, "thrown", new Quiet("boom"));
        l.logp(Level.INFO, "Given.Class", "givenMethod", "explicit source");
        l.logp(Level.INFO, "Only.Class", null, "explicit class");
        new LoggingRoot().instance(l);
        Logger.getLogger("").logp(Level.INFO, "R", "r", "on the root logger itself");
        Logger.getAnonymousLogger().info("anonymous: name is null");
        Logger.getGlobal().info("global");
        l.entering("C", "m");

        l.setLevel(Level.ALL);
        l.fine("logger ALL, handler INFO: still hidden");
        Logger root = Logger.getLogger("");
        Handler h = root.getHandlers()[0];
        h.setLevel(Level.ALL);
        l.fine("handler ALL: shown");
        l.entering("C", "m");
        l.finest("finest shown");
        h.setLevel(Level.SEVERE);
        l.warning("handler SEVERE: hidden");
        l.severe("handler SEVERE: shown");
        h.setLevel(Level.INFO);

        l.setUseParentHandlers(false);
        l.info("hidden: useParentHandlers is false");
        l.addHandler(new Handler() {
            public void publish(LogRecord r) {
                System.out.println("own handler: " + r.getMessage());
            }

            public void flush() {}

            public void close() {}
        });
        l.info("own handler only");
        l.setUseParentHandlers(true);
        l.info("both");

        root.setLevel(Level.WARNING);
        Logger.getLogger("root.other").info("root WARNING hides INFO");
        Logger.getLogger("root.other").warning("root WARNING shows WARNING");
        root.setLevel(Level.OFF);
        Logger.getLogger("root.other").severe("root OFF hides everything");
        root.setLevel(Level.INFO);
        root.removeHandler(h);
        Logger.getLogger("root.other").severe("no handler on the root: nothing");
        p("end", root.getHandlers().length);
    }
}
