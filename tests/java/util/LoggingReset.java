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
 * LogManager.reset closes every handler with no lock held, so a handler may create loggers or wait for
 * another thread that uses the logging classes, and one that throws does not stop the others
 * (docs/design/logging.md, section 11).
 */
public class LoggingReset {
    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    static class Plain extends Handler {
        public void publish(LogRecord r) {}

        public void flush() {}

        public void close() {}
    }

    public static void main(String[] args) throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<String>());
        Logger a = Logger.getLogger("rs.a");
        Logger b = Logger.getLogger("rs.b");
        Logger c = Logger.getLogger("rs.c");
        Logger d = Logger.getLogger("rs.d");
        a.setLevel(Level.FINE);
        a.addHandler(new Plain() {
            public void close() {
                events.add("a handlers=" + a.getHandlers().length + " level=" + a.getLevel());
                for (int i = 0; i < 50; i++) {
                    Logger.getLogger("rs.made." + i);
                }
            }
        });
        b.addHandler(new Plain() {
            public void close() {
                events.add("b throws");
                throw new RuntimeException("close failed");
            }
        });
        c.addHandler(new Plain() {
            public void close() {
                events.add("c");
            }
        });
        d.addHandler(new Plain() {
            public void close() {
                Thread t = new Thread(() -> {
                    Logger.getLogger("rs.from.thread");
                    LogManager.getLogManager().getLogger("rs.d");
                    events.add("thread done");
                });
                t.start();
                try {
                    t.join();
                } catch (InterruptedException e) {
                    events.add("interrupted");
                }
                events.add("d joined");
            }
        });
        Logger anon = Logger.getAnonymousLogger();
        Handler anonHandler = new Plain() {
            public void close() {
                events.add("anonymous closed");
            }
        };
        anon.addHandler(anonHandler);
        LogManager.getLogManager().reset();
        Collections.sort(events);
        p("events", events);
        p("handlers gone", a.getHandlers().length + " " + b.getHandlers().length + " " + c.getHandlers().length + " "
                + d.getHandlers().length);
        p("levels", a.getLevel() + " " + Logger.getLogger("").getLevel());
        p("anonymous untouched", anon.getHandlers().length);
        p("made in close", LogManager.getLogManager().getLogger("rs.made.49") != null);
        p("made on the thread", LogManager.getLogManager().getLogger("rs.from.thread") != null);
        p("second reset closes nothing more", closeAgain(events));
    }

    static int closeAgain(List<String> events) {
        int before = events.size();
        LogManager.getLogManager().reset();
        return events.size() - before;
    }
}
