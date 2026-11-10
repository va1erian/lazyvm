package util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Which overloads entering, exiting and throwing go through, as a subclass overriding logp or log sees
 * them (docs/design/logging.md, section 5, M4).
 */
public class LoggingTrace {
    static final List<String> calls = new ArrayList<>();

    static class Spy extends Logger {
        Spy() {
            super("trace.spy", null);
        }

        public void logp(Level l, String c, String m, String msg) {
            calls.add("logp(String) " + l + " " + msg);
            super.logp(l, c, m, msg);
        }

        public void logp(Level l, String c, String m, String msg, Object p) {
            calls.add("logp(Object) " + l + " " + msg);
            super.logp(l, c, m, msg, p);
        }

        public void logp(Level l, String c, String m, String msg, Object[] ps) {
            calls.add("logp(Object[]) " + l + " " + msg);
            super.logp(l, c, m, msg, ps);
        }

        public void logp(Level l, String c, String m, String msg, Throwable t) {
            calls.add("logp(Throwable) " + l + " " + msg);
            super.logp(l, c, m, msg, t);
        }

        public void log(LogRecord r) {
            calls.add("log(LogRecord) " + r.getLevel() + " " + r.getMessage() + " " + r.getSourceMethodName());
            super.log(r);
        }
    }

    static void show(String label) {
        System.out.println(label + ": " + calls);
        calls.clear();
    }

    public static void main(String[] args) {
        Logger l = new Spy();
        l.setLevel(Level.FINER);
        l.entering("C", "m");
        show("entering");
        l.entering("C", "m", "x");
        show("entering one");
        l.entering("C", "m", new Object[] {1, 2});
        show("entering array");
        l.entering("C", "m", (Object[]) null);
        show("entering null array");
        l.entering("C", "m", new Object[0]);
        show("entering empty array");
        l.exiting("C", "m");
        show("exiting");
        l.exiting("C", "m", "r");
        show("exiting result");
        l.throwing("C", "m", new Error());
        show("throwing");
        l.setLevel(Level.FINE);
        l.entering("C", "m");
        l.entering("C", "m", "x");
        l.entering("C", "m", new Object[] {1});
        l.entering("C", "m", (Object[]) null);
        l.exiting("C", "m");
        l.exiting("C", "m", "r");
        l.throwing("C", "m", new Error());
        show("hidden at FINE");
        l.setLevel(Level.FINER);
        l.entering("C", "m", new Object[] {1, 2, 3});
        calls.clear();
        System.out.println(Arrays.toString(new String[] {"done"}));
    }
}
