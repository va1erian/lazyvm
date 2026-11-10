package util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Subclasses of Logger: every logging method ends in log(LogRecord), and the publication walk reads the
 * tree through the public getters, but the level and the filter from the logger's own fields
 * (docs/design/logging.md, sections 1 and 3).
 */
public class LoggingSubclass {
    static class L extends Logger {
        L(String name) {
            super(name, null);
        }
    }

    static class Cap extends Handler {
        final List<String> got = new ArrayList<>();

        public void publish(LogRecord r) {
            got.add(r.getLevel() + ":" + r.getMessage());
        }

        public void flush() {}

        public void close() {}
    }

    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    static Cap capture(Logger l) {
        Cap c = new Cap();
        l.addHandler(c);
        l.setUseParentHandlers(false);
        return c;
    }

    public static void main(String[] args) {
        overrideLog();
        walkGetters();
        fieldsNotGetters();
        globalLogger();
    }

    static void overrideLog() {
        List<String> seen = new ArrayList<>();
        Logger over = new L("sub.over") {
            public void log(LogRecord r) {
                seen.add(r.getLevel() + ":" + r.getMessage());
            }
        };
        over.setLevel(Level.ALL);
        over.info("i");
        over.log(Level.WARNING, "w");
        over.log(Level.INFO, "p", 1);
        over.log(Level.INFO, "arr", new Object[] {1, 2});
        over.log(Level.INFO, "t", new Error());
        over.logp(Level.FINE, "c", "m", "lp");
        over.entering("c", "m");
        over.entering("c", "m", "x");
        over.exiting("c", "m");
        over.throwing("c", "m", new Error());
        over.fine(() -> "supplier");
        over.log(Level.CONFIG, () -> "supplier 2");
        over.log(Level.CONFIG, new Error(), () -> "supplier 3");
        over.logp(Level.FINEST, "c", "m", () -> "supplier 4");
        p("log(LogRecord) override saw", seen);
        over.setLevel(Level.OFF);
        over.severe("hidden");
        p("level test happens before log(LogRecord)", seen.size());
    }

    static void walkGetters() {
        Logger par = new L("sub.par");
        Cap parent = capture(par);
        Logger viaParent = new L("sub.gp") {
            public Logger getParent() {
                return par;
            }
        };
        viaParent.info("x");
        p("getParent override walk", parent.got);

        Cap other = new Cap();
        Logger viaHandlers = new L("sub.gh") {
            public Handler[] getHandlers() {
                return new Handler[] {other};
            }
        };
        viaHandlers.setUseParentHandlers(false);
        viaHandlers.info("x");
        p("getHandlers override walk", other.got);

        Logger noParent = new L("sub.gu") {
            public boolean getUseParentHandlers() {
                return false;
            }
        };
        noParent.setParent(par);
        parent.got.clear();
        noParent.info("x");
        p("getUseParentHandlers override walk", parent.got);
    }

    static void fieldsNotGetters() {
        Logger par = new L("sub.fpar");
        Cap parent = capture(par);
        Logger gf = new L("sub.gf") {
            public java.util.logging.Filter getFilter() {
                return r -> false;
            }
        };
        gf.setParent(par);
        gf.info("x");
        p("getFilter override is not asked", parent.got);
        parent.got.clear();
        Logger gl = new L("sub.gl") {
            public Level getLevel() {
                return Level.OFF;
            }
        };
        gl.setParent(par);
        gl.info("x");
        p("getLevel override is not asked", parent.got);
        parent.got.clear();
        Logger gi = new L("sub.gi") {
            public boolean isLoggable(Level l) {
                return false;
            }
        };
        gi.setParent(par);
        gi.info("x");
        gi.log(new LogRecord(Level.SEVERE, "y"));
        p("isLoggable override is asked", parent.got);
        Logger gn = new L("sub.gn") {
            public String getName() {
                return "other";
            }
        };
        Cap names = new Cap() {
            public void publish(LogRecord r) {
                got.add(r.getLoggerName());
            }
        };
        gn.addHandler(names);
        gn.setUseParentHandlers(false);
        gn.info("x");
        p("record carries the name given at construction", names.got);
    }

    static void globalLogger() {
        List<String> names = Collections.list(LogManager.getLogManager().getLoggerNames());
        Collections.sort(names);
        p("names at start", names);
        p("manager finds global", LogManager.getLogManager().getLogger("global") == Logger.global);
        p("global parent", Logger.global.getParent() == Logger.getLogger(""));
        Logger.global.setParent(new L("sub.gparent"));
        p("getGlobal keeps an explicit parent", Logger.getGlobal().getParent().getName());
        Logger.global.setParent(Logger.getLogger(""));
        p("addLogger named global", LogManager.getLogManager().addLogger(new L("global")));
        int[] closed = {0};
        Logger.global.addHandler(new Handler() {
            public void publish(LogRecord r) {}

            public void flush() {}

            public void close() {
                closed[0]++;
            }
        });
        LogManager.getLogManager().reset();
        p("reset closes the global logger's handlers", closed[0]);
    }
}
