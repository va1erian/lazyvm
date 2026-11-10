package util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** LogRecord, the Logger log methods and source inference (docs/design/logging.md, sections 4 and 5). */
public class LoggingRecords {
    interface Op {
        Object run() throws Exception;
    }

    static String t(Op op) {
        try {
            return String.valueOf(op.run());
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    static final List<LogRecord> got = new ArrayList<>();
    static final List<String> lines = new ArrayList<>();

    /** Snapshots every field inside publish, which is where the source can be inferred. */
    static class Cap extends Handler {
        public void publish(LogRecord r) {
            got.add(r);
            lines.add(r.getLevel() + " name=" + r.getLoggerName() + " src=" + r.getSourceClassName() + "#"
                    + r.getSourceMethodName() + " msg=" + r.getMessage() + " params="
                    + (r.getParameters() == null ? "null" : Arrays.toString(r.getParameters()))
                    + " thrown=" + r.getThrown());
        }

        public void flush() {}

        public void close() {}
    }

    static void show(String tag) {
        for (String s : lines) {
            System.out.println(tag + " " + s);
        }
        lines.clear();
        got.clear();
    }

    static Logger logger(String name) {
        Logger l = Logger.getLogger(name);
        l.setUseParentHandlers(false);
        l.setLevel(Level.ALL);
        l.addHandler(new Cap());
        return l;
    }

    static class Wrap {
        final Logger l;

        Wrap(Logger l) {
            this.l = l;
        }

        void say(String s) {
            l.info(s);
        }
    }

    static class Ctor {
        Ctor(Logger l) {
            l.info("in ctor");
        }

        static void stat(Logger l) {
            l.info("in static");
        }

        void inst(Logger l) {
            l.info("in instance");
        }
    }

    static class MyLogger extends Logger {
        MyLogger() {
            super("rec.my", null);
            setUseParentHandlers(false);
            addHandler(new Cap());
        }

        void hi() {
            info("hi");
        }

        @Override
        public void info(String m) {
            super.info(m + "!");
        }
    }

    static class Worker implements Runnable {
        final Logger l;

        Worker(Logger l) {
            this.l = l;
        }

        public void run() {
            l.info("in thread");
        }
    }

    public static void main(String[] args) throws Exception {
        Logger l = logger("rec");
        levelMethods(l);
        logMethods(l);
        logpMethods(l);
        tracing(l);
        suppliers(l);
        fields();
        logRecordDirect(l);
        inference(l);
        sequence(l);
    }

    static void levelMethods(Logger l) {
        l.config("c");
        l.fine("f");
        l.finer("fr");
        l.finest("ft");
        l.severe("s");
        l.warning("w");
        l.info("i");
        show("level");
        l.log(Level.OFF, "off message");
        l.log(Level.parse("123"), "custom 123");
        show("log");
        l.setLevel(Level.WARNING);
        l.info("hidden");
        l.severe("shown");
        l.log(Level.OFF, "off message passes");
        l.setLevel(Level.OFF);
        l.severe("hidden");
        l.log(Level.OFF, "hidden too");
        l.setLevel(Level.ALL);
        show("filtered");
    }

    static void logMethods(Logger l) {
        l.log(Level.FINE, "one {0}", "x");
        l.log(Level.FINE, "null param", (Object) null);
        Object[] ps = {1, 2};
        l.log(Level.FINE, "arr", ps);
        p("params array identity", got.get(2).getParameters() == ps);
        l.log(Level.FINE, "null array", (Object[]) null);
        l.log(Level.FINE, "empty array", new Object[0]);
        l.log(Level.FINE, "thrown", new IllegalStateException("bad"));
        l.log(Level.FINE, "null thrown", (Throwable) null);
        l.log(Level.FINE, (String) null);
        show("log");
    }

    static void logpMethods(Logger l) {
        l.logp(Level.CONFIG, "C", "m", "msg");
        l.logp(Level.CONFIG, null, "m", "null class");
        l.logp(Level.CONFIG, "C", null, "null method");
        l.logp(Level.CONFIG, "C", "m", "msg {0}", "p");
        l.logp(Level.CONFIG, "C", "m", "msg {0}", new Object[] {"p", "q"});
        l.logp(Level.CONFIG, "C", "m", "msg", new Error("e"));
        show("logp");
    }

    static void tracing(Logger l) {
        l.entering("C", "m");
        l.entering("C", "m", "arg");
        l.entering("C", "m", new Object[] {"a", "b"});
        l.entering("C", "m", new Object[] {"a", "b", "c"});
        l.entering("C", "m", (Object) null);
        l.entering("C", "m", (Object[]) null);
        l.entering("C", "m", new Object[0]);
        l.exiting("C", "m");
        l.exiting("C", "m", "res");
        l.exiting("C", "m", null);
        l.throwing("C", "m", new Exception("x"));
        l.throwing("C", "m", null);
        show("trace");
        l.setLevel(Level.FINE);
        l.entering("C", "m");
        l.exiting("C", "m");
        l.throwing("C", "m", new Exception("y"));
        p("trace hidden at FINE", lines.size());
        l.setLevel(Level.FINER);
        l.entering("C", "m");
        p("trace shown at FINER", lines.size());
        l.setLevel(Level.ALL);
        lines.clear();
        got.clear();
    }

    static void suppliers(Logger l) {
        int[] calls = {0};
        Supplier<String> s = () -> {
            calls[0]++;
            return "supplied";
        };
        l.info(s);
        l.log(Level.INFO, new Exception("tt"), s);
        l.logp(Level.INFO, "C", "m", s);
        l.logp(Level.INFO, "C", "m", new Exception("tt2"), s);
        l.log(Level.INFO, s);
        l.severe(s);
        l.warning(s);
        l.config(s);
        l.fine(s);
        l.finer(s);
        l.finest(s);
        l.log(Level.INFO, () -> null);
        show("supplier");
        p("calls", calls[0]);
        l.setLevel(Level.SEVERE);
        l.info(s);
        l.log(Level.INFO, new Exception("no"), s);
        l.logp(Level.INFO, "C", "m", s);
        p("not called when hidden", calls[0]);
        l.setLevel(Level.ALL);
        p("null supplier", t(() -> {
            l.log(Level.INFO, (Supplier<String>) null);
            return "ok";
        }));
        show("supplier null");
    }

    static void fields() {
        LogRecord x = new LogRecord(Level.INFO, "hi");
        p("defaults", x.getLoggerName() + " " + x.getResourceBundleName() + " " + x.getThrown() + " "
                + x.getParameters() + " " + x.getMessage() + " " + x.getLevel());
        long now = System.currentTimeMillis();
        p("millis is now", Math.abs(x.getMillis() - now) < 10000);
        p("thread id", x.getThreadID() == (int) Thread.currentThread().getId());
        p("no source before publish", x.getSourceClassName() + " " + x.getSourceMethodName());
        p("null level", t(() -> new LogRecord(null, "x")));
        p("null message", new LogRecord(Level.INFO, null).getMessage());
        p("setLevel null", t(() -> {
            x.setLevel(null);
            return "ok";
        }));
        x.setLevel(Level.FINE);
        x.setSequenceNumber(77);
        x.setThreadID(5);
        x.setMillis(123L);
        x.setLoggerName("ln");
        x.setResourceBundleName("rb");
        x.setMessage("mm");
        x.setParameters(new Object[] {1});
        x.setThrown(new Exception("z"));
        x.setSourceClassName("K");
        x.setSourceMethodName("km");
        p("setters", x.getLevel() + " " + x.getSequenceNumber() + " " + x.getThreadID() + " " + x.getMillis() + " "
                + x.getLoggerName() + " " + x.getResourceBundleName() + " " + x.getMessage() + " "
                + x.getParameters().length + " " + x.getThrown() + " " + x.getSourceClassName() + "#"
                + x.getSourceMethodName());
        x.setSourceClassName(null);
        x.setSourceMethodName(null);
        x.setLoggerName(null);
        x.setMessage(null);
        x.setParameters(null);
        x.setThrown(null);
        p("setters null", x.getSourceClassName() + " " + x.getSourceMethodName() + " " + x.getLoggerName() + " "
                + x.getMessage() + " " + x.getParameters() + " " + x.getThrown());
        x.setMillis(-5L);
        p("negative millis", x.getMillis());
        final long[] otherId = new long[2];
        Thread th = new Thread() {
            public void run() {
                otherId[0] = new LogRecord(Level.INFO, "t").getThreadID();
                otherId[1] = getId();
            }
        };
        th.start();
        try {
            th.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        p("other thread id", otherId[0] == (int) otherId[1]);
    }

    static void logRecordDirect(Logger l) {
        LogRecord r = new LogRecord(Level.WARNING, "direct");
        l.log(r);
        p("direct same record", got.get(0) == r);
        show("direct");
        LogRecord named = new LogRecord(Level.WARNING, "direct named");
        named.setLoggerName("someone.else");
        named.setSourceClassName("Pre");
        l.log(named);
        show("direct pre");
        l.setLevel(Level.SEVERE);
        l.log(new LogRecord(Level.WARNING, "below"));
        l.log(new LogRecord(Level.SEVERE, "at level"));
        l.log(new LogRecord(Level.OFF, "off record"));
        l.setLevel(Level.OFF);
        l.log(new LogRecord(Level.SEVERE, "logger off"));
        l.log(new LogRecord(Level.OFF, "logger off, record off"));
        l.setLevel(Level.ALL);
        show("direct levels");
    }

    static void inference(Logger l) throws Exception {
        l.info("plain");
        new Wrap(l).say("wrapped");
        new Ctor(l);
        Ctor.stat(l);
        new Ctor(l).inst(l);
        show("infer");
        MyLogger my = new MyLogger();
        my.hi();
        my.info("direct");
        show("infer subclass");
        Thread th = new Thread(new Worker(l));
        th.start();
        th.join();
        show("infer thread");
        l.logp(Level.INFO, "Given", null, "explicit class only");
        l.logp(Level.INFO, null, null, "explicit nothing");
        show("infer explicit");
        // A handler that does not read the record: the source is gone once publish has returned.
        Logger late = Logger.getLogger("rec.late");
        late.setUseParentHandlers(false);
        LogRecord[] kept = new LogRecord[1];
        late.addHandler(new Handler() {
            public void publish(LogRecord r) {
                kept[0] = r;
            }

            public void flush() {}

            public void close() {}
        });
        late.info("late");
        p("late source", kept[0].getSourceClassName() + " " + kept[0].getSourceMethodName());
        Handler direct = new Cap();
        direct.publish(new LogRecord(Level.INFO, "published by hand"));
        show("infer by hand");
        // method read first
        Logger first = Logger.getLogger("rec.first");
        first.setUseParentHandlers(false);
        first.addHandler(new Handler() {
            public void publish(LogRecord r) {
                System.out.println("method first: " + r.getSourceMethodName() + " " + r.getSourceClassName());
            }

            public void flush() {}

            public void close() {}
        });
        first.info("x");
        // the same record read twice and from a later handler
        Logger twice = Logger.getLogger("rec.twice");
        twice.setUseParentHandlers(false);
        twice.addHandler(new Cap());
        twice.addHandler(new Cap());
        twice.info("twice");
        show("infer twice");
    }

    static void sequence(Logger l) {
        LogRecord a = new LogRecord(Level.INFO, "a");
        LogRecord b = new LogRecord(Level.INFO, "b");
        l.info("consumes one");
        LogRecord c = new LogRecord(Level.INFO, "c");
        l.setLevel(Level.SEVERE);
        l.info("hidden: consumes none");
        l.setLevel(Level.ALL);
        LogRecord d = new LogRecord(Level.INFO, "d");
        p("sequence steps", (b.getSequenceNumber() - a.getSequenceNumber()) + " "
                + (c.getSequenceNumber() - b.getSequenceNumber()) + " "
                + (d.getSequenceNumber() - c.getSequenceNumber()));
        p("logged record number", got.get(0).getSequenceNumber() == b.getSequenceNumber() + 1);
        p("millis order", a.getMillis() <= b.getMillis());
        show("seq");
    }
}
