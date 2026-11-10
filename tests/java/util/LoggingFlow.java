package util;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Publication order across handlers and parents, filters and handler levels (docs/design/logging.md, section 3). */
public class LoggingFlow {
    static final List<String> order = new ArrayList<>();

    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    /** Records its name when it publishes; it applies its own level and filter like a real handler. */
    static class H extends Handler {
        final String name;

        H(String name, Level level) {
            this.name = name;
            setLevel(level);
        }

        public void publish(LogRecord r) {
            if (isLoggable(r)) {
                order.add(name);
            }
        }

        public void flush() {}

        public void close() {}
    }

    /** Ignores its own level: shows that the logger does not check handler levels. */
    static class Blind extends Handler {
        final String name;

        Blind(String name, Level level) {
            this.name = name;
            setLevel(level);
        }

        public void publish(LogRecord r) {
            order.add(name + (isLoggable(r) ? "+" : "-"));
        }

        public void flush() {}

        public void close() {}
    }

    static void flush(String label) {
        p(label, order);
        order.clear();
    }

    public static void main(String[] args) {
        Logger top = Logger.getLogger("flow");
        Logger mid = Logger.getLogger("flow.mid");
        Logger leaf = Logger.getLogger("flow.mid.leaf");
        top.setUseParentHandlers(false);
        top.addHandler(new H("top1", Level.ALL));
        top.addHandler(new H("top2", Level.ALL));
        mid.addHandler(new H("mid", Level.WARNING));
        leaf.addHandler(new H("leaf", Level.ALL));
        top.setLevel(Level.SEVERE);
        mid.setLevel(Level.INFO);

        leaf.info("i");
        flush("leaf INFO (level inherited from mid)");
        leaf.setLevel(Level.FINE);
        leaf.fine("f");
        flush("leaf FINE (parents' levels are not consulted)");
        leaf.warning("w");
        flush("leaf WARNING");
        leaf.finer("hidden");
        flush("leaf FINER hidden");
        mid.warning("w");
        flush("mid WARNING");
        top.warning("w");
        flush("top WARNING (its own level SEVERE hides it)");
        top.severe("s");
        flush("top SEVERE");

        mid.setUseParentHandlers(false);
        leaf.warning("w");
        flush("mid stops the walk");
        mid.setUseParentHandlers(true);
        leaf.setUseParentHandlers(false);
        leaf.warning("w");
        flush("leaf stops the walk");
        leaf.setUseParentHandlers(true);

        leaf.setFilter(r -> false);
        leaf.warning("w");
        flush("leaf filter refuses: nothing");
        leaf.setFilter(null);
        mid.setFilter(r -> false);
        leaf.warning("w");
        flush("a parent's filter does not gate a child's record");
        mid.warning("w");
        flush("mid filter gates its own");
        mid.setFilter(null);

        Logger blindParent = Logger.getLogger("fl2.blind");
        blindParent.setUseParentHandlers(false);
        blindParent.addHandler(new Blind("blind", Level.SEVERE));
        blindParent.addHandler(new Blind("blind2", Level.OFF));
        blindParent.info("i");
        flush("the logger calls handlers whatever their level");

        // Records seen by filters and handlers carry the logger's name.
        Logger named = Logger.getLogger("fl2.named");
        named.setUseParentHandlers(false);
        named.setFilter(new Filter() {
            public boolean isLoggable(LogRecord r) {
                order.add("filter:" + r.getLoggerName() + ":" + r.getMessage());
                return !r.getMessage().equals("no");
            }
        });
        named.addHandler(new Handler() {
            public void publish(LogRecord r) {
                order.add("handler:" + r.getLoggerName() + ":" + r.getMessage());
            }

            public void flush() {}

            public void close() {}
        });
        named.info("yes");
        named.info("no");
        named.setLevel(Level.SEVERE);
        named.info("below level: the filter is not asked");
        flush("filter then handler");

        // A parent's handlers see the child's logger name, and the order is leaf first.
        Logger parent = Logger.getLogger("fl2.p");
        Logger child = Logger.getLogger("fl2.p.c");
        parent.setUseParentHandlers(false);
        parent.addHandler(new Handler() {
            public void publish(LogRecord r) {
                order.add("parent saw " + r.getLoggerName());
            }

            public void flush() {}

            public void close() {}
        });
        child.addHandler(new H("child", Level.ALL));
        child.info("x");
        flush("handlers: child first, then parent");

        // An exception from a handler reaches the caller and stops the walk.
        Logger boom = Logger.getLogger("fl2.boom");
        boom.setUseParentHandlers(false);
        boom.addHandler(new Handler() {
            public void publish(LogRecord r) {
                order.add("thrower");
                throw new IllegalStateException("from handler");
            }

            public void flush() {}

            public void close() {}
        });
        boom.addHandler(new H("after", Level.ALL));
        try {
            boom.info("x");
            order.add("returned");
        } catch (IllegalStateException e) {
            order.add("caught " + e.getMessage());
        }
        flush("handler exception");

        // The same handler on a logger and its parent is called twice.
        H shared = new H("shared", Level.ALL);
        Logger sp = Logger.getLogger("fl2.s");
        Logger sc = Logger.getLogger("fl2.s.c");
        sp.setUseParentHandlers(false);
        sp.addHandler(shared);
        sc.addHandler(shared);
        sc.info("x");
        flush("shared handler");

        // Handler levels.
        Logger lv = Logger.getLogger("flow.lv");
        lv.setUseParentHandlers(false);
        lv.setLevel(Level.ALL);
        lv.addHandler(new H("warn", Level.WARNING));
        lv.addHandler(new H("all", Level.ALL));
        lv.addHandler(new H("off", Level.OFF));
        lv.addHandler(new H("fine", Level.FINE));
        lv.log(Level.SEVERE, "x");
        lv.log(Level.INFO, "x");
        lv.log(Level.FINE, "x");
        lv.log(Level.FINEST, "x");
        lv.log(Level.OFF, "x");
        flush("handler levels");

        // Adding or removing a handler while publishing takes effect on the next record.
        Logger mod = Logger.getLogger("fl2.mod");
        mod.setUseParentHandlers(false);
        mod.addHandler(new Handler() {
            public void publish(LogRecord r) {
                order.add("first");
                mod.addHandler(new H("added", Level.ALL));
            }

            public void flush() {}

            public void close() {}
        });
        mod.info("1");
        flush("add during publish: this record");
        mod.info("2");
        order.removeIf(s -> s.equals("first"));
        flush("add during publish: next record");
    }
}
