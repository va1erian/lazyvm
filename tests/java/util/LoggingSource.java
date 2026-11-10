package util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Source inference passes over the glue between a logger and its caller (method references, reflection)
 * and keeps its answer once a record has been read (docs/design/logging.md, section 5).
 */
public class LoggingSource {
    static final List<String> got = new ArrayList<>();

    static class L extends Logger {
        L() {
            super("src.test", null);
        }
    }

    static class Reader extends Handler {
        public void publish(LogRecord r) {
            got.add(r.getMessage() + " -> " + r.getSourceClassName() + "#" + r.getSourceMethodName());
        }

        public void flush() {}

        public void close() {}
    }

    static void viaStatic(Logger l) {
        l.info("static");
    }

    public static void log(Logger l, String msg) {
        l.info(msg);
    }

    public static void main(String[] args) throws Exception {
        Logger l = new L();
        l.addHandler(new Reader());
        l.setUseParentHandlers(false);
        l.info("direct");
        viaStatic(l);
        Consumer<String> ref = l::info;
        ref.accept("method reference");
        Runnable lambda = () -> l.info("lambda");
        lambda.run();
        Method m = LoggingSource.class.getMethod("log", Logger.class, String.class);
        m.invoke(null, l, "reflection");
        Method direct = Logger.class.getMethod("warning", String.class);
        direct.invoke(l, "reflection on Logger");
        for (String s : got) {
            System.out.println(s);
        }
        latched();
    }

    static void latched() {
        Logger l = new L();
        LogRecord[] kept = new LogRecord[1];
        l.addHandler(new Handler() {
            public void publish(LogRecord r) {
                kept[0] = r;
                System.out.println("inside: " + r.getSourceClassName() + "#" + r.getSourceMethodName());
            }

            public void flush() {}

            public void close() {}
        });
        l.setUseParentHandlers(false);
        l.log(Level.INFO, "x");
        System.out.println("after, same record: " + kept[0].getSourceClassName() + "#" + kept[0].getSourceMethodName());
        partial();
    }

    // Setting either name ends inference, and the other one stays null.
    static void partial() {
        Logger l = new L();
        l.setUseParentHandlers(false);
        l.addHandler(new Handler() {
            public void publish(LogRecord r) {
                if (r.getMessage().equals("method")) {
                    r.setSourceMethodName("m");
                } else {
                    r.setSourceClassName("C");
                }
                System.out.println(r.getMessage() + ": " + r.getSourceClassName() + "#" + r.getSourceMethodName());
            }

            public void flush() {}

            public void close() {}
        });
        l.log(Level.INFO, "method");
        l.log(Level.INFO, "class");
    }
}
